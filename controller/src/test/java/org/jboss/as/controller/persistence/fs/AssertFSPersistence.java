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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.registry.Resource;
import org.junit.Assert;

/**
 * Various assert methods for file system management model persistence tests.
 *
 * @author Alexey Loubyansky
 */
public class AssertFSPersistence {

    /**
     * Asserts that the resource stored in the directory matches
     * the resource passed in as the argument.
     *
     * @param original
     * @param dir
     */
    public static void assertRead(Resource original, File dir) {
        try {
            assertResourcesEqual(original, FSPersistence.readResource(dir));
        } catch (IOException e) {
            fail("Failed to read resource from " + dir.getAbsolutePath() + ": " + e);
        }
    }

    /**
     * Asserts the two resources are identical.
     *
     * @param original
     * @param test
     */
    public static void assertResourcesEqual(Resource original, Resource test) {

        if(original == null) {
            if(test == null) {
                fail("test resource is not null");
            }
            return;
        }

        if(test == null) {
            fail("test resource is null");
        }

        if(!original.isModelDefined()) {
            if(test.isModelDefined()) {
                fail("test resource has defined model");
            }
        } else {
            if(!test.isModelDefined()) {
                fail("test resource model is undefined");
            }
            assertEquals(original.getModel(), test.getModel());
        }

        final Set<String> originalTypes = original.getChildTypes();
        assertEquals(originalTypes, test.getChildTypes());

        for(String type : originalTypes) {
            final Set<String> childNames = original.getChildrenNames(type);
            assertEquals(childNames, test.getChildrenNames(type));
            for(String childName : childNames) {
                final PathElement pe = PathElement.pathElement(type, childName);
                assertResourcesEqual(original.getChild(pe), test.getChild(pe));
            }
        }
    }

    /**
     * Asserts that the persistent state on the filesystem
     * has the correct structure and content and matches the resource
     * passed in as the argument.
     *
     * @param resource
     * @param dir
     */
    public static void assertPersisted(Resource resource, File dir) {

        try {
            assertEquals(resource.getModel(), FSPersistence.readResourceFile(dir));
        } catch (IOException e) {
            fail("Failed to read " + new File(dir, FSPersistence.RESOURCE_FILE_NAME));
        }

        final Set<String> types = resource.getChildTypes();
        if(types.isEmpty()) {
            assertNoTypes(dir);
        } else {
            final File[] fsTypes = dir.listFiles();
            if(types.size() + 1 != fsTypes.length) {
                final List<String> expected = new ArrayList<String>(types);
                expected.add(FSPersistence.RESOURCE_FILE_NAME);
                Collections.sort(expected);

                final List<String> actual = new ArrayList<String>(Arrays.asList(dir.list()));
                Collections.sort(actual);
                assertEquals(expected, actual);
            }

            for(File fsType : fsTypes) {
                if(fsType.isFile()) {
                    if(!FSPersistence.RESOURCE_FILE_NAME.equals(fsType.getName())) {
                        fail("Unexpected file in the resource dir " + fsType.getAbsolutePath());
                    }
                    continue;
                }
                if(!types.contains(fsType.getName())) {
                    fail("Set of expected types " + types + " does not include " + fsType.getAbsolutePath());
                }

                final Set<String> childNames = resource.getChildrenNames(fsType.getName());
                final File[] fsChildren = fsType.listFiles();
                assertEquals(childNames.size(), fsChildren.length);
                for(File fsChild : fsChildren) {
                    assertTrue(childNames.contains(fsChild.getName()));
                    assertPersisted(
                            resource.getChild(PathElement.pathElement(fsType.getName(), fsChild.getName())),
                            fsChild);
                }
            }
        }
    }

    /**
     * Asserts that the resource directory doesn't contain any types.
     *
     * @param dir
     */
    public static void assertNoTypes(File dir) {
        if(dir == null) {
            Assert.fail("Argument is null");
        }
        if(!dir.exists()) {
            Assert.fail("The dir does not exist");
        }
        if(dir.isFile()) {
            Assert.fail("The directory is a file");
        }
        List<String> children = null;
        for(File f : dir.listFiles()) {
            if(f.getName().equals(FSPersistence.RESOURCE_FILE_NAME)) {
                if(f.isDirectory()) {
                    Assert.fail("Resource file name is a directory " + f.getAbsolutePath());
                }
            } else {
                if(children == null) {
                    children = new ArrayList<String>();
                }
                children.add(f.getName());
            }
        }
        if(children != null) {
            Assert.fail("Found children " + children + " in " + dir.getAbsolutePath());
        }
    }
}
