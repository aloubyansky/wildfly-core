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
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.dmr.ModelNode;

/**
 * Represents a difference between two resources at the specific point
 * in the tree. I.e. it could be a child resource added or
 * child resource removed, or an attribute change.
 *
 * @author Alexey Loubyansky
 */
interface ResourceDiff {

    /**
     * Returns the absolute address for this diff
     *
     * @return absolute address for the diff
     */
    ModelNode getAddress();

/*    *//**
     * Returns an operation request which should be executed
     * to eliminate the difference this instance represents.
     *
     * @return  operation request to eliminate the difference
     * represented by this instance
     *//*
    ModelNode toOperationRequest();
*/
    /**
     * Adds the operations and their handlers that should be executed
     * to bring the resource in sync with its persistent state on the filesystem
     * to the operation context
     *
     * @param opCtx  the operation context
     * @param resReg  resource registration
     * @throws OperationFailedException  in case something goes wrong
     */
    void addStepHandlers(OperationContext opCtx, ImmutableManagementResourceRegistration resReg) throws OperationFailedException;

    class Factory {
        public static AddResourceDiff add(ModelNode address) {
            return new AddResourceDiff(address);
        }

        public static RemoveResourceDiff remove(ModelNode address) {
            return new RemoveResourceDiff(address);
        }

        public static WriteAttributeDiff writeAttribute(ModelNode address) {
            return new WriteAttributeDiff(address);
        }
    }
}