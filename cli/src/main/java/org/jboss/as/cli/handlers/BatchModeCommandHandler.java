/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
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
package org.jboss.as.cli.handlers;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineException;
import org.jboss.as.cli.batch.Batch;
import org.jboss.as.cli.batch.impl.DefaultBatchedCommand;
import org.jboss.dmr.ModelNode;


/**
 *
 * @author Alexey Loubyansky
 */
public abstract class BatchModeCommandHandler extends BaseOperationCommand {

    public BatchModeCommandHandler(CommandContext ctx, String command, boolean connectionRequired) {
        super(ctx, command, connectionRequired);
    }

    @Override
    public boolean isBatchMode(CommandContext ctx) {
        try {
            if(this.helpArg.isPresent(ctx.getParsedCommandLine())) {
                return false;
            }
        } catch (CommandFormatException e) {
            // this is not nice...
            // but if it failed here it won't be added to the batch,
            // will be executed immediately and will fail with the same exception
            return false;
        }
        return true;
    }

    public void addToBatch(CommandContext ctx) throws CommandLineException {
        final Batch batch = ctx.getBatchManager().getActiveBatch();
        addSteps(ctx, batch);
    }

    protected void addSteps(CommandContext ctx, Batch batch) throws CommandLineException {
        recognizeArguments(ctx);
        final ModelNode step = buildBatchStepWithoutHeaders(ctx);
        addHeaders(ctx, step);
        batch.add(new DefaultBatchedCommand(ctx.getParsedCommandLine().getOriginalLine(), step));
    }

    protected ModelNode buildBatchStepWithoutHeaders(CommandContext ctx) throws CommandLineException {
        return buildRequestWithoutHeaders(ctx);
    }
}
