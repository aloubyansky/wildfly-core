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

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.junit.Test;

/**
 *
 * @author Alexey Loubyansky
 */
public class ResourceDiffTestCase {

    @Test
    public void testEmptyToEmpty() throws Exception {
        assertTrue(FSPersistence.diff(Resource.Factory.create(), Resource.Factory.create()).isEmpty());
    }

    @Test
    public void testOnlyModel() throws Exception {
        final Resource actual = Resource.Factory.create();
        final Resource target = Resource.Factory.create();

        final ModelNode actualModel = actual.getModel();
        actualModel.get("one").set(1);
        actualModel.get("two").set(2);
        actualModel.get("three").set(3);

        final ModelNode targetModel = target.getModel();
        targetModel.get("one").set("1");
        targetModel.get("two").set(2);
        targetModel.get("three");

        final List<ResourceDiff> diff = FSPersistence.diff(actual, target);
        assertEquals(1, diff.size());

        final WriteAttributeDiff attrDiff = (WriteAttributeDiff) diff.get(0);
        assertTrue(attrDiff.getAddress().asList().isEmpty());
        final Set<String> names = attrDiff.getAttributeNames();
        assertEquals(2, names.size());
        assertEquals(new ModelNode("1"), attrDiff.getAttributeValue("one"));
        assertEquals(new ModelNode(), attrDiff.getAttributeValue("three"));
    }

    @Test
    public void testAddAllChildren() throws Exception {

        final Resource actual = Resource.Factory.create();
        final Resource target = Resource.Factory.create();

        registerChild("a", "1", target);

        final Resource a2 = registerChild("a", "2", target);

        final Resource b1 = registerChild("b", "1", a2);
        b1.getModel().get("one").set(1);
        b1.getModel().get("two").set(2);

        registerChild("c", "1", b1);

        registerChild("a", "3", target);

        final List<ResourceDiff> diffs = FSPersistence.diff(actual, target);
        assertEquals(5, diffs.size());

        boolean sawA1 = false;
        boolean sawA2 = false;
        boolean sawA3 = false;
        boolean sawB1 = false;
        boolean sawC1 = false;
        for(ResourceDiff diff : diffs) {
            assertTrue(diff instanceof AddResourceDiff);
            AddResourceDiff addDiff = (AddResourceDiff) diff;
            final List<Property> address = addDiff.getAddress().asPropertyList();
            if(address.size() == 1) {
                // must be one of the a's
                final Property node = address.get(0);
                assertEquals("a", node.getName());
                final String child = node.getValue().asString();
                if("1".equals(child) && !sawA1) {
                    sawA1 = true;
                } else if("2".equals(child) && !sawA2) {
                    sawA2 = true;
                } else if("3".equals(child) && !sawA3) {
                    sawA3 = true;
                } else {
                    fail("Unexpected address " + address);
                }
                assertTrue(addDiff.getAttributeNames().isEmpty());
            } else if(address.size() == 2) {
                if(!sawA2) {
                    fail("Child b1 is added before its parent a2");
                }
                // must be b1
                Property node = address.get(0);
                assertEquals("a", node.getName());
                assertEquals("2", node.getValue().asString());
                node = address.get(1);
                assertEquals("b", node.getName());
                assertEquals("1", node.getValue().asString());
                final Set<String> attrNames = addDiff.getAttributeNames();
                assertEquals(2, attrNames.size());
                assertTrue(attrNames.contains("one"));
                assertTrue(attrNames.contains("two"));
                assertEquals(1, addDiff.getAttributeValue("one").asInt());
                assertEquals(2, addDiff.getAttributeValue("two").asInt());
                sawB1 = true;
            } else if(address.size() == 3) {
                if(!sawB1) {
                    fail("Child c1 is added before its parent b1");
                }
                // must be c1
                Property node = address.get(0);
                assertEquals("a", node.getName());
                assertEquals("2", node.getValue().asString());
                node = address.get(1);
                assertEquals("b", node.getName());
                assertEquals("1", node.getValue().asString());
                node = address.get(2);
                assertEquals("c", node.getName());
                assertEquals("1", node.getValue().asString());
                assertTrue(addDiff.getAttributeNames().isEmpty());
                sawC1 = true;
            } else {
                fail("Unexpected address " + address);
            }
        }
        assertTrue(sawA1 && sawA2 && sawA3 && sawB1 && sawC1);
    }

    @Test
    public void testRemoveAllChildren() throws Exception {

        final Resource actual = Resource.Factory.create();
        final Resource target = Resource.Factory.create();

        registerChild("a", "1", actual);

        final Resource a2 = registerChild("a", "2", actual);

        final Resource b1 = registerChild("b", "1", a2);
        b1.getModel().get("one").set(1);
        b1.getModel().get("two").set(2);

        registerChild("c", "1", b1);

        registerChild("a", "3", actual);

        final List<ResourceDiff> diffs = FSPersistence.diff(actual, target);
        assertEquals(5, diffs.size());

        boolean sawA1 = false;
        boolean sawA2 = false;
        boolean sawA3 = false;
        boolean sawB1 = false;
        boolean sawC1 = false;
        for(ResourceDiff diff : diffs) {
            assertTrue(diff instanceof RemoveResourceDiff);
            RemoveResourceDiff rmDiff = (RemoveResourceDiff) diff;
            final List<Property> address = rmDiff.getAddress().asPropertyList();
            if(address.size() == 1) {
                // must be one of the a's
                final Property node = address.get(0);
                assertEquals("a", node.getName());
                final String child = node.getValue().asString();
                if("1".equals(child) && !sawA1) {
                    sawA1 = true;
                } else if("2".equals(child) && !sawA2) {
                    if(!sawB1) {
                        fail("Parent a2 is removed before its child b1");
                    }
                    sawA2 = true;
                } else if("3".equals(child) && !sawA3) {
                    sawA3 = true;
                } else {
                    fail("Unexpected address " + address);
                }
            } else if(address.size() == 2) {
                if(!sawC1) {
                    fail("Parent b1 is removed before its child c1");
                }
                // must be b1
                Property node = address.get(0);
                assertEquals("a", node.getName());
                assertEquals("2", node.getValue().asString());
                node = address.get(1);
                assertEquals("b", node.getName());
                assertEquals("1", node.getValue().asString());
                sawB1 = true;
            } else if(address.size() == 3) {
                // must be c1
                Property node = address.get(0);
                assertEquals("a", node.getName());
                assertEquals("2", node.getValue().asString());
                node = address.get(1);
                assertEquals("b", node.getName());
                assertEquals("1", node.getValue().asString());
                node = address.get(2);
                assertEquals("c", node.getName());
                assertEquals("1", node.getValue().asString());
                sawC1 = true;
            } else {
                fail("Unexpected address " + address);
            }
        }
        assertTrue(sawA1 && sawA2 && sawA3 && sawB1 && sawC1);
    }

    @Test
    public void testMix() throws Exception {

        final Resource actual = Resource.Factory.create();
        final Resource target = Resource.Factory.create();

        // identical child with undefined model
        registerChild("a", "1", actual);
        registerChild("a", "1", target);

        // identical child with defined model
        final Resource actualA2 = registerChild("a", "2", actual);
        actualA2.getModel().get("one").set(1);
        final Resource targetA2 = registerChild("a", "2", target);
        targetA2.getModel().get("one").set(1);

        // child with attribute difference
        final Resource actualA3 = registerChild("a", "3", actual);
        actualA3.getModel().get("one").set(1);
        actualA3.getModel().get("two").set(2);
        actualA3.getModel().get("three").set(3);
        final Resource targetA3 = registerChild("a", "3", target);
        targetA3.getModel().get("one").set(1);
        targetA3.getModel().get("two").set(22);
        targetA3.getModel().get("three").set(33);

        // child to be removed
        registerChild("a", "x", actual);

        // child to be added
        registerChild("a", "4", target);

        final Resource actualA5 = registerChild("a", "5", actual);
        final Resource targetA5 = registerChild("a", "5", target);

        final Resource actualB1 = registerChild("b", "1", actualA5);
        actualB1.getModel().get("one").set(1);
        actualB1.getModel().get("two").set(2);
        final Resource targetB1 = registerChild("b", "1", targetA5);
        targetB1.getModel().get("one").set(11);
        targetB1.getModel().get("two").set(2);

        // children to be removed
        final Resource actualC1 = registerChild("c", "1", actualB1);
        actualC1.getModel().get("one").set(1);
        registerChild("d", "1", actualC1);

        // children to be added
        final Resource targetC2 = registerChild("c", "2", targetB1);
        targetC2.getModel().get("one").set(1);
        registerChild("d", "1", targetC2);

        final List<ResourceDiff> diffs = FSPersistence.diff(actual, target);
        assertEquals(8, diffs.size());

        boolean sawA3 = false;
        boolean sawAx = false;
        boolean sawA4 = false;
        boolean sawB1 = false;
        boolean sawC1 = false;
        boolean sawC2 = false;
        boolean sawD1Removed = false;
        boolean sawD1Added = false;
        for(ResourceDiff diff : diffs) {
            final List<Property> address = diff.getAddress().asPropertyList();
            if(address.size() == 1) {
                assertEquals("a", address.get(0).getName());
                final String child = address.get(0).getValue().asString();
                if("3".equals(child)) {
                    assertTrue(diff instanceof WriteAttributeDiff);
                    final WriteAttributeDiff attrDiff = (WriteAttributeDiff) diff;
                    final Set<String> attrNames = attrDiff.getAttributeNames();
                    assertEquals(2, attrNames.size());
                    assertTrue(attrNames.contains("two"));
                    assertTrue(attrNames.contains("three"));
                    assertEquals(22, attrDiff.getAttributeValue("two").asInt());
                    assertEquals(33, attrDiff.getAttributeValue("three").asInt());
                    sawA3 = true;
                } else if("x".equals(child)) {
                    assertTrue(diff instanceof RemoveResourceDiff);
                    sawAx = true;
                } else if("4".equals(child)) {
                    assertTrue(diff instanceof AddResourceDiff);
                    assertTrue(((AddResourceDiff)diff).getAttributeNames().isEmpty());
                    sawA4 = true;
                } else {
                    fail("Unexpected child " + address);
                }
            } else if(address.size() == 2) {
                assertEquals("a", address.get(0).getName());
                assertEquals(5, address.get(0).getValue().asInt());
                assertEquals("b", address.get(1).getName());
                assertEquals(1, address.get(1).getValue().asInt());
                assertTrue(diff instanceof WriteAttributeDiff);
                final WriteAttributeDiff attrDiff = (WriteAttributeDiff) diff;
                assertEquals(Collections.singleton("one"), attrDiff.getAttributeNames());
                assertEquals(11, attrDiff.getAttributeValue("one").asInt());
                sawB1 = true;
            } else if(address.size() == 3) {
                assertEquals("a", address.get(0).getName());
                assertEquals(5, address.get(0).getValue().asInt());
                assertEquals("b", address.get(1).getName());
                assertEquals(1, address.get(1).getValue().asInt());
                assertEquals("c", address.get(2).getName());
                final int child = address.get(2).getValue().asInt();
                if(child == 1) {
                    assertTrue(diff instanceof RemoveResourceDiff);
                    if(!sawD1Removed) {
                        fail("Parent c1 is removed before its child d1");
                    }
                    sawC1 = true;
                } else if(child == 2) {
                    assertTrue(diff instanceof AddResourceDiff);
                    final AddResourceDiff addDiff = (AddResourceDiff) diff;
                    assertEquals(Collections.singleton("one"), addDiff.getAttributeNames());
                    assertEquals(1, addDiff.getAttributeValue("one").asInt());
                    sawC2 = true;
                } else {
                    fail("Unexpected node " + address.toString());
                }
            } else if(address.size() == 4) {
                assertEquals("a", address.get(0).getName());
                assertEquals(5, address.get(0).getValue().asInt());
                assertEquals("b", address.get(1).getName());
                assertEquals(1, address.get(1).getValue().asInt());
                assertEquals("c", address.get(2).getName());
                final int cName = address.get(2).getValue().asInt();
                assertEquals("d", address.get(3).getName());
                assertEquals(1, address.get(3).getValue().asInt());
                if(cName == 1) {
                    assertTrue(diff instanceof RemoveResourceDiff);
                    sawD1Removed = true;
                } else if(cName == 2) {
                    assertTrue(diff instanceof AddResourceDiff);
                    if(!sawC2) {
                        fail("Child d1 is added before its parent c2");
                    }
                    assertTrue(((AddResourceDiff)diff).getAttributeNames().isEmpty());
                    sawD1Added = true;
                } else {
                    fail("Unexpected node " + address.toString());
                }
            } else {
                fail("Unexpected address " + address);
            }
        }
        assertTrue(sawA3 && sawAx && sawA4 && sawB1 && sawC1 && sawC2 && sawD1Removed && sawD1Added);
    }

    private static Resource registerChild(String type, String name, Resource parent) {
        final Resource child = Resource.Factory.create();
        parent.registerChild(PathElement.pathElement(type, name), child);
        return child;
    }
}
