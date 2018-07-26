/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.server.operations;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_ORGANIZATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FEATURE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PARAMS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleOperationDefinition;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.access.management.SensitiveTargetAccessConstraintDefinition;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.common.ControllerResolver;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.sync.PathAddressFilter;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 *
 * @author Alexey Loubyansky
 */
public class ReadProvisioningModelOperationHandler implements OperationStepHandler {

    private static final String OPERATION_NAME = "read-provisioning-model";
    public static final ReadProvisioningModelOperationHandler INSTANCE = new ReadProvisioningModelOperationHandler(OPERATION_NAME, false);

    public static final SimpleOperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(OPERATION_NAME, ControllerResolver.getResolver(SUBSYSTEM))
            .addAccessConstraint(SensitiveTargetAccessConstraintDefinition.READ_WHOLE_CONFIG)
            .setReplyType(ModelType.LIST)
            .setReplyValueType(ModelType.OBJECT)
            .build();

    private static final Set<String> ROOT_ATTRIBUTES = new HashSet<>(Arrays.asList(DOMAIN_ORGANIZATION));
    private static final Set<String> FULL_ROOT_ATTRIBUTES = new HashSet<>(Arrays.asList(DOMAIN_ORGANIZATION, NAME));

    private final String operationName;
    private final boolean skipLocalAdd;

    protected ReadProvisioningModelOperationHandler(String operationName, boolean skipLocalAdd) {
        this.operationName = operationName;
        this.skipLocalAdd = skipLocalAdd;
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        final PathAddress address = PathAddress.pathAddress(operation.require(ModelDescriptionConstants.OP_ADDR));
        final PathAddressFilter filter = context.getAttachment(PathAddressFilter.KEY);
        if (filter != null && ! filter.accepts(address)) {
            return;
        }
        final ImmutableManagementResourceRegistration registration = context.getResourceRegistration();
        if (!registration.isFeature() || registration.isAlias() || registration.isRemote() || registration.isRuntimeOnly()) {
            return;
        }

        final Resource resource = context.readResource(PathAddress.EMPTY_ADDRESS, false);
        final ModelNode result = context.getResult();
        result.setEmptyList();
        final ModelNode results = new ModelNode().setEmptyList();
        final AtomicReference<ModelNode> failureRef = new AtomicReference<>();
        final Map<String, ModelNode> includeResults = new HashMap<>();

        // Step to handle failed operations
        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                boolean failed = false;
                if (failureRef.get() != null) {
                    // One of our subsystems failed
                    context.getFailureDescription().set(failureRef.get());
                    failed = true;
                } else {
                    for (final ModelNode includeRsp : includeResults.values()) {
                        if (includeRsp.hasDefined(FAILURE_DESCRIPTION)) {
                            context.getFailureDescription().set(includeRsp.get(FAILURE_DESCRIPTION));
                            failed = true;
                            break;
                        }
                        final ModelNode includeResult = includeRsp.get(RESULT);
                        if (includeResult.isDefined()) {
                            for (ModelNode op : includeResult.asList()) {
                                result.add(op);
                            }
                        }
                    }
                }
                if (!failed) {
                    for (final ModelNode childRsp : results.asList()) {
                        result.add(childRsp);
                    }
                    context.getResult().set(result);
                }
            }
        }, OperationContext.Stage.MODEL, true);

        describeChildren(resource, registration, filter, address, context, failureRef, results, operation);
        if (resource.isProxy() || resource.isRuntime()) {
            return;
        }
        appendGenericOperation(resource, registration, includeResults, operation, address, context);
    }

    private void describeChildren(final Resource resource, final ImmutableManagementResourceRegistration registration, final PathAddressFilter filter, final PathAddress address, OperationContext context, final AtomicReference<ModelNode> failureRef, final ModelNode results, ModelNode operation) {
        resource.getChildTypes().forEach((childType) -> {
            resource.getChildren(childType).stream()
                    .filter(entry -> {
                        final ImmutableManagementResourceRegistration childRegistration = registration.getSubModel(PathAddress.EMPTY_ADDRESS.append(entry.getPathElement()));
                        if(childRegistration == null) {
                            ControllerLogger.ROOT_LOGGER.warnf("Couldn't find a registration for %s at %s for resource %s at %s", entry.getPathElement().toString(), registration.getPathAddress().toCLIStyleString(), resource, address.toCLIStyleString());
                            return false;
                        }
                        return !childRegistration.isRuntimeOnly() && !childRegistration.isRemote() && !childRegistration.isAlias();
                    })
                    .filter(entry ->  filter == null || filter.accepts(address.append(entry.getPathElement())))
                    .forEach((entry) -> describeChildResource(entry, registration, address, context, failureRef, results, operation));
        });
    }

    private void appendGenericOperation(final Resource resource, final ImmutableManagementResourceRegistration registration, final Map<String, ModelNode> includeResults, ModelNode operation, final PathAddress address, OperationContext context) throws OperationFailedException {
        // Generic operation generation
        final ModelNode model = resource.getModel();
        if (registration.getOperationHandler(PathAddress.EMPTY_ADDRESS, ModelDescriptionConstants.ADD) != null) {
            appendAddResourceOperation(registration, includeResults, operation, address, context, resource);
        } else {
            registration.getAttributeNames(PathAddress.EMPTY_ADDRESS).stream()
                    .filter(attribute -> model.hasDefined(attribute))
                    .filter(attribute -> address.size() != 0 || isAcceptedRootAttribute(context, attribute))
                    .filter(attribute -> registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attribute).getStorageType() == AttributeAccess.Storage.CONFIGURATION)
                    .forEach(attribute -> appendWriteAttributeOperation(address, context, resource, attribute));
        }
    }

    private boolean isAcceptedRootAttribute(OperationContext context, String attribute) {
        if(context.getProcessType().isServer()) {
            return FULL_ROOT_ATTRIBUTES.contains(attribute);
        }
        return ROOT_ATTRIBUTES.contains(attribute);
    }

    private void appendAddResourceOperation(final ImmutableManagementResourceRegistration registration,
            final Map<String, ModelNode> includeResults, final ModelNode operation, final PathAddress address,
            final OperationContext context, final Resource resource) throws OperationFailedException {

        ImmutableManagementResourceRegistration registry = context.getRootResourceRegistration().getSubModel(address);
        final ModelNode featureConfig = new ModelNode();
        final ModelNode featureNode = featureConfig.get(FEATURE);
        featureNode.get("spec").set(registry.getFeature());

        ModelNode params = featureNode.get("id");

        final Set<String> idParams = new HashSet<>(address.size());
        for (PathElement elt : address) {
            final String paramName = elt.getKey();
            params.add(paramName, elt.getValue());
            idParams.add(paramName);
        }

        params = featureNode.get(PARAMS);
        final ModelNode model = resource.getModel();
        try(Stream<String> attrs = registration.getAttributeNames(PathAddress.EMPTY_ADDRESS).stream()) {
            final Iterator<String> i = attrs.iterator();
            while(i.hasNext()) {
                final String attrName = i.next();
                if(!model.hasDefined(attrName)) {
                    continue;
                }
                if(registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attrName).getStorageType() != AttributeAccess.Storage.CONFIGURATION) {
                    continue;
                }
                String paramName = attrName;
                if (idParams.contains(attrName) || ((PROFILE.equals(attrName) || HOST.equals(attrName)) && isSubsystem(address))) {
                    paramName = attrName + "-feature";
                }
                params.add(paramName, model.get(attrName));
            }
        }

        // Allow the profile describe handler to process profile includes
        processMore(context, operation, resource, address, includeResults);
        if (!skipLocalAdd) {
            context.getResult().add(featureConfig);
        }
    }

    private static boolean isSubsystem(PathAddress address) {
        for(PathElement elt : address) {
            if(SUBSYSTEM.equals(elt.getKey())) {
                return true;
            }
        }
        return false;
    }

    private void appendWriteAttributeOperation(final PathAddress address, final OperationContext context, final Resource resource, String attribute) {

        ImmutableManagementResourceRegistration registry = context.getRootResourceRegistration().getSubModel(address);
        final ModelNode featureConfig = new ModelNode();
        final ModelNode featureNode = featureConfig.get(FEATURE);
        featureNode.get("spec").set(registry.getFeature());

        ModelNode params = featureNode.get("id");
        for (PathElement elt : address) {
            final String paramName = elt.getKey();
            params.add(paramName, elt.getValue());
        }
        params = featureNode.get(PARAMS);
        params.add(attribute, resource.getModel().get(attribute));
        context.getResult().add(featureConfig);
    }

    private void describeChildResource(final Resource.ResourceEntry entry,
            final ImmutableManagementResourceRegistration registration, final PathAddress address,
            OperationContext context, final AtomicReference<ModelNode> failureRef,
            final ModelNode results, ModelNode operation) throws IllegalArgumentException {
        final ModelNode childRsp = new ModelNode();
        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                if (failureRef.get() == null) {
                    if (childRsp.hasDefined(FAILURE_DESCRIPTION)) {
                        failureRef.set(childRsp.get(FAILURE_DESCRIPTION));
                    } else if (childRsp.hasDefined(RESULT)) {
                        addChildOperation(address, childRsp.require(RESULT).asList(), results);
                    }
                }
            }
        }, OperationContext.Stage.MODEL, true);
        final ModelNode childOperation = operation.clone();
        childOperation.get(ModelDescriptionConstants.OP).set(operationName);
        final PathElement childPE = entry.getPathElement();
        childOperation.get(ModelDescriptionConstants.OP_ADDR).set(address.append(childPE).toModelNode());
        final ImmutableManagementResourceRegistration childRegistration = registration.getSubModel(PathAddress.EMPTY_ADDRESS.append(childPE));
        final OperationStepHandler stepHandler = childRegistration.getOperationHandler(PathAddress.EMPTY_ADDRESS, operationName);
        context.addStep(childRsp, childOperation, stepHandler, OperationContext.Stage.MODEL, true);
    }

    protected void addChildOperation(final PathAddress parent, final List<ModelNode> operations, ModelNode results) {
        for (final ModelNode operation : operations) {
            results.add(operation);
        }
    }

    protected void processMore(final OperationContext context, final ModelNode operation, final Resource resource, final PathAddress address, final Map<String, ModelNode> includeResults) throws OperationFailedException {

    }
}
