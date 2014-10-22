/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
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

package org.jboss.as.controller.persistence;

import static org.jboss.as.controller.logging.ControllerLogger.ROOT_LOGGER;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLElementWriter;
import org.jboss.staxmapper.XMLMapper;

/**
 * A configuration persister which uses an XML file for backing storage.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public class XmlConfigurationPersister extends AbstractConfigurationPersister {

    private final File fileName;
    private final QName rootElement;
    private final XMLElementReader<List<ModelNode>> rootParser;
    private final Map<QName, XMLElementReader<List<ModelNode>>> additionalParsers;

    /**
     * Construct a new instance.
     *
     * @param fileName the configuration base file name
     * @param rootElement the root element of the configuration file
     * @param rootParser the root model parser
     * @param rootDeparser the root model deparser
     */
    public XmlConfigurationPersister(final File fileName, final QName rootElement, final XMLElementReader<List<ModelNode>> rootParser, final XMLElementWriter<ModelMarshallingContext> rootDeparser) {
        super(rootDeparser);
        this.fileName = fileName;
        this.rootElement = rootElement;
        this.rootParser = rootParser;
        additionalParsers = new HashMap<QName, XMLElementReader<List<ModelNode>>>();
    }

    public void registerAdditionalRootElement(final QName anotherRoot, final XMLElementReader<List<ModelNode>> parser){
        synchronized (additionalParsers) {
            additionalParsers.put(anotherRoot, parser);
        }
    }

    /** {@inheritDoc} */
    @Override
    public PersistenceResource store(final ManagementModel model, Set<PathAddress> affectedAddresses) throws ConfigurationPersistenceException {
        return new FilePersistenceResource(Resource.Tools.readModel(model.getRootResource()), fileName, this);
    }

    /** {@inheritDoc} */
    @Override
    public List<ModelNode> load(ManagementModel mgmtModel) throws ConfigurationPersistenceException {
        final XMLMapper mapper = XMLMapper.Factory.create();
        mapper.registerRootElement(rootElement, rootParser);
        synchronized (additionalParsers) {
            for (Map.Entry<QName, XMLElementReader<List<ModelNode>>> entry : additionalParsers.entrySet()) {
                mapper.registerRootElement(entry.getKey(), entry.getValue());
            }
        }
        final List<ModelNode> updates = new ArrayList<ModelNode>();
        try {
            final FileInputStream fis = new FileInputStream(fileName);
            try {
                BufferedInputStream input = new BufferedInputStream(fis);
                XMLStreamReader streamReader = XMLInputFactory.newInstance().createXMLStreamReader(input);
                mapper.parseDocument(updates, streamReader);
                streamReader.close();
                input.close();
                fis.close();
            } finally {
                safeClose(fis);
            }
        } catch (Exception e) {
            throw ControllerLogger.ROOT_LOGGER.failedToParseConfiguration(e);
        }
        return updates;
    }

    private static void safeClose(final Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (Throwable t) {
            ROOT_LOGGER.failedToCloseResource(t, closeable);
        }
    }

    protected void successfulBoot(File file) throws ConfigurationPersistenceException {

    }

    @Override
    public String snapshot() throws ConfigurationPersistenceException {
        return "";
    }
}
