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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.persistence.ConfigurationPersistenceException;
import org.jboss.as.controller.persistence.ExtensibleConfigurationPersister;
import org.jboss.as.controller.persistence.SubsystemMarshallingContext;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.staxmapper.XMLElementWriter;

/**
 *
 * @author Alexey Loubyansky
 */
public class FSTreeConfigurationPersister implements ExtensibleConfigurationPersister { //ConfigurationPersister {

    private final File root;
    private final AtomicBoolean successfulBoot = new AtomicBoolean();

    public FSTreeConfigurationPersister(File root) {
        if(root == null) {
            throw new IllegalArgumentException("The root directory is null");
        }
        this.root = root;
    }

    public PersistenceResource store(final Resource res, final ManagementResourceRegistration registration)
            throws ConfigurationPersistenceException {

        if(!successfulBoot.get()) {
            return new PersistenceResource() {
                public void commit() {
                }

                public void rollback() {
                }
            };
        }

        return new PersistenceResource() {

            @Override
            public void commit() {
                try {
                    FSPersistence.persist(registration, res, root);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void rollback() {
                new Exception("ROLLBACK!!!").printStackTrace();
            }
        };
    }

    @Override
    public PersistenceResource store(ManagementModel model, Set<PathAddress> affectedAddresses)
            throws ConfigurationPersistenceException {
        return store(model.getRootResource(), model.getRootResourceRegistration());
    }

    @Override
    public void marshallAsXml(ModelNode model, OutputStream output) throws ConfigurationPersistenceException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ModelNode> load(ManagementModel mgmtModel) throws ConfigurationPersistenceException {

      final List<ModelNode> fsBootOps = new ArrayList<ModelNode>();
      try {
          for(ResourceDiff diff : FSPersistence.diff(mgmtModel.getRootResourceRegistration(),
                  new ModelNode().setEmptyList(), mgmtModel.getRootResource(), root, true)) {
              fsBootOps.add(diff.toOperationRequest());
          }
      } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
      }
      return fsBootOps;
    }

    @Override
    public void successfulBoot() throws ConfigurationPersistenceException {
        successfulBoot.compareAndSet(false, true);
    }

    @Override
    public String snapshot() throws ConfigurationPersistenceException {
        return null;
    }

    @Override
    public SnapshotInfo listSnapshots() {
        return NULL_SNAPSHOT_INFO;
    }

    @Override
    public void deleteSnapshot(String name) {
    }

    @Override
    public void registerSubsystemWriter(String name, XMLElementWriter<SubsystemMarshallingContext> writer) {
        // TODO Auto-generated method stub
    }

    @Override
    public void unregisterSubsystemWriter(String name) {
        // TODO Auto-generated method stub
    }
}
