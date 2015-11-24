/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.patching.cli;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;


/**
 * This tool doesn't depend on the patching API.
 *
 * Having the modules in the overlays after applying patches and/or updates,
 * this tool can:
 * <ul>
 * <li>copy the module JARs from the active overlay directory to the directory
 * where the original JAR versions reside;</li>
 * <li>(optionally) keeping a backup and audit info while copying to modules
 * from the overlay directory to the original modules directory which will
 * allow later to revert to the filesystem state before overlay content was
 * copied</li>
 * <li>(optionally) delete the active overlay after copying its content to
 * the original modules directory</li>
 * <li>re-create the active overlay (in case it was deleted) from the backup
 * and audit info recorded during copying the overlay content to the original
 * modules directory</li>
 * <li>remove all the existing overlays (WARN: only the last active overlay
 * can be re-created assuming the user chose to copy its content with
 * the backup enabled</li>
 * </ul>
 *
 * <p>
 * BACKUP AND AUDITING
 * <p>
 * While copying the content, the tool always creates a temporary backup of
 * the overridden files and auditing the files added to be able to revert
 * the changes in case a filesystem failure happens or the JVM crushes.
 * In that case the tool will be able to revert the incomplete update the next
 * time it's launched.
 * In case the tool is launched with the instruction to copy the overlay
 * content to layers or the other way around while there are still leftovers
 * from the previously failed update, the tool will notify the user about that
 * and won't proceed further until it is asked to abort the previous update.
 * <p>
 * Once all the filesystem tasks have been completed, the temporary backup
 * and auditing data can be turned into a permanent backup and auditing data
 * if the user chose to keep the backup or simply deleted.
 * <p>
 * NOTE: the files in the layer directories not overridden by the overlay
 * content WON'T be deleted. Which means if a module was updated from
 * version X to version Y, there will be two versions of the JARs:
 * one for version X and one for version Y. The updated module.xml though
 * will make version Y the only active one.
 * <p>
 *
 * COMMAND-LINE ARGUMENTS
 * <p>
 * <ul>
 * <li>--as-home - the only required argument which is a filesystem path to
 * the AS installation</li>
 * <li>--modules-dir - the modules home directory, if not present, will be
 * assumed to be as-home/modules</li>
 * <li>--abort-update - cleans up after a failed run</li>
 * <li>--no-backup - copy the content from the overlay to the layers without
 * keeping a backup of the replaced JARs</li>
 * <li>--delete-overlay - delete the active overlay directory once its content
 * has been copied to the layers</li>
 * <li>--delete-all-overlays - delete all the existing overlay directories once
 * the content from the active overlay has been copied successfully
 * to the layers</li>
 * <li>--back-to-overlay - assuming the backup and auditing data is present,
 * re-creates the active overlay directory in case it was deleted and
 * removes the active overlay content from the original module directories</li>
 * </ul>
 *
 *
 * @author Alexey Loubyansky
 */
public class TrashOverlaysTool {

    private static final boolean logging = true;

    private static final MessageDigest DIGEST;
    static {
        try {
            DIGEST = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    private static void updateDigest(MessageDigest digest, File file) throws IOException {
        if (file.isDirectory()) {
            File[] childList = file.listFiles();
            if (childList != null) {
                Map<String, File> sortedChildren = new TreeMap<String, File>();
                for (File child : childList) {
                    sortedChildren.put(child.getName(), child);
                }
                for (File child : sortedChildren.values()) {
                    updateDigest(digest, child);
                }
            }
        } else {
            // jar index files are generated by JBoss modules at runtime (a pristine AS7 installation does not have them).
            // they are skipped when computing checksum to avoid different checksum for the same JBoss module depending on
            // whether the AS7 installation has been started or not.
            if (file.getName().endsWith(".jar.index")) {
                return;
            }
            BufferedInputStream bis = null;
            try {

                bis = new BufferedInputStream(new FileInputStream(file));
                byte[] bytes = new byte[8192];
                int read;
                while ((read = bis.read(bytes)) > -1) {
                    digest.update(bytes, 0, read);
                }
            } finally {
                safeClose(bis);
            }
        }
    }
    private static byte[] hashFile(File file) throws IOException {
        synchronized (DIGEST) {
            DIGEST.reset();
            updateDigest(DIGEST, file);
            return DIGEST.digest();
        }
    }

    private static final int DEFAULT_BUFFER_SIZE = 65536;

    private static final String ADDED = ".overlay_added";
    private static final String BACKUP = ".overlay_backup";
    private static final String OVERLAYS = ".overlays";
    private static final String OVERLAY_REVERT = ".overlay_revert";
    private static final String SAME = ".same";
    private static final String TMP_BACKUP = ".tmp_backup";

    private static final String ABORT_UPDATE_ARG = "--abort-update";
    private static final String AS_HOME_ARG = "--as-home";
    private static final String BACK_TO_OVERLAY_ARG = "--back-to-overlay";
    private static final String DELETE_ALL_OVERLAYS_ARG = "--delete-all-overlays";
    private static final String DELETE_OVERLAY_ARG = "--delete-overlay";
    private static final String MODULES_DIR_ARG = "--modules-dir";
    private static final String NO_BACKUP_ARG = "--no-backup";

    private static final String LS = System.getProperty("line.separator");

    enum MainTask {
        OVERLAY_TO_LAYER,
        LAYER_TO_OVERLAY
    }

    enum OverlayAction {
        NOT_AFFECTED,
        DELETE_CURRENT,
        DELETE_ALL
    }

    public static void main(String[] args) throws Exception {

        String asHomeArg = null;
        String modulesDirArg = null;
        boolean abortUpdate = false;
        boolean backup = true;
        MainTask task = MainTask.OVERLAY_TO_LAYER;
        OverlayAction overlayAction = OverlayAction.NOT_AFFECTED;
        for(String arg : args) {
            if(arg.startsWith(AS_HOME_ARG)) {
                asHomeArg = arg.substring(AS_HOME_ARG.length() + 1);
            } else if(arg.startsWith(ABORT_UPDATE_ARG)) {
                abortUpdate = true;
            } else if(arg.startsWith(NO_BACKUP_ARG)) {
                backup = false;
            } else if(arg.startsWith(BACK_TO_OVERLAY_ARG)) {
                task = MainTask.LAYER_TO_OVERLAY;
            } else if(arg.startsWith(DELETE_OVERLAY_ARG)) {
                overlayAction = OverlayAction.DELETE_CURRENT;
            } else if(arg.startsWith(DELETE_ALL_OVERLAYS_ARG)) {
                overlayAction = OverlayAction.DELETE_ALL;
            } else if(arg.startsWith(MODULES_DIR_ARG)) {
                modulesDirArg = arg.substring(MODULES_DIR_ARG.length() + 1);
            } else {
                unknownArg(arg);
            }
        }

        if(asHomeArg == null) {
            missingArg(AS_HOME_ARG);
        }
        final File asHome = new File(asHomeArg);
        if(!asHome.exists()) {
            error(asHome.getAbsolutePath() + " does not exist");
        }
        final File modulesDir = modulesDirArg == null ? newFile(asHome, "modules") : new File(modulesDirArg);
        if(!modulesDir.exists()) {
            error(modulesDir.getAbsolutePath() + " does not exist");
        }
        final File layersDir = newFile(modulesDir, "system", "layers");

        final TrashOverlaysTool tool = new TrashOverlaysTool(asHome, task, overlayAction, backup);
        if(tool.checkForLeftOvers(layersDir, abortUpdate)) {
            return;
        }
        tool.processLayers(layersDir);
    }

    private final File asHome;
    private final MainTask task;
    private final OverlayAction overlayAction;
    private final boolean backup;

    private TaskList mainTasks = new TaskList();
    private TaskList finishingTasks = new TaskList();
    private TaskList deleteTasks = new TaskList();

    private TrashOverlaysTool(File asHome, MainTask task, OverlayAction overlayAction, boolean backup) {
        this.asHome = asHome;
        this.task = task;
        this.overlayAction = overlayAction;
        this.backup = backup;
    }

    protected boolean checkForLeftOvers(final File layersDir, boolean abortUpdate) throws IOException {
        // prepare the tasks
        for(File layerDir : layersDir.listFiles()) {
            checkLayerForLeftovers(layerDir);
        }
        if(!mainTasks.isEmpty()) {
            if(!abortUpdate) {
                log("The installation appears to be in a state of an incomplete update.");
                log("Use " + ABORT_UPDATE_ARG + " to go back to the previous consistent state.");
                return true;
            }
            mainTasks.safeRollback();
            mainTasks.clear();
            log("The update was aborted.");
            return true;
        }
        return false;
    }

    protected void checkLayerForLeftovers(final File layer) throws IOException {
        for(File f : layer.listFiles()) {
            if(!f.getName().equals(OVERLAYS)) {
                checkDirForLeftovers(f);
            }
        }
    }
    protected void checkDirForLeftovers(final File dir) throws IOException {
        for(File f : dir.listFiles()) {
            if (f.isFile()) {
                final String fileName = f.getName();
                if (fileName.endsWith(ADDED)) {
                    final File t = new File(f.getParentFile(), fileName.substring(0, fileName.length() - ADDED.length()));
                    mainTasks.add(new AddTask(t, t, true));
                } else if (fileName.endsWith(TMP_BACKUP)) {
                    final File t = new File(f.getParentFile(), fileName.substring(0, fileName.length() - TMP_BACKUP.length()));
                    mainTasks.add(new OverrideTask(t, t, true));
                } else if (fileName.endsWith(SAME)) {
                    deleteTasks.add(new DeleteFileTask(f));
                }
            } else {
                checkDirForLeftovers(f);
            }
        }
    }

    protected void processLayers(final File layersDir) throws IOException {

        // prepare the tasks
        if(task == MainTask.OVERLAY_TO_LAYER) {
            for(File layerDir : layersDir.listFiles()) {
                scheduleOverlayToLayerCopy(layerDir);
            }
        } else if(task == MainTask.LAYER_TO_OVERLAY) {
            for(File layerDir : layersDir.listFiles()) {
                scheduleLayerToOverlayRevert(layerDir);
            }
        } else {
            error("Unknown task: " + task);
        }

        if(mainTasks.isEmpty()) {
            log("no content to copy");
        }

        final AtomicTaskLists lists = new AtomicTaskLists();
        lists.add(mainTasks);
        lists.add(finishingTasks);
        lists.execute();

        deleteTasks.execute();
    }

    protected void scheduleLayerToOverlayRevert(final File layerDir) throws IOException {
        log("processing layer " + layerDir.getName());
        File overlays = new File(layerDir, OVERLAYS);
        String activeOverlay = null;
        final File overlayBackupFile = new File(overlays, OVERLAYS + BACKUP);
        if (overlayBackupFile.exists()) {
            activeOverlay = readFile(overlayBackupFile).trim();
            finishingTasks.add(new MoveTask(overlayBackupFile, OVERLAYS));
        }
        if(activeOverlay == null) {
            activeOverlay = readCPFromLayerConf(layerDir.getName());
            mainTasks.add(new WriteFileTask(new File(overlays, OVERLAYS), activeOverlay + LS));
        }

        File activeOverlayDir = new File(overlays, activeOverlay);
        if(activeOverlayDir.exists()) { // if it doesn't exist it'll be re-created
            activeOverlayDir = null;
        }
        for(File f : layerDir.listFiles()) {
            if(!f.getName().equals(OVERLAYS)) {
                scheduleLayerToOverlayDirTasks(f, activeOverlayDir);
            }
        }
    }

    protected String readCPFromLayerConf(final String layerName) throws IOException {
        final File layerConf = newFile(asHome, ".installation", "layers", layerName, "layer.conf");
        if(layerConf.exists()) {
            final Properties props = new Properties();
            FileReader reader = null;
            try {
                reader = new FileReader(layerConf);
                props.load(reader);
            } finally {
                safeClose(reader);
            }
            return props.getProperty("cumulative-patch-id");
        }
        return null;
    }

    protected void scheduleLayerToOverlayDirTasks(File src, File overlayDir) throws IOException {
        if(src.isDirectory()) {
            if(overlayDir != null) {
                overlayDir = new File(overlayDir, src.getName());
            }
            for(File c : src.listFiles()) {
                scheduleLayerToOverlayDirTasks(c, overlayDir);
            }
        } else {
            if(src.getName().endsWith(BACKUP)) {
                final String originalName = src.getName().substring(0, src.getName().length() - BACKUP.length());
                if(overlayDir != null) {
                    final File overlayFile = new File(src.getParentFile(), originalName);
                    if (overlayFile.exists()) {
                        final AddTask addToOverlay = new AddTask(overlayFile, new File(overlayDir, originalName));
                        mainTasks.add(addToOverlay);
                        deleteTasks.add(new DeleteFileTask(addToOverlay.auditFile));
                    } else {
                        error("Original overlay file does not exist: " + overlayFile.getAbsolutePath());
                    }
                }
                mainTasks.add(new MoveTask(src, originalName));

            } else if(src.getName().equals(OVERLAY_REVERT)) {
                boolean added = true;
                for(String name : readList(src)) {
                    if(name.equals(ADDED)) {
                        added = true;
                        continue;
                    } else if(name.equals(SAME)) {
                        added = false;
                        continue;
                    }
                    final File f = new File(src.getParentFile(), name);
                    if (f.exists()) {
                        if (overlayDir != null) {
                            final AddTask addToOverlay = new AddTask(f, new File(overlayDir, name));
                            mainTasks.add(addToOverlay);
                            deleteTasks.add(new DeleteFileTask(addToOverlay.auditFile));
                        }
                        if(added) {
                            deleteTasks.add(new DeleteFileTask(f));
                        }
                    }
                }
                deleteTasks.add(new DeleteFileTask(src));
            }
        }
    }

    protected void scheduleOverlayToLayerCopy(final File layerDir) throws IOException {
        log("processing layer " + layerDir.getName());
        final File allOverlays = new File(layerDir, OVERLAYS);
        if(!allOverlays.exists()) {
            log("layer " + layerDir.getName() + " is not overlayed");
            return;
        }
        final File activeOverlayFile = new File(allOverlays, OVERLAYS);
        if(!activeOverlayFile.exists()) {
            log("layer " + layerDir.getName() + " does not specify an active overlay");
            return;
        }
        final String activeOverlay = readFile(activeOverlayFile).trim();
        if(activeOverlay.isEmpty()) {
            log("layer " + layerDir.getName() + " is not overlayed");
            return;
        }
        log("active overlay for " + layerDir.getName() + " is " + activeOverlay);
        final File curOverlay = new File(allOverlays, activeOverlay);
        if(!curOverlay.exists()) {
            error("Specified active overlay " + activeOverlay + " does not exist for layer " + layerDir.getName());

        }
        finishingTasks.add(new MoveTask(activeOverlayFile, activeOverlayFile.getName() + BACKUP));
        scheduleOverlayToLayerDirTasks(curOverlay, layerDir);
        if(overlayAction == OverlayAction.DELETE_ALL) {
            deleteTasks.add(new DeleteFileTask(allOverlays));
        } else if(overlayAction == OverlayAction.DELETE_CURRENT) {
            deleteTasks.add(new DeleteFileTask(curOverlay));
        }
    }

    private void scheduleOverlayToLayerDirTasks(File from, File to) throws IOException {

        OverlayUndoFileTask undoFileTask = null;
        for(File f : from.listFiles()) {
            if(f.isFile()) {
                final File t = new File(to, f.getName());
                if(t.exists()) {
                    if(Arrays.equals(hashFile(t), hashFile(f))) {
                        //log("target and source files match " + t.getAbsolutePath());
                        if(backup) {
                            final SameFileTask sameTask = new SameFileTask(f);
                            mainTasks.add(sameTask);
                            if (undoFileTask == null) {
                                undoFileTask = new OverlayUndoFileTask(t.getParentFile());
                                finishingTasks.add(undoFileTask);
                            }
                            undoFileTask.recordSame(f.getName(), sameTask.createFile);
                        }
                    } else {
                        //log("target file will be overriden " + t.getAbsolutePath());
                        final OverrideTask overrideTask = new OverrideTask(f, t);
                        mainTasks.add(overrideTask);
                        if(backup) {
                            finishingTasks.add(new MoveTask(overrideTask.backup, t.getName() + BACKUP));
                        } else {
                            deleteTasks.add(new DeleteFileTask(overrideTask.backup));
                        }
                    }
                } else {
                    //log(f.getAbsolutePath() + " will be added");
                    final AddTask addTask = new AddTask(f, t);
                    mainTasks.add(addTask);
                    if (backup) {
                        if (undoFileTask == null) {
                            undoFileTask = new OverlayUndoFileTask(t.getParentFile());
                            finishingTasks.add(undoFileTask);
                        }
                        undoFileTask.recordAdd(f.getName(), addTask.auditFile);
                    } else {
                        deleteTasks.add(new DeleteFileTask(addTask.auditFile));
                    }
                }
            } else {
                scheduleOverlayToLayerDirTasks(f, new File(to, f.getName()));
            }
        }
    }

    private static String readFile(File f) throws IOException {
        BufferedReader reader = null;
        StringWriter strWriter = new StringWriter();
        BufferedWriter writer = new BufferedWriter(strWriter);
        try {
            reader = new BufferedReader(new FileReader(f));
            String line = reader.readLine();
            if(line != null) {
                writer.write(line);
                line = reader.readLine();
                while (line != null) {
                    writer.newLine();
                    writer.write(line);
                }
            }
        } finally {
            safeClose(reader);
            safeClose(writer);
        }
        return strWriter.toString();
    }

    private static List<String> readList(File f) throws IOException {
        BufferedReader reader = null;
        List<String> result = Collections.emptyList();
        try {
            reader = new BufferedReader(new FileReader(f));
            String line = reader.readLine();
            if(line != null) {
                result = Collections.singletonList(line);
                line = reader.readLine();
                while (line != null) {
                    if(result.size() == 1) {
                        result = new ArrayList<String>(result);
                    }
                    result.add(line);
                    line = reader.readLine();
                }
            }
        } finally {
            safeClose(reader);
        }
        return result;
    }

    private static void safeClose(Closeable c) {
        if(c == null) {
            return;
        }
        try {
            c.close();
        } catch(IOException e) {
        }
    }

    private static File newFile(File parent, String... segments) {
        File f = new File(parent, segments[0]);
        if(segments.length > 1) {
            for(int i = 1; i < segments.length; ++i) {
                f = new File(f, segments[i]);
            }
        }
        return f;
    }

    private static void log(String msg) {
        if(logging) {
            System.out.println(msg);
        }
    }
    private static void missingArg(String name) {
        error("unknown argument '" + name + "'");
    }
    private static void unknownArg(String name) {
        error("unknown argument '" + name + "'");
    }
    private static void error(String msg) {
        System.out.println("ERROR: " + msg);
        System.exit(1);
    }

    private static void assertDoesNotExist(File f) {
        if(f.exists()) {
            error(f.getAbsolutePath() + " already exists.");
        }
    }

    private static void writeFile(File f, String content) throws IOException {
        FileWriter writer = null;
        if(!f.getParentFile().exists()) {
            if(!f.getParentFile().mkdirs()) {
                error("Failed to create directory " + f.getParentFile().getAbsolutePath());
            }
        }
        try {
            writer = new FileWriter(f);
            writer.write(content);
        } finally {
            safeClose(writer);
        }
    }
    private static void copy(File source, File target) throws IOException {
        final FileInputStream is = new FileInputStream(source);
        try {
            copy(is, target);
        } finally {
            safeClose(is);
        }
    }
    private static void copy(final InputStream is, final File target) throws IOException {
        if(! target.getParentFile().exists()) {
            target.getParentFile().mkdirs();
        }
        final OutputStream os = new FileOutputStream(target);
        try {
            copyStream(is, os);
        } finally {
            safeClose(os);
        }
    }
    private static void copyStream(InputStream is, OutputStream os) throws IOException {
        copyStream(is, os, DEFAULT_BUFFER_SIZE);
    }
    private static void copyStream(InputStream is, OutputStream os, int bufferSize) throws IOException {
        byte[] buff = new byte[bufferSize];
        int rc;
        while ((rc = is.read(buff)) != -1) os.write(buff, 0, rc);
        os.flush();
    }
    private static boolean recursiveDelete(File root) {
        if (root == null) {
            return true;
        }
        boolean ok = true;
        if (root.isDirectory()) {
            final File[] files = root.listFiles();
            for (File file : files) {
                ok &= recursiveDelete(file);
            }
            return ok && (root.delete() || !root.exists());
        } else {
            ok &= root.delete() || !root.exists();
        }
        return ok;
    }

    private static class TaskList {
        private List<FileTask> tasks = Collections.emptyList();
        void add(FileTask task) {
            switch(tasks.size()) {
                case 0:
                    tasks = Collections.singletonList(task);
                    break;
                case 1:
                    tasks = new ArrayList<FileTask>(tasks);
                default:
                    tasks.add(task);
            }
        }
        void execute() throws IOException {
            int i = 0;
            while(i < tasks.size()) {
                try {
                    tasks.get(i++).execute();
                } catch (RuntimeException | Error | IOException t) {
                    --i;
                    while(i >= 0) {
                        tasks.get(i--).safeRollback();
                    }
                    throw t;
                }
            }
        }
        void safeRollback() {
            for(FileTask task : tasks) {
                task.safeRollback();
            }
        }
        boolean isEmpty() {
            return tasks.isEmpty();
        }
        void clear() {
            tasks = Collections.emptyList();
        }
    }

    private static class OverlayUndoFileTask extends FileTask {
        private final File undoFile;
        private List<String> addedNames = Collections.emptyList();
        private List<File> addedFiles = Collections.emptyList();
        private List<String> sameNames = Collections.emptyList();
        private List<File> sameFiles = Collections.emptyList();
        OverlayUndoFileTask(File dir) throws IOException {
            undoFile = new File(dir, OVERLAY_REVERT);
            if(undoFile.exists()) {
                throw new IOException(undoFile.getAbsolutePath() + " already exists");
            }
        }
        void recordAdd(String name, File auditFile) {
            switch(addedNames.size()) {
                case 0:
                    addedNames = Collections.singletonList(name);
                    addedFiles = Collections.singletonList(auditFile);
                    break;
                case 1:
                    addedNames = new ArrayList<String>(addedNames);
                    addedFiles = new ArrayList<File>(addedFiles);
                default:
                    addedNames.add(name);
                    addedFiles.add(auditFile);
            }
        }
        void recordSame(String name, File auditFile) {
            switch(sameNames.size()) {
                case 0:
                    sameNames = Collections.singletonList(name);
                    sameFiles = Collections.singletonList(auditFile);
                    break;
                case 1:
                    sameNames = new ArrayList<String>(sameNames);
                    sameFiles = new ArrayList<File>(sameFiles);
                default:
                    sameNames.add(name);
                    sameFiles.add(auditFile);
            }
        }
        @Override
        void execute() throws IOException {
            BufferedWriter writer = null;
            try {
                writer = new BufferedWriter(new FileWriter(undoFile));
                if (!addedNames.isEmpty()) {
                    writer.write(ADDED);
                    writer.newLine();
                    for (String name : addedNames) {
                        writer.write(name);
                        writer.newLine();
                    }
                }
                if (!sameNames.isEmpty()) {
                    writer.write(SAME);
                    writer.newLine();
                    for (String name : sameNames) {
                        writer.write(name);
                        writer.newLine();
                    }
                }
            } finally {
                safeClose(writer);
            }
            for(File f : addedFiles) {
                recursiveDelete(f);
            }
            for(File f : sameFiles) {
                recursiveDelete(f);
            }
        }
        @Override
        void rollback() throws IOException {
            for(int i = 0; i < addedFiles.size(); ++i) {
                writeFile(addedFiles.get(i), addedNames.get(i));
            }
            for(int i = 0; i < sameFiles.size(); ++i) {
                writeFile(sameFiles.get(i), sameNames.get(i));
            }
            recursiveDelete(undoFile);
        }
    }
    private static final class OverrideTask extends CopyTask {
        private final File backup;
        OverrideTask(File src, File trg) {
            this(src, trg, false);
        }
        OverrideTask(File src, File trg, boolean forRollback) {
            super(src, trg);
            backup = new File(trg.getParentFile(), trg.getName() + TMP_BACKUP);
            if(!forRollback) {
                assertDoesNotExist(backup);
            }
        }
        @Override
        void execute() throws IOException {
            if(backup != null) {
                copy(trg, backup);
            }
            super.execute();
        }
        @Override
        void rollback() throws IOException {
            copy(backup, trg);
            recursiveDelete(backup);
        }
    }
    private static final class AddTask extends CopyTask {
        private final File auditFile;
        AddTask(File src, File trg) {
            this(src, trg, false);
        }
        AddTask(File src, File trg, boolean forRollback) {
            super(src, trg);
            auditFile = new File(trg.getParentFile(), src.getName() + ADDED);
            if(!forRollback) {
                assertDoesNotExist(auditFile);
            }
        }
        @Override
        void execute() throws IOException {
            if(!auditFile.getParentFile().exists() && !auditFile.getParentFile().mkdirs()) {
                throw new IOException("Failed to create directory " + auditFile.getParentFile().getAbsolutePath());
            }
            writeFile(auditFile, src.getName());
            super.execute();
        }
        @Override
        void rollback() throws IOException {
            super.rollback();
            if(!trg.exists()) {
                if(auditFile.exists()) {
                    recursiveDelete(auditFile);
                }
            }
        }
    }
    private static class MoveTask extends CopyTask {
        MoveTask(File src, String name) {
            super(src, new File(src.getParentFile(), name));
        }
        @Override
        void execute() throws IOException {
            super.execute();
            recursiveDelete(src);
        }
        @Override
        void rollback() throws IOException {
            copy(trg, src);
            super.rollback();
        }
    }
    private abstract static class CopyTask extends TwoFilesTask {
        CopyTask(File src, File trg) {
            super(src, trg);
        }
        @Override
        void execute() throws IOException {
            copy(src, trg);
        }
        @Override
        void rollback() throws IOException {
            recursiveDelete(trg);
        }
    }
    private abstract static class TwoFilesTask extends FileTask {
        protected final File src;
        protected final File trg;
        TwoFilesTask(File src, File trg) {
            this.src = src;
            this.trg = trg;
        }
    }
    private static class DeleteFileTask extends FileTask {
        private final File f;
        DeleteFileTask(File f) {
            this.f = f;
        }
        @Override
        void execute() throws IOException {
            recursiveDelete(f);
        }
        @Override
        void rollback() throws IOException {
            throw new UnsupportedOperationException();
        }
    }
    private static class SameFileTask extends WriteFileTask {
        SameFileTask(File f) {
            super(new File(f.getParentFile(), f.getName() + SAME), f.getName());
        }
    }
    private static class WriteFileTask extends FileTask {
        final File createFile;
        private final String content;
        WriteFileTask(File f, String content) {
            this.createFile = f;
            this.content = content;
        }
        @Override
        void execute() throws IOException {
            writeFile(createFile, content);
        }
        @Override
        void rollback() throws IOException {
            recursiveDelete(createFile);
        }
    }
    private abstract static class FileTask {
        abstract void execute() throws IOException;
        abstract void rollback() throws IOException;
        void safeRollback() {
            try {
                rollback();
            } catch(RuntimeException | Error | IOException e) {
                // ignore
            }
        }
    }

    private static class AtomicTaskLists {
        private List<TaskList> tasks = Collections.emptyList();
        AtomicTaskLists add(TaskList list) {
            switch(tasks.size()) {
                case 0:
                    tasks = Collections.singletonList(list);
                    break;
                case 1:
                    tasks = new ArrayList<TaskList>(tasks);
                default:
                    tasks.add(list);
            }
            return this;
        }
        void execute() throws IOException {
            int i = 0;
            while(i < tasks.size()) {
                final TaskList taskList = tasks.get(i++);
                try {
                    taskList.execute();
                } catch (RuntimeException | Error | IOException t) {
                    safeRollback(i - 2);
                    throw t;
                }
            }
        }
        void safeRollback(int i) {
            while(i >= 0) {
                (tasks.get(i--)).safeRollback();
            }
        }
    }
}
