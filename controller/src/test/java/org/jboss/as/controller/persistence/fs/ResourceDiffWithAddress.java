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

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationContext.Stage;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
abstract class ResourceDiffWithAddress implements ResourceDiff {

    protected final ModelNode address;
    protected final String opName;

    protected ResourceDiffWithAddress(ModelNode address, String opName) {
        if(address == null) {
            throw new IllegalArgumentException("The address is null");
        }
        if(!address.isDefined()) {
            throw new IllegalArgumentException("The address is undefined");
        }
        this.address = address;

        if(opName == null) {
            throw new IllegalArgumentException("The operation name is null");
        }
        this.opName = opName;
    }

    public ModelNode getAddress() {
        return address;
    }

    @Override
    public void addStepHandlers(OperationContext opCtx, ImmutableManagementResourceRegistration resReg) throws OperationFailedException {
        addStepHandler(opCtx, resReg, toOperationRequest());
    }

    protected void addStepHandler(OperationContext opCtx, ImmutableManagementResourceRegistration resReg, final ModelNode op)
            throws OperationFailedException {
        final OperationStepHandler step = resReg.getOperationHandler(PathAddress.EMPTY_ADDRESS, opName);
        if(step == null) {
            throw new OperationFailedException("No handler for " + opName);
        }
        opCtx.addStep(op, step, Stage.MODEL);
    }

    protected ModelNode toOperationRequest() {
        return getAddressedRequest();
    }

    protected ModelNode getAddressedRequest() {
        final ModelNode req = new ModelNode();
        req.get(ModelDescriptionConstants.ADDRESS).set(address);
        req.get(ModelDescriptionConstants.OP).set(opName);
        return req;
    }

    @Override
    public String toString() {
        return toOperationRequest().asString();
    }
}