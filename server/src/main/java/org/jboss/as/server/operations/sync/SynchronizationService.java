/*
 * Copyright 2016 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.server.operations.sync;

import static org.jboss.as.controller.AbstractControllerService.EXECUTOR_CAPABILITY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_MODEL;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESPONSE_HEADERS;
import static org.jboss.as.controller.operations.common.OrderedChildTypesAttachment.ORDERED_CHILDREN;
import static org.jboss.as.server.Services.JBOSS_AS;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import org.jboss.as.controller.AbstractControllerService;
import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.ModelControllerClientFactory;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.ModelControllerClientConfiguration;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.server.ServerEnvironmentService;
import org.jboss.as.server.logging.ServerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;

/**
 * Service performing reading the local and remote models.
 * @author Emmanuel Hugonnet (c) 2018 Red Hat, inc.
 */
public class SynchronizationService implements Service<SynchronizationService> {

    public static final ServiceName SERVICE_NAME = JBOSS_AS.append("synchronization");

    private final ModelControllerClientConfiguration configuration;
    private final Supplier<ExecutorService> executorServiceSupplier;
    private final Supplier<ServerEnvironment> serverEnvironmentSupplier;
    private final Supplier<PathManager> pathManagerSupplier;
    private final Supplier<ModelControllerClientFactory> modelControllerClientFactorySupplier;
    private ModelControllerClient remoteClient;
    private ModelControllerClient client;
    private final ExtensionRegistry extensionRegistry;
    private final ExpressionResolver expressionResolver;

    private SynchronizationService(ModelControllerClientConfiguration configuration,
                                   final ExtensionRegistry extensionRegistry, final ExpressionResolver expressionResolver,
                                   final Supplier<ModelControllerClientFactory> modelControllerClientFactorySupplier,
                                   final Supplier<ServerEnvironment> serverEnvironmentSupplier,
                                   final Supplier<ExecutorService> executorServiceSupplier,
                                   final Supplier<PathManager> pathManagerSupplier) {
        this.configuration = configuration;
        this.extensionRegistry = extensionRegistry;
        this.expressionResolver = expressionResolver;
        this.executorServiceSupplier = executorServiceSupplier;
        this.modelControllerClientFactorySupplier = modelControllerClientFactorySupplier;
        this.pathManagerSupplier = pathManagerSupplier;
        this.serverEnvironmentSupplier = serverEnvironmentSupplier;
    }

    public static void addService(final OperationContext context , final ModelControllerClientConfiguration configuration,
            final ExtensionRegistry extensionRegistry, final ExpressionResolver expressionResolver) {
        final ServiceTarget serviceTarget = context.getServiceTarget();
        ServiceBuilder sb = serviceTarget.addService(SERVICE_NAME);
        Supplier<ModelControllerClientFactory> modelControllerClientFactorySupplier = sb.requires(context.getCapabilityServiceName("org.wildfly.management.model-controller-client-factory", null));
        Supplier<ServerEnvironment> serverEnvironmentSupplier = sb.requires(ServerEnvironmentService.SERVICE_NAME);
        Supplier<ExecutorService> executorServiceSupplier = sb.requires(EXECUTOR_CAPABILITY.getCapabilityServiceName());
        Supplier<PathManager> pathManagerSupplier = sb.requires(AbstractControllerService.PATH_MANAGER_CAPABILITY.getCapabilityServiceName());
        sb.setInstance(new SynchronizationService(configuration, extensionRegistry, expressionResolver,
                modelControllerClientFactorySupplier, serverEnvironmentSupplier, executorServiceSupplier,
                pathManagerSupplier))
                .install();
    }

    @Override
    public void start(StartContext sc) throws StartException {
        remoteClient = ModelControllerClient.Factory.create(configuration);
        client = modelControllerClientFactorySupplier.get().createClient(executorServiceSupplier.get());
    }

    public void synchronize(final OperationContext context, boolean dryRun, boolean export, boolean config) {
        final ServerSyncModelParameters parameters = new ServerSyncModelParameters(this, expressionResolver,
                serverEnvironmentSupplier.get(), extensionRegistry, pathManagerSupplier.get(), true);
        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                ServerSyncOperationHandler handler = new ServerSyncOperationHandler(parameters, dryRun, export, config);
                // Create the operation to get the required configuration from the master
                ModelNode response = readRemoteModel();
                ModelNode result = Operations.readResult(response);
                if(response.hasDefined(RESPONSE_HEADERS, ORDERED_CHILDREN)) {
                    OrderedChildTypesAttachment orderedChildTypes = new OrderedChildTypesAttachment();
                    orderedChildTypes.fromModel(response.get(RESPONSE_HEADERS).get(ORDERED_CHILDREN));
                    context.attach(OrderedChildTypesAttachment.KEY, orderedChildTypes);
                }
                if (result.hasDefined(FAILURE_DESCRIPTION)) {
                    throw new OperationFailedException(result.get(FAILURE_DESCRIPTION).asString());
                }

                final ModelNode syncOperation = new ModelNode();
                syncOperation.get(OP).set("calculate-diff-and-sync");
                syncOperation.get(OP_ADDR).setEmptyList();
                syncOperation.get(DOMAIN_MODEL).set(result);

                // Execute the handler to synchronize the model
                context.addStep(syncOperation, handler, OperationContext.Stage.MODEL, true);
            }
        }, OperationContext.Stage.MODEL, true);
    }

    @Override
    public void stop(StopContext sc) {
        try {
            remoteClient.close();
        } catch (IOException ex) {
            ServerLogger.AS_ROOT_LOGGER.warn(ex.getMessage(), ex);
        }
        try {
            client.close();
        } catch (IOException ex) {
            ServerLogger.AS_ROOT_LOGGER.warn(ex.getMessage(), ex);
        }
    }

    @Override
    public SynchronizationService getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    ModelNode readLocalModel() {
        try {
            return Operations.readResult(client.execute(Operations.createOperation(ReadServerModelOperationHandler.OPERATION_NAME)));
        } catch (IOException ioex) {
            ServerLogger.AS_ROOT_LOGGER.warn(ioex.getMessage(), ioex);
            throw new RuntimeException(ioex);
        }
    }

    ModelNode readLocalOperations() {
        try {
            return Operations.readResult(client.execute(Operations.createOperation(ReadServerOperationsHandler.OPERATION_NAME)));
        } catch (IOException ioex) {
            ServerLogger.AS_ROOT_LOGGER.warn(ioex.getMessage(), ioex);
            throw new RuntimeException(ioex);
        }
    }

    ModelNode readRemoteModel() {
        try {
            return remoteClient.execute(Operations.createOperation(ReadServerModelOperationHandler.OPERATION_NAME));
        } catch (IOException ioex) {
            ServerLogger.AS_ROOT_LOGGER.warn(ioex.getMessage(), ioex);
            throw new RuntimeException(ioex);
        }
    }

    ModelNode readRemoteOperations() {
        try {
            return remoteClient.execute(Operations.createOperation(ReadServerOperationsHandler.OPERATION_NAME));
        } catch (IOException ioex) {
            ServerLogger.AS_ROOT_LOGGER.warn(ioex.getMessage(), ioex);
            throw new RuntimeException(ioex);
        }
    }
}
