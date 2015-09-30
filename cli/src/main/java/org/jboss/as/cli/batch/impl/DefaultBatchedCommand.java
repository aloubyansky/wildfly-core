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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.as.cli.batch.BatchedCommand;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
public class DefaultBatchedCommand implements BatchedCommand {

    static class StreamParameter {
        final ModelNode parameterNode;
        final File f;

        StreamParameter(ModelNode parameterNode, File f) {
            assert parameterNode != null : "parameter node is null";
            assert f != null : "file is null";
            this.parameterNode = parameterNode;
            this.f = f;
        }
    }

    private final String command;
    private ModelNode request;
    private List<StreamParameter> params = Collections.emptyList();

    public DefaultBatchedCommand(String command) {
        this(command, null);
    }

    public DefaultBatchedCommand(String command, ModelNode request) {
        if(command == null) {
            throw new IllegalArgumentException("Command is null.");
        }
        this.command = command;
        if(request != null) {
            this.request = request;
        }
    }

    public String getCommand() {
        return command;
    }

    public ModelNode getRequest() {
        return request;
    }

    public void setRequest(ModelNode request) {
        assert request != null : "request is null";
        this.request = request;
    }

    public void attachFile(ModelNode parameterNode, File f) {
        switch(params.size()) {
            case 0:
                params = Collections.singletonList(new StreamParameter(parameterNode, f));
                break;
            case 1:
                params = new ArrayList<StreamParameter>(params);
            default:
                params.add(new StreamParameter(parameterNode, f));
        }
    }

    void attachStreams(DefaultBatch batch) {
        if(params.isEmpty()) {
            return;
        }
        for(StreamParameter param : params) {
            param.parameterNode.set(batch.attachFile(param.f));
        }
    }
}
