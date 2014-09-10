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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
abstract class ResourceDiffWithAttributes extends ResourceDiffWithAddress {

    protected Map<String, ModelNode> attributes = Collections.emptyMap();

    protected ResourceDiffWithAttributes(ModelNode address, String opName) {
        super(address, opName);
    }

    public void addDiff(String name, ModelNode value) {
        if(name == null) {
            throw new IllegalArgumentException("Attribute name is null");
        }
        if(value == null) {
            throw new IllegalArgumentException("Attribute value is null");
        }
        switch(attributes.size()) {
            case 0:
                attributes = Collections.singletonMap(name, value);
                break;
            case 1:
                attributes = new HashMap<String,ModelNode>(attributes);
            default:
                attributes.put(name, value);
        }
    }

    public Set<String> getAttributeNames() {
        return attributes.keySet();
    }

    public ModelNode getAttributeValue(String name) {
        return attributes.get(name);
    }
}