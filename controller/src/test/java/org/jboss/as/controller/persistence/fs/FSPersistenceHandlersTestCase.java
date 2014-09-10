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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AbstractRemoveStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.ModelOnlyWriteAttributeHandler;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.operations.global.GlobalNotifications;
import org.jboss.as.controller.operations.global.GlobalOperationHandlers;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.test.AbstractControllerTestBase;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author Alexey Loubyansky
 */
public class FSPersistenceHandlersTestCase extends AbstractControllerTestBase {

    private static final String ROOT_TYPE = "fs-persistence-test";
    private static final String PERSIST = PersistToFSStepHandler.NAME;
    private static final String SYNC = SyncWithFSStepHandler.NAME;

    private static final File DIR;
    static {
        DIR = new File(new File(System.getProperty("java.io.tmpdir")), "ROOT_TYPE");
    }

    @BeforeClass
    public static void prepare() {
        if(DIR.exists()) {
            FSPersistence.rmrf(DIR);
        }
        if(!DIR.mkdirs()) {
            throw new IllegalStateException("Failed to create test dir");
        }
    }

    @AfterClass
    public static void cleanup() {
        FSPersistence.rmrf(DIR);
    }

    @Test
    public void testOnlyModel() throws Exception {

        ModelNode op = Util.createAddOperation(PathAddress.pathAddress(new ModelNode().add(ROOT_TYPE, "root")));
        op.get("one").set("1");
        op.get("two").set("2");
        op.get("ro").set("only read");
        op.get("non-config").set("xxx");
        executeForResult(op);

        assertEquals("1", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("2", readAttribute("two", ROOT_TYPE, "root").asString());
        assertEquals("only read", readAttribute("ro", ROOT_TYPE, "root").asString());
        assertEquals("xxx", readAttribute("non-config", ROOT_TYPE, "root").asString());

        op = createOperation(PERSIST, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        // just a copy of the created resource for comparison
        final Resource root = Resource.Factory.create();
        root.getModel().get("one").set("1");
        root.getModel().get("two").set("2");
        root.getModel().get("ro").set("only read");

        AssertFSPersistence.assertPersisted(root, DIR);

        // this will be an op as there is no difference so far between the stored and runtime states
        op = createOperation(SYNC, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        // modifying the runtime state
        writeAttribute("one", "11", ROOT_TYPE, "root");
        writeAttribute("two", "22", ROOT_TYPE, "root");
        assertEquals("11", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("22", readAttribute("two", ROOT_TYPE, "root").asString());

        // syncing with the persistent state will reset the runtime changes
        op = createOperation(SYNC, ROOT_TYPE, "root");
        executeCheckNoFailure(op);
        assertEquals("1", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("2", readAttribute("two", ROOT_TYPE, "root").asString());

        // modifying the runtime state
        writeAttribute("one", "11", ROOT_TYPE, "root");
        writeAttribute("two", "22", ROOT_TYPE, "root");
        assertEquals("11", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("22", readAttribute("two", ROOT_TYPE, "root").asString());

        // persisting the changes
        op = createOperation(PERSIST, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        root.getModel().get("one").set("11");
        root.getModel().get("two").set("22");
        AssertFSPersistence.assertPersisted(root, DIR);

        // modifying the runtime state
        writeAttribute("one", "1", ROOT_TYPE, "root");
        writeAttribute("two", "2", ROOT_TYPE, "root");
        assertEquals("1", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("2", readAttribute("two", ROOT_TYPE, "root").asString());

        // syncing with the persistent state will reset the not persisted runtime changes
        op = createOperation(SYNC, ROOT_TYPE, "root");
        executeCheckNoFailure(op);
        assertEquals("11", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("22", readAttribute("two", ROOT_TYPE, "root").asString());
    }

    @Test
    public void testAddAllChildren() throws Exception {

        // create the root resource
        ModelNode op = Util.createAddOperation(PathAddress.pathAddress(new ModelNode().add(ROOT_TYPE, "root")));
        executeForResult(op);

        assertEquals("undefined", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("undefined", readAttribute("two", ROOT_TYPE, "root").asString());
        assertEquals("undefined", readAttribute("ro", ROOT_TYPE, "root").asString());

        // persist to the FS
        op = createOperation(PERSIST, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        // just a copy of the created resource for comparison
        final Resource root = Resource.Factory.create();
        root.getModel().get("one");
        root.getModel().get("two");
        root.getModel().get("ro");
        AssertFSPersistence.assertPersisted(root, DIR);

        // create children on FS
        createChildOnFS(DIR, "a", "1");
        final File a2 = createChildOnFS(DIR, "a", "2");

        final File b1 = createChildOnFS(a2, "b", "1");
        final ModelNode b1Model = new ModelNode();
        b1Model.get("one").set("1");
        b1Model.get("two").set("2");
        storeAttributesOnFS(b1, b1Model);

        createChildOnFS(b1, "c", "1");
        createChildOnFS(DIR, "a", "3");

        // assert children don't exist on the FS
        assertTrue(readChildNames("a", ROOT_TYPE, "root").isEmpty());

        // sync runtime with the FS
        op = createOperation(SYNC, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        // assert all the children are synced
        assertEquals(Arrays.asList(new String[]{"1", "2", "3"}), readChildNames("a", ROOT_TYPE, "root"));
        assertEquals(new ModelNode(), readAttribute("one", ROOT_TYPE, "root", "a", "1"));
        assertEquals(new ModelNode(), readAttribute("two", ROOT_TYPE, "root", "a", "1"));
        assertEquals(new ModelNode(), readAttribute("one", ROOT_TYPE, "root", "a", "2"));
        assertEquals(new ModelNode(), readAttribute("two", ROOT_TYPE, "root", "a", "2"));
        assertEquals(new ModelNode(), readAttribute("one", ROOT_TYPE, "root", "a", "3"));
        assertEquals(new ModelNode(), readAttribute("two", ROOT_TYPE, "root", "a", "3"));

        assertEquals(Collections.emptyList(), readChildNames("b", ROOT_TYPE, "root", "a", "1"));
        assertEquals(Collections.emptyList(), readChildNames("b", ROOT_TYPE, "root", "a", "3"));
        assertEquals(Collections.singletonList("1"), readChildNames("b", ROOT_TYPE, "root", "a", "2"));
        assertEquals(new ModelNode("1"), readAttribute("one", ROOT_TYPE, "root", "a", "2", "b", "1"));
        assertEquals(new ModelNode("2"), readAttribute("two", ROOT_TYPE, "root", "a", "2", "b", "1"));

        assertEquals(Collections.singletonList("1"), readChildNames("c", ROOT_TYPE, "root", "a", "2", "b", "1"));
        assertEquals(new ModelNode(), readAttribute("one", ROOT_TYPE, "root", "a", "2", "b", "1", "c", "1"));
        assertEquals(new ModelNode(), readAttribute("two", ROOT_TYPE, "root", "a", "2", "b", "1", "c", "1"));

    }

    @Test
    public void testRemoveAllChildren() throws Exception {

        // create the root resource
        ModelNode op = Util.createAddOperation(PathAddress.pathAddress(new ModelNode().add(ROOT_TYPE, "root")));
        executeForResult(op);

        assertEquals("undefined", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("undefined", readAttribute("two", ROOT_TYPE, "root").asString());
        assertEquals("undefined", readAttribute("ro", ROOT_TYPE, "root").asString());

        // persist to the FS
        op = createOperation(PERSIST, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        // create the runtime resource tree
        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "1")));
        executeForResult(op);
        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "2")));
        executeForResult(op);
        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "3")));
        executeForResult(op);
        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "2")
                               .add("b", "1")));
        executeForResult(op);
        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "2")
                               .add("b", "1")
                               .add("c", "1")));
        executeForResult(op);

        // assert children added
        assertEquals(Arrays.asList(new String[]{"1", "2", "3"}), readChildNames("a", ROOT_TYPE, "root"));
        assertEquals(Arrays.asList(new String[]{"1"}), readChildNames("b", ROOT_TYPE, "root", "a", "2"));
        assertEquals(Arrays.asList(new String[]{"1"}), readChildNames("c", ROOT_TYPE, "root", "a", "2", "b", "1"));

        // just a copy of the created resource for comparison
        final Resource root = Resource.Factory.create();
        root.getModel().get("one");
        root.getModel().get("two");
        root.getModel().get("ro");
        AssertFSPersistence.assertPersisted(root, DIR);

        // sync runtime with the FS
        op = createOperation(SYNC, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        assertEquals(Collections.emptyList(), readChildNames("a", ROOT_TYPE, "root"));
    }

    @Test
    public void testMix() throws Exception {

        // create the root resource
        ModelNode op = Util.createAddOperation(PathAddress.pathAddress(new ModelNode().add(ROOT_TYPE, "root")));
        executeForResult(op);

        assertEquals("undefined", readAttribute("one", ROOT_TYPE, "root").asString());
        assertEquals("undefined", readAttribute("two", ROOT_TYPE, "root").asString());
        assertEquals("undefined", readAttribute("ro", ROOT_TYPE, "root").asString());

        // create the runtime resource tree
        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "1")));
        executeForResult(op);

        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "2")));
        op.get("one").set("1");
        executeForResult(op);

        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "3")));
        op.get("one").set("1");
        op.get("two").set("2");
        executeForResult(op);

        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "x")));
        executeForResult(op);

        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "5")));
        executeForResult(op);

        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "5")
                               .add("b", "1")));
        op.get("one").set("1");
        op.get("two").set("2");
        executeForResult(op);

        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "5")
                               .add("b", "1")
                               .add("c", "1")));
        op.get("one").set("1");
        executeForResult(op);

        op = Util.createAddOperation(PathAddress.pathAddress(
                new ModelNode().add(ROOT_TYPE, "root")
                               .add("a", "5")
                               .add("b", "1")
                               .add("c", "1")
                               .add("d", "1")));
        executeForResult(op);


        // persist to the FS
        op = createOperation(PERSIST, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        // modify the FS structure

        storeAttributeOnFS(getFile(DIR, "a", "3"), "two", "22");
        FSPersistence.rmrf(getFile(DIR, "a", "x"));
        createChildOnFS(DIR, "a", "4");
        storeAttributeOnFS(getFile(DIR, "a", "5", "b", "1"), "one", "11");
        FSPersistence.rmrf(getFile(DIR, "a", "5", "b", "1", "c", "1"));
        createChildOnFS(getFile(DIR, "a", "5", "b", "1"), "c", "2");
        storeAttributeOnFS(getFile(DIR, "a", "5", "b", "1", "c", "2"), "two", "2");
        createChildOnFS(getFile(DIR, "a", "5", "b", "1", "c", "2"), "d", "1");

        // sync runtime with the FS
        op = createOperation(SYNC, ROOT_TYPE, "root");
        executeCheckNoFailure(op);

        // assert the state
        assertEquals(Arrays.asList(new String[]{"1", "2", "3", "4", "5"}), readChildNames("a", ROOT_TYPE, "root"));
        assertFalse(readAttribute("one", ROOT_TYPE, "root", "a", "1").isDefined());
        assertFalse(readAttribute("two", ROOT_TYPE, "root", "a", "1").isDefined());
        assertEquals(Collections.emptyList(), readChildNames("b", ROOT_TYPE, "root", "a", "1"));

        assertEquals("1", readAttribute("one", ROOT_TYPE, "root", "a", "2").asString());
        assertFalse(readAttribute("two", ROOT_TYPE, "root", "a", "2").isDefined());
        assertEquals(Collections.emptyList(), readChildNames("b", ROOT_TYPE, "root", "a", "2"));

        assertEquals("1", readAttribute("one", ROOT_TYPE, "root", "a", "3").asString());
        assertEquals("22", readAttribute("two", ROOT_TYPE, "root", "a", "3").asString());
        assertEquals(Collections.emptyList(), readChildNames("b", ROOT_TYPE, "root", "a", "3"));

        assertFalse(readAttribute("one", ROOT_TYPE, "root", "a", "4").isDefined());
        assertFalse(readAttribute("two", ROOT_TYPE, "root", "a", "4").isDefined());
        assertEquals(Collections.emptyList(), readChildNames("b", ROOT_TYPE, "root", "a", "4"));

        assertFalse(readAttribute("one", ROOT_TYPE, "root", "a", "5").isDefined());
        assertFalse(readAttribute("two", ROOT_TYPE, "root", "a", "5").isDefined());
        assertEquals(Collections.singletonList("1"), readChildNames("b", ROOT_TYPE, "root", "a", "5"));

        assertEquals("11", readAttribute("one", ROOT_TYPE, "root", "a", "5", "b", "1").asString());
        assertEquals("2", readAttribute("two", ROOT_TYPE, "root", "a", "5", "b", "1").asString());
        assertEquals(Collections.singletonList("2"), readChildNames("c", ROOT_TYPE, "root", "a", "5", "b", "1"));

        assertFalse(readAttribute("one", ROOT_TYPE, "root", "a", "5", "b", "1", "c", "2").isDefined());
        assertEquals("2", readAttribute("two", ROOT_TYPE, "root", "a", "5", "b", "1", "c", "2").asString());
        assertEquals(Collections.singletonList("1"), readChildNames("d", ROOT_TYPE, "root", "a", "5", "b", "1", "c", "2"));

        assertFalse(readAttribute("one", ROOT_TYPE, "root", "a", "5", "b", "1", "c", "2", "d", "1").isDefined());
        assertFalse(readAttribute("two", ROOT_TYPE, "root", "a", "5", "b", "1", "c", "2", "d", "1").isDefined());
    }

    protected File getFile(File base, String... dirNames) {
        if(dirNames.length == 0) {
            return base;
        }
        File f = base;
        for(String name : dirNames) {
            f = new File(f, name);
        }
        return f;
    }
    protected File createChildOnFS(File parent, String type, String name) {
        File dir = new File(parent, type);
        if(!dir.exists()) {
            if(!dir.mkdir()) {
                Assert.fail("Failed to create type dir " + dir.getAbsolutePath());
            }
        }
        dir = new File(dir, name);
        if(dir.exists()) {
            Assert.fail("Child dir already exists");
        }
        if(!dir.mkdir()) {
            Assert.fail("Failed to create child dir " + dir.getAbsolutePath());
        }
        return dir;
    }

    protected void storeAttributeOnFS(File dir, String name, String value) throws IOException {
        final ModelNode model = FSPersistence.readResourceFile(dir);
        model.get(name).set(value);
        storeAttributesOnFS(dir, model);
    }

    protected void storeAttributesOnFS(File dir, ModelNode model) throws IOException {
        FSPersistence.writeResourceFile(dir, model);
    }

    protected ModelNode readAttribute(String name, String... address) throws OperationFailedException {
        final ModelNode op = createOperation(ModelDescriptionConstants.READ_ATTRIBUTE_OPERATION, address);
        op.get(ModelDescriptionConstants.NAME).set(name);
        return executeForResult(op);
    }

    protected ModelNode writeAttribute(String name, String value, String... address) throws OperationFailedException {
        final ModelNode op = createOperation(ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION, address);
        op.get(ModelDescriptionConstants.NAME).set(name);
        op.get(ModelDescriptionConstants.VALUE).set(value);
        return executeCheckNoFailure(op);
    }

    protected List<String> readChildNames(String type, String... address) throws OperationFailedException {
        final ModelNode op = createOperation(ModelDescriptionConstants.READ_CHILDREN_NAMES_OPERATION, address);
        op.get(ModelDescriptionConstants.CHILD_TYPE).set(type);
        return toStringList(executeForResult(op).asList());
    }

    protected List<String> toStringList(List<ModelNode> nodes) {
        switch(nodes.size()) {
            case 0:
                return Collections.emptyList();
            case 1:
                return Collections.singletonList(nodes.get(0).asString());
            default:
                final List<String> list = new ArrayList<String>(nodes.size());
                for(ModelNode node : nodes) {
                    list.add(node.asString());
                }
                return list;
        }
    }

    @Override
    protected void initModel(ManagementModel managementModel) {

        final ManagementResourceRegistration registration = managementModel.getRootResourceRegistration();
        //registration.registerOperationHandler(ReadAttributeHandler.DEFINITION, ReadAttributeHandler.INSTANCE, true);
        //registration.registerOperationHandler(WriteAttributeHandler.DEFINITION, WriteAttributeHandler.INSTANCE, true);

        GlobalNotifications.registerGlobalNotifications(registration, processType);
        GlobalOperationHandlers.registerGlobalOperations(registration, processType);

        registration.registerSubModel(new TestResource());
    }

    private static AttributeDefinition ATTRIBUTE_ONE = new SimpleAttributeDefinitionBuilder("one", ModelType.STRING)
        .setAllowNull(true)
        .addFlag(AttributeAccess.Flag.STORAGE_CONFIGURATION)
        .build();
    private static AttributeDefinition ATTRIBUTE_TWO = new SimpleAttributeDefinitionBuilder("two", ModelType.STRING)
        .setAllowNull(true)
        .addFlag(AttributeAccess.Flag.STORAGE_CONFIGURATION)
        .build();
    private static AttributeDefinition ATTRIBUTE_RO = new SimpleAttributeDefinitionBuilder("ro", ModelType.STRING)
        .setAllowNull(true)
        .addFlag(AttributeAccess.Flag.STORAGE_CONFIGURATION)
        .build();
    private static AttributeDefinition ATTRIBUTE_RT = new SimpleAttributeDefinitionBuilder("rt", ModelType.STRING)
        .setAllowNull(true)
        .addFlag(AttributeAccess.Flag.STORAGE_RUNTIME)
        .build();
    private static AttributeDefinition ATTRIBUTE_NON_CONFIG = new SimpleAttributeDefinitionBuilder("non-config", ModelType.STRING)
        .setAllowNull(true)
        .addFlag(AttributeAccess.Flag.STORAGE_RUNTIME)
        .build();

    private static class TestResource extends SimpleResourceDefinition {

        public TestResource() {
            this(ROOT_TYPE);
        }

        public TestResource(String type) {
            super(PathElement.pathElement(type), new NonResolvingResourceDescriptionResolver(), new TestResourceAddHandler(), new AbstractRemoveStepHandler() {});
        }

        @Override
        public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
            super.registerAttributes(resourceRegistration);
            resourceRegistration.registerReadWriteAttribute(ATTRIBUTE_ONE, null, new ModelOnlyWriteAttributeHandler());
            resourceRegistration.registerReadWriteAttribute(ATTRIBUTE_TWO, null, new ModelOnlyWriteAttributeHandler());
            resourceRegistration.registerReadOnlyAttribute(ATTRIBUTE_RO, null);
            resourceRegistration.registerReadWriteAttribute(ATTRIBUTE_RT, null, new ModelOnlyWriteAttributeHandler());
            resourceRegistration.registerReadWriteAttribute(ATTRIBUTE_NON_CONFIG, null, new ModelOnlyWriteAttributeHandler());
        }

        @Override
        public void registerOperations(ManagementResourceRegistration registration) {
            super.registerOperations(registration);
            registration.registerOperationHandler(
                    new SimpleOperationDefinitionBuilder(PERSIST,
                            new NonResolvingResourceDescriptionResolver()).build(),
                    new PersistToFSStepHandler(DIR));
            registration.registerOperationHandler(
                    new SimpleOperationDefinitionBuilder(SYNC,
                            new NonResolvingResourceDescriptionResolver()).build(),
                    new SyncWithFSStepHandler(DIR));
        }

        @Override
        public void registerChildren(ManagementResourceRegistration registration) {
            super.registerChildren(registration);
            final String type = this.getPathElement().getKey();
            if(type.equals(ROOT_TYPE)) {
                registration.registerSubModel(new TestResource("a"));
            } else if(type.equals("a")) {
                registration.registerSubModel(new TestResource("b"));
            } else if(type.equals("b")) {
                registration.registerSubModel(new TestResource("c"));
            } else if(type.equals("c")) {
                registration.registerSubModel(new TestResource("d"));
            }
        }
    }

    private static class TestResourceAddHandler extends AbstractAddStepHandler {

        @Override
        protected void populateModel(ModelNode operation, ModelNode model) throws OperationFailedException {
            ATTRIBUTE_ONE.validateAndSet(operation, model);
            ATTRIBUTE_TWO.validateAndSet(operation, model);
            ATTRIBUTE_RO.validateAndSet(operation, model);
            ATTRIBUTE_NON_CONFIG.validateAndSet(operation, model);
        }
    }
}
