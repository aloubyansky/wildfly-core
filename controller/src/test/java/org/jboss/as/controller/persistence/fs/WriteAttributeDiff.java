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

import java.util.Map;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
class WriteAttributeDiff extends ResourceDiffWithAttributes {

    WriteAttributeDiff(ModelNode address) {
        super(address, ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION);
    }

    @Override
    public void addStepHandlers(OperationContext opCtx, ImmutableManagementResourceRegistration resReg)
            throws OperationFailedException {
        if(attributes.isEmpty()) {
            throw new IllegalStateException("No attributes to write");
        }
        for(Map.Entry<String, ModelNode> attr : attributes.entrySet()) {
            addStepHandler(opCtx, resReg, writeAttributeOp(attr.getKey(), attr.getValue()));
        }
    }

    @Override
    protected ModelNode toOperationRequest() {
        if(attributes.isEmpty()) {
            throw new IllegalStateException("No attributes to write");
        }
        if(attributes.size() == 1) {
            final String name = attributes.keySet().iterator().next();
            return writeAttributeOp(name, attributes.get(name));
        }
        final ModelNode composite = new ModelNode();
        composite.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.COMPOSITE);
        composite.get(ModelDescriptionConstants.ADDRESS).setEmptyList();
        final ModelNode steps = composite.get(ModelDescriptionConstants.STEPS);
        for(Map.Entry<String, ModelNode> attr : attributes.entrySet()) {
            final ModelNode op = writeAttributeOp(attr.getKey(), attr.getValue());
            steps.add(op);
        }
        return composite;
    }

    protected ModelNode writeAttributeOp(String name, ModelNode value) {
        final ModelNode op = getAddressedRequest();
        op.get(ModelDescriptionConstants.NAME).set(name);
        op.get(ModelDescriptionConstants.VALUE).set(value);
        return op;
    }
}