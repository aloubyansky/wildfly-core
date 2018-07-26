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
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PARAMS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
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
import org.jboss.as.controller.operations.PathAddressFilter;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 *
 * @author Alexey Loubyansky
 */
public class ReadConfigAsFeaturesOperationHandler implements OperationStepHandler {

    private static final String OPERATION_NAME = "read-config-as-features";
    public static final ReadConfigAsFeaturesOperationHandler INSTANCE = new ReadConfigAsFeaturesOperationHandler(OPERATION_NAME, false);

    public static final SimpleOperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(OPERATION_NAME, ControllerResolver.getResolver(SUBSYSTEM))
            .addAccessConstraint(SensitiveTargetAccessConstraintDefinition.READ_WHOLE_CONFIG)
            .setReplyType(ModelType.LIST)
            .setReplyValueType(ModelType.OBJECT)
            .build();

    private static final Set<String> ROOT_ATTRIBUTES = new HashSet<>(Arrays.asList(DOMAIN_ORGANIZATION));
    private static final Set<String> FULL_ROOT_ATTRIBUTES = new HashSet<>(Arrays.asList(DOMAIN_ORGANIZATION, NAME));

    private final String operationName;
    private final boolean skipLocalAdd;

    protected ReadConfigAsFeaturesOperationHandler(String operationName, boolean skipLocalAdd) {
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
        final ModelNode featureNode = new ModelNode();
        featureNode.get("spec").set(registry.getFeature());

        final ModelNode idParams = featureNode.get("id");

        final Set<String> idParamNames = new HashSet<>(address.size());
        for (PathElement elt : address) {
            final String paramName = elt.getKey();
            idParams.get(paramName).set(elt.getValue());
            idParamNames.add(paramName);
        }

        boolean add = !skipLocalAdd;
        List<ModelNode> children = Collections.emptyList();
        ModelNode params = null;
        final ModelNode model = resource.getModel();
        try(Stream<String> attrs = registration.getAttributeNames(PathAddress.EMPTY_ADDRESS).stream()) {
            final Iterator<String> i = attrs.iterator();
            while(i.hasNext()) {
                final String attrName = i.next();
                if(!model.hasDefined(attrName)) {
                    continue;
                }
                final AttributeAccess attrAccess = registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attrName);
                if(attrAccess.getStorageType() != AttributeAccess.Storage.CONFIGURATION) {
                    continue;
                }
                final AttributeDefinition attrDef = attrAccess.getAttributeDefinition();
                if (!attrDef.isRequired()) {
                    final ModelType attrType = attrDef.getType();
                    if (attrType.equals(ModelType.OBJECT)
                            && ObjectTypeAttributeDefinition.class.isAssignableFrom(attrDef.getClass())) {
                        final ModelNode child = getObjectAttributeFeature(registration, (ObjectTypeAttributeDefinition) attrDef,
                                idParams, idParamNames, model.get(attrName));
                        if (children.isEmpty()) {
                            children = Collections.singletonList(child);
                            continue;
                        }
                        if (children.size() == 1) {
                            final List<ModelNode> tmp = children;
                            children = new ArrayList<>(2);
                            children.add(tmp.get(0));
                        }
                        children.add(child);
                        continue;
                    }
                    /* NOTE: the commented out code allows to split the list as an atomic value into a list of item features
                     * the reason it's commented out is that currently list items have no IDs and so can't be compared and merged
                    if(attrType.equals(ModelType.LIST) && ObjectListAttributeDefinition.class.isAssignableFrom(attrAccess.getAttributeDefinition().getClass())) {
                        final List<ModelNode> features = getListAttributeFeature(registration,
                            (ObjectListAttributeDefinition) attrAccess.getAttributeDefinition(), idParams, idParamNames,
                            model.get(attrName).asList());
                        if(children.isEmpty()) {
                            children = features;
                            continue;
                        }
                        children.addAll(features);
                         continue;
                     }
                     */
                }
                String paramName = attrName;
                if (idParamNames.contains(attrName) || ((PROFILE.equals(attrName) || HOST.equals(attrName)) && isSubsystem(address))) {
                    paramName = attrName + "-feature";
                }
                if(params == null) {
                    params = featureNode.get(PARAMS);
                }
                params.get(paramName).set(model.get(attrName));
                add &= true;
            }
        }

        // Allow the profile describe handler to process profile includes
        processMore(context, operation, resource, address, includeResults);
        if (add) {
            context.getResult().add(featureNode);
        }
        if(!children.isEmpty()) {
            final ModelNode result = context.getResult();
            for(ModelNode child : children) {
                result.add(child);
            }
        }
    }

    private ModelNode getObjectAttributeFeature(final ImmutableManagementResourceRegistration registration, ObjectTypeAttributeDefinition attrDef,
            ModelNode idParams, Set<String> idParamNames, ModelNode objectValue) {
        final ModelNode featureNode = new ModelNode();
        featureNode.get("spec").set(registration.getFeature() + '.' + attrDef.getName());

        featureNode.get("id").set(idParams.clone());

        final ModelNode params = featureNode.get(PARAMS);
        final AttributeDefinition[] attrs = attrDef.getValueTypes();
        for(AttributeDefinition attr : attrs) {
            String attrName = attr.getName();
            if(!objectValue.hasDefined(attrName)) {
                continue;
            }
            final ModelNode attrValue = objectValue.get(attrName);
            if(idParamNames.contains(attrName)) {
                attrName += "-feature";
            }
            params.get(attrName).set(attrValue);
        }
        return featureNode;
    }
/*
    private List<ModelNode> getListAttributeFeature(final ImmutableManagementResourceRegistration registration, ObjectListAttributeDefinition attrDef,
            ModelNode idParams, Set<String> idParamNames, List<ModelNode> list) {
        final ObjectTypeAttributeDefinition itemType = attrDef.getValueType();
        final AttributeDefinition[] attrs = itemType.getValueTypes();

        List<ModelNode> features = new ArrayList<>(list.size());
        for(ModelNode item : list) {
            final ModelNode featureNode = new ModelNode();
            featureNode.get("spec").set(registration.getFeature() + '.' + attrDef.getName());

            final ModelNode params = featureNode.get(PARAMS);
            for(Property param : idParams.asPropertyList()) {
                params.get(param.getName()).set(param.getValue());
            }

            for(AttributeDefinition attr : attrs) {
                String attrName = attr.getName();
                if(!item.hasDefined(attrName)) {
                    continue;
                }
                final ModelNode attrValue = item.get(attrName);
                if(idParamNames.contains(attrName)) {
                    attrName += "-feature";
                }
                params.get(attrName).set(attrValue);
            }
            features.add(featureNode);
        }
        return features;
    }
*/
    private static boolean isSubsystem(PathAddress address) {
        for(PathElement elt : address) {
            if(SUBSYSTEM.equals(elt.getKey())) {
                return true;
            }
        }
        return false;
    }

    private void appendWriteAttributeOperation(final PathAddress address, final OperationContext context, final Resource resource, String attribute) {
        final ImmutableManagementResourceRegistration registry = context.getRootResourceRegistration().getSubModel(address);
        final ModelNode featureNode = new ModelNode();
        featureNode.get("spec").set(registry.getFeature());
        ModelNode params = featureNode.get("id");
        for (PathElement elt : address) {
            params.get(elt.getKey()).set(elt.getValue());
        }
        params = featureNode.get(PARAMS);
        params.get(attribute).set(resource.getModel().get(attribute));
        context.getResult().add(featureNode);
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
