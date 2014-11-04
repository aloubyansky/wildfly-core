/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
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

package org.jboss.as.controller.persistence.fs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.registry.Resource.ResourceEntry;
import org.jboss.dmr.ModelNode;

/**
 * A utility class for file system management model persistence.
 *
 * @author Alexey Loubyansky
 */
public class FSPersistence {

    public static final String RESOURCE_FILE_NAME = "attributes.dmr";
    public static final String ORDER_FILE_NAME = "order.txt";

    /** file system name escape character */
    private static final char ESCAPE_CHAR = '%';

    /**
     * Persists the resource to the file system directory.
     * The resource will not be persisted if it is a proxy or if it is
     * a runtime resource, also if its registration indicates that it's
     * an alias, a remote or a runtime only.
     *
     * WARNING: after persisting, the directory structure
     * and its content will match the resource structure and data exactly,
     * i.e. every file or directory which existed there before
     * but does not correspond to anything in the current resource will be removed.
     *
     * @param registration
     * @param res
     * @param dir
     * @throws IOException
     */
    public static void persist(ImmutableManagementResourceRegistration registration,
            Resource res, File dir) throws IOException {
        persist(registration, res, dir, true);
    }

    public static void persist(ImmutableManagementResourceRegistration registration,
            Resource res, File dir, boolean removeNotOverriden) throws IOException {
        persist(registration, res, dir, removeNotOverriden, Collections.<String>emptySet());
    }

    public static void persist(ImmutableManagementResourceRegistration registration,
            Resource res, File dir, boolean removeNotOverriden, Set<String> ignoreTypes) throws IOException {

        if(/*dir.getName().equals("module-loading") ||*/
                dir.getName().equals("ignored-resources") ||
                dir.getName().equals("host-environment") ||
                dir.getName().equals("discovery-options")) {
            System.out.println("PERSIST: " + dir.getAbsolutePath() + " " +
                registration.isAlias() + " " +
                registration.isRemote() + " " +
                registration.isRuntimeOnly() + " " +
                res.isProxy() + " " +
                res.isRuntime());
            return;
        }

        if(!isPersistent(res, registration)) {
            return;
        }

        final Set<String> resourceTypes = res.getChildTypes();
        if(!ignoreTypes.isEmpty()) {
            resourceTypes.removeAll(ignoreTypes);
        }
        if(resourceTypes.isEmpty()) {
            if(dir.exists()) {
                if (removeNotOverriden) {
                    for (File child : dir.listFiles()) {
                        rmrf(child);
                    }
                }
            } else {
                if(!dir.mkdirs()) {
                    throw new IOException("Failed to create " + dir.getAbsolutePath());
                }
            }
        } else {
            // types that should be removed from the filesystem
            final Set<String> fsTypesToRemove;
            if(dir.exists()) {
                if(removeNotOverriden) {
                    fsTypesToRemove = new HashSet<String>(Arrays.asList(dir.list(new FilenameFilter() {
                        @Override
                        public boolean accept(File dir, String name) {
                            return !name.equals(RESOURCE_FILE_NAME) && !name.equals(ORDER_FILE_NAME);
                        }
                    })));
                } else {
                    fsTypesToRemove = Collections.emptySet();
                }
            } else {
                if(!dir.mkdirs()) {
                    throw new IOException("Failed to create " + dir.getAbsolutePath());
                }
                fsTypesToRemove = Collections.emptySet();
            }

            for(String resType : resourceTypes) {
                fsTypesToRemove.remove(resType);
                final Set<ResourceEntry> resourceChildren = res.getChildren(resType);
                final File fsType = new File(dir, resType);

                if(resourceChildren.isEmpty()) {
                    if(fsType.exists()) {
                        if (removeNotOverriden) {
                            for (File child : fsType.listFiles()) {
                                rmrf(child);
                            }
                        }
                    } else {
                        if(!fsType.mkdir()) {
                            throw new IOException("Failed to create " + fsType.getAbsolutePath());
                        }
                    }
                } else {
                    final Set<String> fsChildrenToRemove;
                    if(fsType.exists() && removeNotOverriden) {
                        fsChildrenToRemove = new HashSet<String>(Arrays.asList(fsType.list()));
                    } else {
                        fsChildrenToRemove = Collections.emptySet();
                    }

                    for(ResourceEntry child : resourceChildren) {
                        if(!isPersistent(child)) {
                            continue;
                        }
                        final ImmutableManagementResourceRegistration childReg;
                        final String childName = child.getName();
                        if(childName.trim().isEmpty()) {
                            throw new IllegalStateException("Child name is empty. Resource type " + resType + ", path '" + dir.getAbsolutePath() + "'");
                        }
                        if(registration == null) {
                            childReg = null;
                        } else {
                            childReg = registration.getSubModel(
                                PathAddress.pathAddress(PathElement.pathElement(resType, childName)));
                            if (childReg == null) {
                                throw new IllegalStateException("Child not registered: type="
                                        + resType + ", name="
                                        + childName);
                            }
                            if(!isPersistent(childReg)) {
                                continue;
                            }
                        }
                        fsChildrenToRemove.remove(childName);
                        final String childDirName = encodeDirName(childName);
                        final File childDir = new File(fsType, childDirName);
                        if(childDir.getName().trim().isEmpty()) {
                            throw new IllegalStateException("Empty dir name: '" + childDir.getAbsolutePath() + "', '"
                                + fsType.getAbsolutePath() + "', '" + childName + "'");
                        }
                        persist(childReg, child, childDir, removeNotOverriden);
                    }

                    if(!fsChildrenToRemove.isEmpty()) {
                        for (String child : fsChildrenToRemove) {
                            rmrf(new File(fsType, child));
                        }
                    }
                }

                if(!fsTypesToRemove.isEmpty()) {
                    for (String type : fsTypesToRemove) {
                        rmrf(new File(dir, type));
                    }
                }
            }
        }

        final ModelNode resourceModel = res.getModel();
        if(registration != null && resourceModel.isDefined()) {
            final ModelNode configModel = new ModelNode();
            for(String attr : resourceModel.keys()) {
                if(!res.getChildTypes().contains(attr) && // TODO this check indicates we need to clean the model up
                        isConfigAttribute(registration, attr, dir + " " + res.getChildTypes())) {
                    configModel.get(attr).set(resourceModel.get(attr));
                }
            }
            writeResourceFile(dir, configModel);
        } else {
            writeResourceFile(dir, resourceModel);
        }
    }

    /**
     * Reads the resource stored in the directory.
     *
     * @param dir
     * @return
     * @throws IOException
     */
    public static Resource readResource(File dir) throws IOException {

        if(dir == null) {
            throw new IllegalArgumentException("The dir is null");
        }
        if(!dir.isDirectory()) {
            throw new IllegalArgumentException(dir.getAbsolutePath() + " is not a directory");
        }

        final Resource resource = Resource.Factory.create();

        final ModelNode model = readResourceFile(dir);
        resource.writeModel(model);

        for(File type : dir.listFiles()) {
            if(!type.isDirectory()) {
                if(!RESOURCE_FILE_NAME.equals(type.getName()) && !ORDER_FILE_NAME.equals(type.getName())) {
                    // this is strict wrt to the content but it can always be relaxed later
                    throw new IllegalStateException("Unexpected file: " + type.getAbsolutePath());
                }
            } else {

                final File[] children = type.listFiles();
                for(File child : children) {
                    if(child.isDirectory()) {
                        try {
                        resource.registerChild(
                                PathElement.pathElement(decodeDirName(type.getName()), decodeDirName(child.getName())),
                                readResource(child));
                        } catch(StringIndexOutOfBoundsException e) {
                            System.out.println("'" + type.getName() + "' -> '" + decodeDirName(type.getName()) + "', '" +
                                child.getName() + "' -> '" + decodeDirName(child.getName()) + "'");
                            throw e;
                        }
                    } else {
                        // this is strict wrt to the content but it can always be relaxed later
                        throw new IllegalStateException("Unexpected file " + child.getAbsolutePath());
                    }
                }
            }
        }
        return resource;
    }

    /**
     * Stores the model to the resource file.
     *
     * @param dir  target directory
     * @param model  the model to store
     * @throws IOException
     */
    public static void writeResourceFile(File dir, ModelNode model) throws IOException {
        if(dir.getName().trim().isEmpty()) {
            throw new IllegalStateException("'" + dir.getAbsolutePath() + "'");
        }
        if(model == null) {
            throw new IllegalArgumentException("Model is null");
        }
        if(!dir.exists()) {
            throw new IllegalArgumentException("Directory does not exist: " + dir.getAbsolutePath());
        }
        final File f = new File(dir, RESOURCE_FILE_NAME);
        FileWriter writer = null;
        try {
            writer = new FileWriter(f);
            writer.write(model.asString());
        } catch (IOException e) {
            throw new IOException("Failed to persist resource " + dir.getName(), e);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * Reads the DMR file storing resource attributes.
     *
     * @param dir
     * @return
     * @throws IOException
     */
    public static ModelNode readResourceFile(File dir) throws IOException {
        final File f = new File(dir, RESOURCE_FILE_NAME);
        if(!f.exists()) {
            return new ModelNode();
        }
        if(!f.isFile()) {
            throw new IllegalStateException(f.getAbsolutePath() + " is not a file");
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            return ModelNode.fromStream(fis);
        } catch(IOException e) {
            throw new IOException("Failed to read resource from " + f.getAbsolutePath(), e);
        } finally {
            if(fis != null) {
                fis.close();
            }
        }
    }

    /**
     * Removes a directory with all its content.
     *
     * @param f
     * @return
     */
    public static boolean rmrf(File f) {
        if(f == null) {
            return true;
        }
        if(!f.exists()) {
            return true;
        }
        if(f.isFile()) {
            return f.delete();
        }
        for(File c : f.listFiles()) {
            if(!rmrf(c)) {
                return false;
            }
        }
        return f.delete();
    }

    public static List<ResourceDiff> diff(Resource actual, File dir) throws IOException {
        return diff(actual, readResource(dir));
    }

    public static List<ResourceDiff> diff(Resource actual, Resource target) {
        final ArrayList<ResourceDiff> diff = new ArrayList<ResourceDiff>();
        diff(new ModelNode().setEmptyList(), actual, target, diff);
        return diff;
    }

/*    private static List<ResourceDiff> diff(ModelNode address, Resource actual, File dir)
            throws IOException {
        return diff(null, address, actual, dir);
    }
*/
    public static List<ResourceDiff> diff(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource actual, File dir) throws IOException {
        return diff(registration, address, actual, dir, false);
    }

    public static List<ResourceDiff> diff(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource actual, File dir, boolean ignoreMissingChildRegistration) throws IOException {
        final ArrayList<ResourceDiff> diff = new ArrayList<ResourceDiff>();
        //diff(registration, address, actual, readResource(dir), diff, ignoreMissingChildRegistration);
        diff(registration, address, actual, dir, diff, ignoreMissingChildRegistration);
        return diff;
    }

    public static List<ResourceDiff> diff(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource actual, File dir, boolean ignoreMissingChildRegistration,
            Set<String> skipTypes) throws IOException {
        final ArrayList<ResourceDiff> diff = new ArrayList<ResourceDiff>();
        diff(registration, address, actual, dir, diff, ignoreMissingChildRegistration, skipTypes);
        return diff;
    }

    private static void diff(ModelNode address, Resource actual, Resource target, List<ResourceDiff> diffList) {
        diff(null, address, actual, target, diffList);
    }

    private static void diff(ImmutableManagementResourceRegistration registration, ModelNode address,
            Resource actual, Resource target, List<ResourceDiff> diffList) {
        diff(registration, address, actual, target, diffList, false);
    }

    private static void diff(ImmutableManagementResourceRegistration registration, ModelNode address,
            Resource actual, Resource target, List<ResourceDiff> diffList, boolean ignoreMissingChildRegistration) {
        diffAttributes(registration, address, actual.getModel(), target.getModel(), diffList);

        final Set<String> targetTypes = new HashSet<String>(target.getChildTypes());
        for(String type : actual.getChildTypes()) {
            if(targetTypes.remove(type)) {
                final Set<String> targetChildren = new HashSet<String>(target.getChildrenNames(type));
                for(String child : actual.getChildrenNames(type)) {
                    final ModelNode childAddress = address.clone();
                    childAddress.add(type, child);
                    final PathElement pe = PathElement.pathElement(type, child);
                    final Resource childRes = actual.getChild(pe);
                    if(!isPersistent(childRes)) {
                        //System.out.println("SKIPPING AS NOT PERSISTENT 1: " + childAddress);
                        continue;
                    }
                    final ImmutableManagementResourceRegistration childReg;
                    if(registration != null) {
                        childReg = registration.getSubModel(PathAddress.pathAddress(type, child));
                        if(childReg == null) {
                            if(!ignoreMissingChildRegistration) {
                                childRegistrationIsMissing(childAddress);
                            }
                        } else if(!isPersistent(childReg)) {
                            continue;
                        }
                    } else {
                        childReg = null;
                    }

                    if(targetChildren.remove(child)) {
                        diff(childReg, childAddress, childRes, target.getChild(pe), diffList, ignoreMissingChildRegistration);
                    } else {
                        diffRemoveResource(childReg, childAddress, childRes, diffList, ignoreMissingChildRegistration);
                    }
                }

                if(!targetChildren.isEmpty()) {
                    // add
                    for(String child : targetChildren) {
                        final ModelNode childAddress = address.clone();
                        childAddress.add(type, child);
                        if(registration != null) {
                            final ImmutableManagementResourceRegistration childReg = registration.getSubModel(
                                    PathAddress.pathAddress(type, child));
                            if(childReg == null) {
                                if(ignoreMissingChildRegistration) {
                                    diffAddResource(null, childAddress,
                                            target.getChild(PathElement.pathElement(type, child)), diffList);
                                } else {
                                    childRegistrationIsMissing(childAddress);
                                }
                            } else if(isPersistent(childReg)) {
                                diffAddResource(childReg, childAddress,
                                        target.getChild(PathElement.pathElement(type, child)), diffList, ignoreMissingChildRegistration);
                            }
                        } else {
                            diffAddResource(null, childAddress,
                                    target.getChild(PathElement.pathElement(type, child)), diffList);
                        }
                    }
                }
            } else {
                // remove
                for(Resource.ResourceEntry child : actual.getChildren(type)) {
                    final ModelNode childAddress = address.clone();
                    childAddress.add(type, child.getName());
                    if(!isPersistent(child)) {
                        //System.out.println("SKIPPING AS NOT PERSISTENT 2: " + childAddress);
                        continue;
                    }
                    if(registration != null) {
                        final ImmutableManagementResourceRegistration childReg = registration.getSubModel(
                                PathAddress.pathAddress(type, child.getName()));
                        if(childReg == null) {
                            if(ignoreMissingChildRegistration) {
                                diffRemoveResource(null, childAddress, child, diffList);
                            } else {
                                childRegistrationIsMissing(childAddress);
                            }
                        } else if(isPersistent(childReg)) {
                            diffRemoveResource(childReg, childAddress, child, diffList, ignoreMissingChildRegistration);
                        }
                    } else {
                        diffRemoveResource(null, childAddress, child, diffList);
                    }
                }
            }
        }

        if(!targetTypes.isEmpty()) {
            // add
            for (String type : targetTypes) {
                for(Resource.ResourceEntry child : target.getChildren(type)) {
                    final ModelNode childAddress = address.clone();
                    childAddress.add(type, child.getName());
                    if(registration != null) {
                        ImmutableManagementResourceRegistration childReg = registration.getSubModel(
                                PathAddress.pathAddress(type, child.getName()));
                        if(childReg == null) {
                            if(ignoreMissingChildRegistration) {
                                diffAddResource(null, childAddress, child, diffList);
                            } else {
                                childRegistrationIsMissing(childAddress);
                            }
                        } else if(isPersistent(childReg)) {
                            diffAddResource(childReg, childAddress, child, diffList, ignoreMissingChildRegistration);
                        }
                    } else {
                        diffAddResource(null, childAddress, child, diffList);
                    }
                }
            }
        }
    }

    private static void diffAddResource(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource target, List<ResourceDiff> diffList) {
        diffAddResource(registration, address, target, diffList, false);
    }

    private static void diffAddResource(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource target, List<ResourceDiff> diffList, boolean ignoreMissingChildRegistration) {

        if (registration == null || registration.getOperationEntry(PathAddress.EMPTY_ADDRESS, ModelDescriptionConstants.ADD) != null) {
            final AddResourceDiff addDiff = ResourceDiff.Factory.add(address);
            diffList.add(addDiff);
            if (target.getModel().isDefined()) {
                final ModelNode model = target.getModel();
                for (String name : model.keys()) {
                    if (isConfigAttribute(registration, name, address) && model.get(name).isDefined()) {
                        addDiff.addDiff(name, model.get(name));
                    }
                }
            }
        } else {
            // should the children still be processed?
            return;
        }

        for(String type : target.getChildTypes()) {
            for(Resource.ResourceEntry child : target.getChildren(type)) {
                final ModelNode childAddress = address.clone();
                childAddress.add(type, child.getName());
                if(registration != null) {
                    final ImmutableManagementResourceRegistration childReg = registration.getSubModel(
                            PathAddress.pathAddress(type, child.getName()));
                    if(childReg == null) {
                        if(ignoreMissingChildRegistration) {
                            diffAddResource(null, childAddress, child, diffList);
                        } else {
                            childRegistrationIsMissing(childAddress);
                        }
                    } else if(isPersistent(childReg)) {
                        diffAddResource(childReg, childAddress, child, diffList, ignoreMissingChildRegistration);
                    }
                } else {
                    diffAddResource(null, childAddress, child, diffList);
                }
            }
        }
    }

    private static void diffAddResource(ImmutableManagementResourceRegistration registration,
            ModelNode address, File targetDir, List<ResourceDiff> diffList, boolean ignoreMissingChildRegistration) throws IOException {

        if (registration == null || registration.getOperationEntry(PathAddress.EMPTY_ADDRESS, ModelDescriptionConstants.ADD) != null) {
            final AddResourceDiff addDiff = ResourceDiff.Factory.add(address);
            diffList.add(addDiff);
            final ModelNode targetModel = readResourceFile(targetDir);
            if (targetModel.isDefined()) {
                for (String name : targetModel.keys()) {
                    if (isConfigAttribute(registration, name, address) && targetModel.get(name).isDefined()) {
                        addDiff.addDiff(name, targetModel.get(name));
                    }
                }
            }
        } else {
            // should the children still be processed?
            return;
        }

        for(File typeDir : listOrderedDirs(targetDir)) {
            final String targetType = decodeDirName(typeDir.getName());
            for(File childDir : listOrderedDirs(typeDir)) {
                final String targetChild = decodeDirName(childDir.getName());
                final ModelNode childAddress = address.clone();
                childAddress.add(targetType, targetChild);
                if(registration != null) {
                    final ImmutableManagementResourceRegistration childReg = registration.getSubModel(
                            PathAddress.pathAddress(targetType, targetChild));
                    if(childReg == null) {
                        if(ignoreMissingChildRegistration) {
                            diffAddResource(null, childAddress, childDir, diffList, ignoreMissingChildRegistration);
                        } else {
                            childRegistrationIsMissing(childAddress);
                        }
                    } else if(isPersistent(childReg)) {
                        diffAddResource(childReg, childAddress, childDir, diffList, ignoreMissingChildRegistration);
                    }
                } else {
                    diffAddResource(null, childAddress, childDir, diffList, ignoreMissingChildRegistration);
                }
            }
        }
    }

    private static void diffRemoveResource(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource actual, List<ResourceDiff> diffList) {
        diffRemoveResource(registration, address, actual, diffList, false);
    }

    private static void diffRemoveResource(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource actual, List<ResourceDiff> diffList, boolean ignoreMissingChildRegistration) {

        // remove descendants first
        for(String type : actual.getChildTypes()) {
            for(String child : actual.getChildrenNames(type)) {
                final ModelNode childAddress = address.clone();
                childAddress.add(type, child);
                if(registration != null) {
                    final ImmutableManagementResourceRegistration childReg = registration.getSubModel(
                            PathAddress.pathAddress(type, child));
                    if(childReg == null) {
                        if(ignoreMissingChildRegistration) {
                            diffRemoveResource(null, childAddress, actual.getChild(PathElement.pathElement(type, child)), diffList);
                        } else {
                            childRegistrationIsMissing(childAddress);
                        }
                    } else if (isPersistent(childReg)) {
                        diffRemoveResource(childReg, childAddress,
                                actual.getChild(PathElement.pathElement(type, child)), diffList, ignoreMissingChildRegistration);
                    }
                } else {
                    diffRemoveResource(null, childAddress, actual.getChild(PathElement.pathElement(type, child)), diffList);
                }
            }
        }
        diffList.add(ResourceDiff.Factory.remove(address));
    }

    private static void diffAttributes(ImmutableManagementResourceRegistration registration,
            ModelNode address, ModelNode actual, ModelNode target, List<ResourceDiff> diffList) {
        if(actual.equals(target)) {
            return;
        }
        // for now I assume the model nodes should be consistent with regards to the keys
        // otherwise, it's not clear to me what the inconsistency would mean and
        // how to handle it
/* TODO review this        if(!actual.isDefined()) {
            throw new IllegalStateException("Actual model is undefined at " + address);
        }
*//* TODO this fell on core-service=management/access=audit/logger=audit-log/handler=file
 * the actual was {} and the target undefined
        if(!target.isDefined()) {
            throw new IllegalStateException("Target model is undefined at " + address + " while the actual is " + actual);
        }
*/
        if(registration != null) {
            final WriteAttributeDiff diff = ResourceDiff.Factory.writeAttribute(address);
            for(String name : registration.getAttributeNames(PathAddress.EMPTY_ADDRESS)) {
                if(isConfigAttribute(registration, name, address)) {
                    final ModelNode actualValue;
                    if(!actual.has(name)) {
                        actualValue = new ModelNode();
                        //throw new IllegalStateException("Configuration attribute " + name + " is not present in the actual model");
                    } else {
                        actualValue = actual.get(name);
                    }
                    ModelNode targetValue;
                    if(!target.has(name)) {
                        targetValue = new ModelNode();
                        //throw new IllegalStateException("Configuration attribute " + name + " is not present in the target model");
                    } else {
                        targetValue = target.get(name);
                    }
                    //final ModelNode targetValue = target.get(name);
                    if(!targetValue.equals(actualValue)) {
                        diff.addDiff(name, targetValue);
                    }
                }
            }
            if(!diff.getAttributeNames().isEmpty()) {
                diffList.add(diff);
            }
            return;
        }

        if(!actual.keys().equals(target.keys())) {
            throw new IllegalStateException(
                    "Actual attribute set " + actual.keys() +
                    " is inconsistent with the target one " + target.keys());
        }
        final WriteAttributeDiff diff = ResourceDiff.Factory.writeAttribute(address);
        for(String name : actual.keys()) {
            final ModelNode targetValue = target.get(name);
            if(!targetValue.equals(actual.get(name))) {
                diff.addDiff(name, targetValue);
            }
        }
        diffList.add(diff);
    }

    // TODO
    private static void diff(ImmutableManagementResourceRegistration registration, ModelNode address,
            Resource actual, File targetDir, List<ResourceDiff> diffList, boolean ignoreMissingChildRegistration) throws IOException {
        diff(registration, address, actual, targetDir, diffList, ignoreMissingChildRegistration, Collections.<String>emptySet());
    }

    private static void diff(ImmutableManagementResourceRegistration registration, ModelNode address,
                Resource actual, File targetDir, List<ResourceDiff> diffList,
                boolean ignoreMissingChildRegistration, Set<String> skipTypes) throws IOException {

        diffAttributes(registration, address, actual.getModel(), readResourceFile(targetDir), diffList);

        final Set<String> actualTypes = new HashSet<String>(actual.getChildTypes());
        if(!skipTypes.isEmpty()) {
            actualTypes.removeAll(skipTypes);
        }
        for(File typeDir : listOrderedDirs(targetDir)) {
            final String targetType = decodeDirName(typeDir.getName());
            if(skipTypes.contains(targetType)) {
                continue;
            }
            if(actualTypes.remove(targetType)) {
                final Set<String> actualChildren = new HashSet<String>(actual.getChildrenNames(targetType));
                for(File childDir : listOrderedDirs(typeDir)) {
                    final String targetChild = decodeDirName(childDir.getName());
                    final ModelNode childAddress = address.clone();
                    childAddress.add(targetType, targetChild);

                    final ImmutableManagementResourceRegistration childReg;
                    if(registration != null) {
                        childReg = registration.getSubModel(PathAddress.pathAddress(targetType, targetChild));
                        if(childReg == null) {
                            if(!ignoreMissingChildRegistration) {
                                childRegistrationIsMissing(childAddress);
                            }
                        } else if(!isPersistent(childReg)) {
                            continue;
                        }
                    } else {
                        childReg = null;
                    }

                    if(actualChildren.remove(targetChild)) {
                        final PathElement pe = PathElement.pathElement(targetType, targetChild);
                        final Resource childRes = actual.getChild(pe);
                        if(childRes == null) {
                            throw new IllegalStateException("Child resource is null");
                        }
                        if(!isPersistent(childRes)) {
                            //System.out.println("SKIPPING AS NOT PERSISTENT 1: " + childAddress);
                            continue;
                        }
                        diff(childReg, childAddress, childRes, childDir, diffList, ignoreMissingChildRegistration);
                    } else {
                        diffAddResource(childReg, childAddress, childDir, diffList, ignoreMissingChildRegistration);
                    }
                }

                if(!actualChildren.isEmpty()) {
                    // remove
                    for(String childName : actualChildren) {
                        final Resource child = actual.getChild(PathElement.pathElement(targetType, childName));
                        if(!isPersistent(child)) {
                            continue;
                        }
                        final ModelNode childAddress = address.clone();
                        childAddress.add(targetType, childName);
                        if(registration != null) {
                            final ImmutableManagementResourceRegistration childReg = registration.getSubModel(
                                    PathAddress.pathAddress(targetType, childName));
                            if(childReg == null) {
                                if(ignoreMissingChildRegistration) {
                                    diffRemoveResource(null, childAddress, child, diffList);
                                } else {
                                    childRegistrationIsMissing(childAddress);
                                }
                            } else if(isPersistent(childReg)) {
                                diffRemoveResource(childReg, childAddress, child, diffList, ignoreMissingChildRegistration);
                            }
                        } else {
                            // TODO SKIPPING access authorization
                            if(!(targetType.equals("access") && childName.equals("authorization"))) {
                                diffRemoveResource(null, childAddress, child, diffList);
                            }
                        }
                    }
                }
            } else {
                // add
                for(File childDir : listOrderedDirs(typeDir)) {
                    final String targetChild = decodeDirName(childDir.getName());
                    final ModelNode childAddress = address.clone();
                    childAddress.add(targetType, targetChild);

                    final ImmutableManagementResourceRegistration childReg;
                    if(registration != null) {
                        childReg = registration.getSubModel(PathAddress.pathAddress(targetType, targetChild));
                        if(childReg == null) {
                            if(!ignoreMissingChildRegistration) {
                                childRegistrationIsMissing(childAddress);
                            }
                        } else if(!isPersistent(childReg)) {
                            continue;
                        }
                    } else {
                        childReg = null;
                    }
                    diffAddResource(childReg, childAddress, childDir, diffList, ignoreMissingChildRegistration);
                }
            }
        }

        if(!actualTypes.isEmpty()) {
            // remove
            for (String type : actualTypes) {
                for (String childName : actual.getChildrenNames(type)) {
                    final Resource child = actual.getChild(PathElement.pathElement(type, childName));
                    if(!isPersistent(child)) {
                        continue;
                    }
                    final ModelNode childAddress = address.clone();
                    childAddress.add(type, childName);
                    if (registration != null) {
                        final ImmutableManagementResourceRegistration childReg = registration.getSubModel(PathAddress
                                .pathAddress(type, childName));
                        if (childReg == null) {
                            if (ignoreMissingChildRegistration) {
                                diffRemoveResource(null, childAddress, child, diffList);
                            } else {
                                childRegistrationIsMissing(childAddress);
                            }
                        } else if (isPersistent(childReg)) {
                            diffRemoveResource(childReg, childAddress, child, diffList, ignoreMissingChildRegistration);
                        }
                    } else {
                        diffRemoveResource(null, childAddress, child, diffList);
                    }
                }
            }
        }
    }

    static List<File> listOrderedDirs(File targetDir) throws IOException {
        final List<File> ordered;
        final File orderFile = new File(targetDir, ORDER_FILE_NAME);
        if(orderFile.exists()) {
            final String[] nameArr = targetDir.list();
            final Set<String> fileSet = nameArr == null ? Collections.<String>emptySet() : new HashSet<String>(Arrays.asList(nameArr));
            ordered = new ArrayList<File>(fileSet.size());
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(orderFile));
                String name = reader.readLine();
                while(name != null) {
                    name = name.trim();
                    if(!name.isEmpty() && name.charAt(0) != '#') {
                        final File childDir = new File(targetDir, name);
                        if (!childDir.isDirectory()) {
                            throw new IllegalStateException("'" + name + "' from " + ORDER_FILE_NAME + " is not a directory.");
                        }
                        if (fileSet.remove(name)) {
                            ordered.add(childDir);
                        } else {
                            throw new IllegalStateException("Directory from " + ORDER_FILE_NAME + " does not exist "
                                    + childDir.getAbsolutePath());
                        }
                    }
                    name = reader.readLine();
                }
            } finally {
                if(reader != null) {
                    reader.close();
                }
            }
            if(!fileSet.isEmpty()) {
                for(String name : fileSet) {
                    final File f = new File(targetDir, name);
                    if(f.isDirectory()) {
                        ordered.add(f);
                    }
                }
            }
        } else {
            final File[] fileList = targetDir.listFiles(new FileFilter(){
                @Override
                public boolean accept(File pathname) {
                    return pathname.isDirectory();
                }});
            ordered = fileList == null ? Collections.<File>emptyList() : Arrays.asList(fileList);
        }
        return ordered;
    }

    static boolean isPersistent(Resource resource, final ImmutableManagementResourceRegistration registration) {
        return isPersistent(resource) && (registration == null || isPersistent(registration));
    }

    static boolean isPersistent(Resource res) {
        return !res.isProxy() && !res.isRuntime();
    }

    static boolean isPersistent(final ImmutableManagementResourceRegistration registration) {
        return !registration.isAlias() && !registration.isRemote() && !registration.isRuntimeOnly();
    }

    private static boolean isConfigAttribute(ImmutableManagementResourceRegistration registration, String name, Object location) {
        if(registration == null) {
            return true;
        }
        final AttributeAccess access = registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, name);
        if(access == null) {
            // TODO
            //throw new IllegalStateException("Attribute access is not specified for " + name + " at " + location);
            return false;
        }
        return /*access.getAccessType().isWritable() &&*/ access.getStorageType() == AttributeAccess.Storage.CONFIGURATION;
    }

    static void childRegistrationIsMissing(final ModelNode childAddress) {
        throw new IllegalStateException("Registration is missing for child resource " + childAddress);
    }

    private static String encodeDirName(String name) {

        for(int i = 0; i < name.length(); ++i) {
            char ch = name.charAt(i);
            if (ch < ' ' || ch >= 0x7F || ch == File.separatorChar
                    || (ch == '.' && i == 0) // we don't want to collide with "." or ".."!
                    || ch == ESCAPE_CHAR) {

                final StringBuilder builder = new StringBuilder();
                if(i > 0) {
                    builder.append(name, 0, i);
                }
                builder.append(ESCAPE_CHAR);
                if (ch < 0x10) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(ch));

                for (int j = i + 1; j < name.length(); j++) {
                    ch = name.charAt(j);
                    if (ch < ' ' || ch >= 0x7F || ch == File.separatorChar
                            || (ch == '.' && j == 0) // we don't want to collide with "." or ".."!
                            || ch == ESCAPE_CHAR) {
                        builder.append(ESCAPE_CHAR);
                        if (ch < 0x10) {
                            builder.append('0');
                        }
                        builder.append(Integer.toHexString(ch));
                    } else {
                        builder.append(ch);
                    }
                }
                //System.out.println("encoded '" + name + "' to '" + builder.toString() + "'");
                return builder.toString();
            }
        }
        return name;
    }

    private static String decodeDirName(String name) {
        for(int i = 0; i < name.length(); ++i) {
            if(name.charAt(i) == ESCAPE_CHAR) {
                char ch = name.charAt(i);
                final StringBuilder builder = new StringBuilder(name.length());
                if(i > 0) {
                    builder.append(name, 0, i);
                }
                int j = i + 1;
                if(j < name.length()) {
                    if(j + 1 >= name.length()) {
                        throw new IllegalStateException("Unexpected escpaing sequence '" + name + "' at " + j);
                    }
                    builder.append((char)Integer.decode("0x" + name.charAt(j++) + name.charAt(j++)).intValue());
                }
                while(j < name.length()) {
                    ch = name.charAt(j++);
                    if(ch == ESCAPE_CHAR) {
                        if(j + 1 >= name.length()) {
                            throw new IllegalStateException("Unexpected escpaing sequence '" + name + "' at " + j);
                        }
                        builder.append((char)Integer.decode("0x" + name.charAt(j++) + name.charAt(j++)).intValue());
                    } else {
                        builder.append(ch);
                    }
                }
                //System.out.println("decoded '" + name + "' to '" + builder.toString() + "'");
                return builder.toString();
            }
        }
        return name;
    }
}
