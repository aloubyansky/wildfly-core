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

import java.io.File;
import java.io.FileInputStream;
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

        if(!isPersistent(res, registration)) {
            return;
        }

        final Set<String> resourceTypes = res.getChildTypes();
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
                            return !name.equals(RESOURCE_FILE_NAME);
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
                        if(registration == null) {
                            childReg = null;
                        } else {
                            childReg = registration.getSubModel(
                                PathAddress.pathAddress(PathElement.pathElement(resType, child.getName())));
                            if (childReg == null) {
                                throw new IllegalStateException("Child not registered: type="
                                        + resType + ", name="
                                        + child.getName());
                            }
                            if(!isPersistent(childReg)) {
                                continue;
                            }
                        }
                        fsChildrenToRemove.remove(child.getName());
                        persist(childReg, child, new File(fsType, child.getName()), removeNotOverriden);
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
                if(isConfigAttribute(registration, attr)) {
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
                if(!RESOURCE_FILE_NAME.equals(type.getName())) {
                    // this is strict wrt to the content but it can always be relaxed later
                    throw new IllegalStateException("Unexpected file: " + type.getAbsolutePath());
                }
            } else {

                final File[] children = type.listFiles();
                for(File child : children) {
                    if(child.isDirectory()) {
                        resource.registerChild(
                                PathElement.pathElement(type.getName(), child.getName()),
                                readResource(child));
                    } else {
                        // this is strict wrt to the content but it can always be relaxed later
                        throw new IllegalStateException("Unexpected file: " + type.getAbsolutePath());
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

    public static List<ResourceDiff> diff(ModelNode address, Resource actual, File dir)
            throws IOException {
        return diff(null, address, actual, dir);
    }

    public static List<ResourceDiff> diff(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource actual, File dir) throws IOException {
        return diff(registration, address, actual, dir, false);
    }

    public static List<ResourceDiff> diff(ImmutableManagementResourceRegistration registration,
            ModelNode address, Resource actual, File dir, boolean ignoreMissingChildRegistration) throws IOException {
        final ArrayList<ResourceDiff> diff = new ArrayList<ResourceDiff>();
        diff(registration, address, actual, readResource(dir), diff, ignoreMissingChildRegistration);
        return diff;
    }

    public static List<ResourceDiff> describe(ImmutableManagementResourceRegistration registration,
            ModelNode address, String type, File dir) throws IOException {
        final ArrayList<ResourceDiff> diffList = new ArrayList<ResourceDiff>();
        final Resource resource = readResource(dir);
        for (Resource.ResourceEntry child : resource.getChildren(type)) {
            final ModelNode childAddress = address.clone();
            childAddress.add(type, child.getName());
            if (registration != null) {
                final ImmutableManagementResourceRegistration childReg = registration.getSubModel(
                        PathAddress.pathAddress(type, child.getName()));
                if (childReg == null) {
                    childRegistrationIsMissing(childAddress);
                }
                if (isPersistent(childReg)) {
                    diffAddResource(childReg, childAddress, child, diffList);
                }
            } else {
                diffAddResource(null, childAddress, child, diffList);
            }
        }
        return diffList;
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
                    if(!FSPersistence.isPersistent(childRes)) {
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
                    if(!FSPersistence.isPersistent(child)) {
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
                    if (isConfigAttribute(registration, name) && model.get(name).isDefined()) {
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
                if(isConfigAttribute(registration, name)) {
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

    static boolean isPersistent(Resource resource, final ImmutableManagementResourceRegistration registration) {
        return isPersistent(resource) && (registration == null || isPersistent(registration));
    }

    static boolean isPersistent(Resource res) {
        return !res.isProxy() && !res.isRuntime();
    }

    static boolean isPersistent(final ImmutableManagementResourceRegistration registration) {
        return !registration.isAlias() && !registration.isRemote() && !registration.isRuntimeOnly();
    }

    private static boolean isConfigAttribute(ImmutableManagementResourceRegistration registration, String name) {
        if(registration == null) {
            return true;
        }
        final AttributeAccess access = registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, name);
        if(access == null) {
            throw new IllegalStateException("Attribute access is not specified for " + name);
        }
        return access.getStorageType() == AttributeAccess.Storage.CONFIGURATION;
    }

    static void childRegistrationIsMissing(final ModelNode childAddress) {
        throw new IllegalStateException("Registration is missing for child resource " + childAddress);
    }
}
