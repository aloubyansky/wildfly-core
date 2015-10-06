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
package org.jboss.as.cli.batch.impl;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.as.cli.Util;
import org.jboss.as.cli.batch.Batch;
import org.jboss.as.cli.batch.BatchedCommand;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
public class DefaultBatch implements Batch {

    private OperationBuilder opBuilder;
    private final List<BatchedCommand> commands = new ArrayList<BatchedCommand>();
    private List<Closeable> closeables = Collections.emptyList();

    /* (non-Javadoc)
     * @see org.jboss.as.cli.batch.Batch#getCommands()
     */
    @Override
    public List<BatchedCommand> getCommands() {
        return commands;
    }

    @Override
    public void add(BatchedCommand cmd) {
        if(cmd == null) {
            throw new IllegalArgumentException("Null argument.");
        }
        commands.add(cmd);
    }

    @Override
    public void clear() {
        commands.clear();
    }

    @Override
    public void remove(int lineNumber) {
        ensureRange(lineNumber);
        commands.remove(lineNumber);
    }

    @Override
    public void set(int index, BatchedCommand cmd) {
        ensureRange(index);
        commands.set(index, cmd);
    }

    protected void ensureRange(int lineNumber) {
        if(lineNumber < 0 || lineNumber > commands.size() - 1) {
            throw new IndexOutOfBoundsException(lineNumber + " isn't in range [0.." + (commands.size() - 1) + "]");
        }
    }

    @Override
    public int size() {
        return commands.size();
    }

    @Override
    public void move(int currentIndex, int newIndex) {
        ensureRange(currentIndex);
        ensureRange(newIndex);
        if(currentIndex == newIndex) {
            return;
        }

        BatchedCommand cmd = commands.get(currentIndex);
        int step = newIndex > currentIndex ? 1 : -1;
        for(int i = currentIndex; i != newIndex; i += step) {
            commands.set(i, commands.get(i + step));
        }
        commands.set(newIndex, cmd);
    }

    @Override
    public ModelNode toRequest() {
        final ModelNode composite = new ModelNode();
        composite.get(Util.OPERATION).set(Util.COMPOSITE);
        composite.get(Util.ADDRESS).setEmptyList();
        final ModelNode steps = composite.get(Util.STEPS);
        for(BatchedCommand cmd : commands) {
            steps.add(cmd.getRequest());
        }
        return composite;
    }

    @Override
    public Operation toOperation() {

        final ModelNode composite = new ModelNode();
        composite.get(Util.OPERATION).set(Util.COMPOSITE);
        composite.get(Util.ADDRESS).setEmptyList();
        final ModelNode steps = composite.get(Util.STEPS);

        opBuilder = new OperationBuilder(composite);
        for(BatchedCommand cmd : commands) {
            cmd.attachStreams(this);
            steps.add(cmd.getRequest());
        }

        return opBuilder.build();
    }

    @Override
    public int attachFile(File f) {
        opBuilder.addFileAsAttachment(f);
        return opBuilder.getInputStreamCount() - 1;
    }

    @Override
    public void closeWithBatch(Closeable closeable) {
        assert closeable != null : "closeable is null";
        switch(closeables.size()) {
            case 0:
                closeables = Collections.singletonList(closeable);
                break;
            case 1:
                closeables = new ArrayList<Closeable>(closeables);
            default:
                closeables.add(closeable);
        }
    }

    @Override
    public void close() throws IOException {
        if(closeables.isEmpty()) {
            return;
        }
        StringBuilder buf = null;
        for(Closeable c : closeables) {
            try {
                c.close();
            } catch(IOException e) {
                if(buf == null) {
                    buf = new StringBuilder();
                } else {
                    buf.append(", ");
                }
                buf.append(e.getLocalizedMessage());
            }
        }
        if(buf != null) {
            throw new IOException("Failed to close resources used in the batch: " + buf.toString());
        }
        throw new IllegalStateException("CLOSED BATCH");
    }
}
