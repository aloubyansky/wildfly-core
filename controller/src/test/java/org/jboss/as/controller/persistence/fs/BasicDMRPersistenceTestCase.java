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
import java.io.IOException;

import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author Alexey Loubyansky
 */
public class BasicDMRPersistenceTestCase {

    private static final String ROOT_DIR_NAME = "test-wf-fs-config-root";

    private File rootDir;

    @Before
    public void init() throws Exception {
        rootDir = new File(new File(System.getProperty("java.io.tmpdir")), ROOT_DIR_NAME);
        if(rootDir.exists()) {
            if(!FSPersistence.rmrf(rootDir)) {
                Assert.fail("Failed to delete the already existing root dir " + rootDir.getAbsolutePath());
            }
        }
        if(!rootDir.mkdirs()) {
            Assert.fail("Failed to create the root dir " + rootDir.getAbsolutePath());
        }
    }

    @After
    public void cleanup() throws Exception {
        if(!FSPersistence.rmrf(rootDir)) {
            Assert.fail("Failed to delete the already existing root dir " + rootDir.getAbsolutePath());
        }
    }

    @Test
    public void testResourceWithoutChildren() throws Exception {

        final Resource root = Resource.Factory.create();
        persist(root);
        assertPersisted(root);
        assertRead(root);

        root.getModel().get("attr-str").set("value-str");
        persist(root);
        assertPersisted(root);
        assertRead(root);

        root.getModel().get("attr-int").set(1);
        persist(root);
        assertPersisted(root);
        assertRead(root);

        root.getModel().get("attr-int").set(2);
        root.getModel().get("attr-bool").set(true);
        persist(root);
        assertPersisted(root);
        assertRead(root);

        ModelNode o = new ModelNode();
        o.get("one").set(1);
        o.get("true").set(true);
        root.getModel().get("attr-obj").set(o);
        persist(root);
        assertPersisted(root);
        assertRead(root);

        root.getModel().clear();
        persist(root);
        assertPersisted(root);
        assertRead(root);
    }

    @Test
    public void testResourceWithChildrenNoAttributes() throws Exception {

        final Resource root = Resource.Factory.create();
        persist(root);
        assertPersisted(root);
        assertRead(root);

        final Resource a1 = Resource.Factory.create();
        root.registerChild(PathElement.pathElement("a", "1"), a1);
        persist(root);
        assertPersisted(root);
        assertRead(root);

        final Resource a2 = Resource.Factory.create();
        root.registerChild(PathElement.pathElement("a", "2"), a2);
        persist(root);
        assertPersisted(root);
        assertRead(root);

        final Resource b1 = Resource.Factory.create();
        root.registerChild(PathElement.pathElement("b", "1"), b1);
        persist(root);
        assertPersisted(root);
        assertRead(root);

        final Resource c1 = Resource.Factory.create();
        b1.registerChild(PathElement.pathElement("c", "1"), c1);
        persist(root);
        assertPersisted(root);
        assertRead(root);

        final Resource c2 = Resource.Factory.create();
        b1.registerChild(PathElement.pathElement("c", "2"), c2);
        persist(root);
        assertPersisted(root);
        assertRead(root);
    }

    @Test
    public void testRemovingChildren() throws Exception {

        final Resource root = Resource.Factory.create();
        root.getModel().get("a").add("1");
        root.getModel().get("a").add("2");
        root.getModel().get("b").add("1");

        final Resource a1 = Resource.Factory.create();
        root.registerChild(PathElement.pathElement("a", "1"), a1);

        final Resource a2 = Resource.Factory.create();
        root.registerChild(PathElement.pathElement("a", "2"), a2);

        final Resource b1 = Resource.Factory.create();
        root.registerChild(PathElement.pathElement("b", "1"), b1);
        b1.getModel().get("c").add("1");
        b1.getModel().get("c").add("2");

        final Resource c1 = Resource.Factory.create();
        b1.registerChild(PathElement.pathElement("c", "1"), c1);

        final Resource c2 = Resource.Factory.create();
        b1.registerChild(PathElement.pathElement("c", "2"), c2);

        persist(root);
        assertPersisted(root);
        assertRead(root);

        // removing single child
        b1.removeChild(PathElement.pathElement("c", "2"));
        b1.getModel().get("c").setEmptyList();
        b1.getModel().get("c").add("1");
        persist(root);
        assertPersisted(root);
        assertRead(root);

        // removing a branch
        root.removeChild(PathElement.pathElement("b", "1"));
        root.getModel().remove("b");
        persist(root);
        assertPersisted(root);
        assertRead(root);

        root.removeChild(PathElement.pathElement("a", "2"));
        root.removeChild(PathElement.pathElement("a", "1"));
        root.getModel().clear();
        persist(root);
        assertPersisted(root);
        assertRead(root);
    }

    /**
     * Same as persist(Resource,File) but with the implicit default directory.
     *
     * @param res
     * @throws IOException
     */
    private void persist(Resource res) throws IOException {
        FSPersistence.persist(null, res, rootDir);
    }

    /**
     * Same as assertRead(Resource,File) with the implicit default directory.
     *
     * @param original
     */
    private void assertRead(Resource original) {
        AssertFSPersistence.assertRead(original, rootDir);
    }


    /**
     * Same as assertPersisted(Resource,File) with the implicit default directory.
     *
     * @param resource
     */
    private void assertPersisted(Resource resource) {
        AssertFSPersistence.assertPersisted(resource, rootDir);
    }
}
