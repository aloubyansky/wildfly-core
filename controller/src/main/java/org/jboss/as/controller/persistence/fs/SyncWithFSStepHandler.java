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
import java.util.List;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.common.ControllerResolver;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
public class SyncWithFSStepHandler implements OperationStepHandler {

    public static final String NAME = "sync-with-fs";

    public static final OperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(NAME, ControllerResolver.getResolver())
        .build();

    private final File dir;

    public SyncWithFSStepHandler(File dir) {
        if(dir == null) {
            throw new IllegalArgumentException("Directory is null");
        }
        this.dir = dir;
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        final ImmutableManagementResourceRegistration registration = context.getResourceRegistration();
        final Resource actual = context.readResource(PathAddress.EMPTY_ADDRESS);
        if(FSPersistence.isPersistent(actual, registration)) {
            List<ResourceDiff> diffs;
            try {
                diffs = FSPersistence.diff(registration, operation.get(ModelDescriptionConstants.ADDRESS), actual, dir);
            } catch (IOException e) {
                throw new OperationFailedException("Failed to read resource from " + dir.getAbsolutePath(), e);
            }

            if(!diffs.isEmpty()) {
                try {
                for(ResourceDiff diff : diffs) {
                    diff.addStepHandlers(context, registration);
                }
                } catch(OperationFailedException e) {
                    e.printStackTrace();
                    throw e;
                } catch(Throwable t) {
                    t.printStackTrace();
                    throw new OperationFailedException("Failed to add step handlers", t);
                }
            }
        }

        context.stepCompleted();
    }
}