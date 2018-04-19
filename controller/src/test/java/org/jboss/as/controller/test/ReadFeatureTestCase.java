package org.jboss.as.controller.test;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.ModelOnlyWriteAttributeHandler;
import org.jboss.as.controller.NoopOperationStepHandler;
import org.jboss.as.controller.ObjectListAttributeDefinition;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.operations.global.ReadFeatureHandler;
import org.jboss.as.controller.registry.AliasEntry;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ANNOTATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CHILDREN;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEFAULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FEATURE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FEATURE_REFERENCE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INCLUDE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_FEATURE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RECURSIVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REQUIRES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.controller.operations.global.ReadFeatureHandler.ADDRESS_PARAMETERS;
import static org.jboss.as.controller.operations.global.ReadFeatureHandler.FEATURE_ID;
import static org.jboss.as.controller.operations.global.ReadFeatureHandler.OPERATION_PARAMETERS;
import static org.jboss.as.controller.operations.global.ReadFeatureHandler.OPERATION_PARAMETERS_MAPPING;
import static org.jboss.as.controller.operations.global.ReadFeatureHandler.PARAMETERS;

/**
 *  The test registers extension containing a subsystem, and several sub-resources with attributes:
 *
 *  /extension=testextension
 *
 * TODO: automatic param mapping "test" => "test-feature" can conflict with already existing attribute "test-feature"
 * TODO:
 *  - CheckResourceAccessHandler
 *  - ReadFeatureAssemblyHandler
 *  - ReadFeatureAccessControlContext
 */
public class ReadFeatureTestCase extends AbstractControllerTestBase {

    private static final String TEST_EXTENSION = "org.wildfly.testextension";
    private static final String TEST_SUBSYSTEM = "testsubsystem";
    private static final String RESOURCE = "resource";
    private static final String STORAGE_RESOURCE = "storage-resource";
    private static final String RUNTIME_RESOURCE = "runtime-resource";
    private static final String NON_FEATURE_RESOURCE = "non-feature-resource";
    private static final String TEST = "test";
    private static final String RESOURCE_CAPABILITY = "resource-capability";
    private static final String DEPENDENT_RESOURCE_CAPABILITY = "dependent-resource-capability";
    private static final String ROOT_CAPABILITY = "root-capability";

    private static final AttributeDefinition OPTIONAL_ATTRIBUTE =
            new SimpleAttributeDefinitionBuilder("optional-attr", ModelType.INT, true)
                    .setCapabilityReference(ROOT_CAPABILITY)
                    .build();
    private static final AttributeDefinition MANDATORY_ATTRIBUTE =
            new SimpleAttributeDefinitionBuilder("mandatory-attr", ModelType.INT)
                    .setCapabilityReference("resource-capability")
                    .addArbitraryDescriptor(FEATURE_REFERENCE, new ModelNode(true))
                    .build();
    private static final AttributeDefinition READ_ONLY_ATTRIBUTE =
            new SimpleAttributeDefinitionBuilder("read-only-attr", ModelType.INT).build();
    private static final AttributeDefinition PROFILE_ATTRIBUTE =
            new SimpleAttributeDefinitionBuilder(PROFILE, ModelType.INT).build();
    private static final AttributeDefinition HOST_ATTRIBUTE =
            new SimpleAttributeDefinitionBuilder(HOST, ModelType.INT).build();
    private static final ObjectTypeAttributeDefinition OBJECT_ATTRIBUTE =
            ObjectTypeAttributeDefinition.Builder.of("object-attr", MANDATORY_ATTRIBUTE, OPTIONAL_ATTRIBUTE).build();
    private static final ObjectListAttributeDefinition LIST_ATTRIBUTE =
            ObjectListAttributeDefinition.Builder.of("list-attr", OBJECT_ATTRIBUTE).build();

    private ManagementResourceRegistration registration;

    @Override
    public void setupController() throws InterruptedException {
        super.setupController();
        // register read-feature op
        // can't do this in #initModel() because `capabilityRegistry` is not available at that stage
        registration.registerOperationHandler(ReadFeatureHandler.DEFINITION, ReadFeatureHandler.getInstance(capabilityRegistry), true);
    }

    // TODO: remove
    @Test
    public void testPrintRootRecursively() throws OperationFailedException {
        readFeature(PathAddress.EMPTY_ADDRESS, true);
    }

    /**
     * Reads subsystem that has sub-resources, but no attributes. Subsystem provides a capability.
     *
     * Expectations:
     *
     * - feature-id param,
     * - param for extension which contains this subsystem,
     * - provided capability is listed in 'provides',
     * - 'refs' contain {feature = "extension", include = true},
     * - packages contain "[extension name].main".
     */
    @Test
    public void testSubsystem() throws OperationFailedException {
        PathAddress address = PathAddress.pathAddress(SUBSYSTEM, TEST_SUBSYSTEM);
        ModelNode result = readFeature(address);

        ModelNode feature = result.require(FEATURE);
        Assert.assertEquals(serializeAddress(address), feature.require(NAME).asString());

        // check params
        Map<String, ModelNode> params = extractParamsToMap(feature);
        Assert.assertEquals(2, params.size());
        assertFeatureIdParam(SUBSYSTEM, TEST_SUBSYSTEM, params); // path parameter
        assertParamWithDefault(EXTENSION, TEST_EXTENSION, params); // referencing extension

        // check provides
        ModelNode provides = feature.require("provides");
        Assert.assertEquals(1, provides.asList().size());
        Assert.assertEquals(TEST_EXTENSION, provides.get(0).asString());

        // check ref on extension
        Assert.assertEquals(1, feature.require("refs").asList().size());
        Assert.assertEquals("extension", feature.require("refs").require(0).require(FEATURE).asString());
        Assert.assertTrue(feature.require("refs").require(0).require(INCLUDE).asBoolean());

        // check packages
        ModelNode packages = feature.require("packages");
        Assert.assertEquals(1, packages.asList().size());
        Assert.assertEquals(TEST_EXTENSION + ".main", packages.get(0).require("package").asString());
    }

    /**
     * Reads feature on storage resource that:
     *
     * - has add handler,
     * - contains simple storage and runtime attributes, and complex (list and object) attributes,
     * - provides a capability,
     * - has attributes that reference capabilities,
     * - has a requirement on a capability. TODO: investigate more
     *
     * Expectations:
     *
     * - annotation for 'add' operation (address parameters, operation parameters),
     * - feature-id params (subsystem, resource),
     * - params for writeable attributes,
     * - TODO: referenced by subsystem, ? and ?,
     * - complex attributes are described,
     * - provided capability is listed in 'provides',
     * - capabilities referenced by attributes are listed in 'requires',
     * - capability required by a resource is listed in 'requires'.
     */
    @Test
    public void testStorageResource() throws OperationFailedException {
        PathAddress address = PathAddress.pathAddress(SUBSYSTEM, TEST_SUBSYSTEM).append(RESOURCE, STORAGE_RESOURCE);
        ModelNode result = readFeature(address);

        ModelNode feature = result.require(FEATURE);
        Assert.assertEquals(serializeAddress(address), feature.require(NAME).asString());

        // check annotation
        ModelNode annotation = feature.require(ANNOTATION);
        Assert.assertEquals(ADD, annotation.require(NAME).asString());
        Assert.assertArrayEquals(new String[] {SUBSYSTEM, RESOURCE}, annotation.require(ADDRESS_PARAMETERS).asString().split(","));
        Assert.assertArrayEquals(new String[] {"optional-attr", "mandatory-attr"}, annotation.require(OPERATION_PARAMETERS).asString().split(","));

        // check params
        Map<String, ModelNode> params = extractParamsToMap(feature);
        assertFeatureIdParam(SUBSYSTEM, TEST_SUBSYSTEM, params);
        assertFeatureIdParam(RESOURCE, STORAGE_RESOURCE, params);
        // optional-attr is nillable
        Assert.assertTrue(params.containsKey("optional-attr"));
        Assert.assertTrue(params.get("optional-attr").require("nillable").asBoolean());
        // mandatory-attr is not nillable
        Assert.assertTrue(params.containsKey("mandatory-attr"));
        Assert.assertFalse(params.get("mandatory-attr").has("nillable"));
        // read-only attr not present
        Assert.assertFalse(params.containsKey("read-only-attr"));

        // check ref on subsystem
        ModelNode refs = feature.require("refs");
//        Assert.assertEquals(1, refs.asList().size()); // TODO: 3
        Assert.assertEquals(SUBSYSTEM + "." + TEST_SUBSYSTEM, refs.get(0).require(FEATURE).asString());
        // TODO: 'testsubsystem' referenced twice, forgot why

        // capabilities
        ModelNode provides = feature.require("provides");
        Assert.assertEquals(1, provides.asList().size());
        Assert.assertEquals("resource-capability." + STORAGE_RESOURCE, provides.get(0).asString());

        // complex attributes

        ModelNode children = feature.require(CHILDREN);

        ModelNode listAttr = children.require("subsystem.testsubsystem.resource.storage-resource.list-attr");
        Assert.assertEquals("subsystem.testsubsystem.resource.storage-resource.list-attr", listAttr.require(NAME).asString());
        annotation = listAttr.require(ANNOTATION);
        Assert.assertEquals("list-add", annotation.require(NAME).asString());
        Assert.assertEquals("list-attr", annotation.require("complex-attribute").asString());
        Assert.assertArrayEquals(new String[] {SUBSYSTEM, RESOURCE}, annotation.require(ADDRESS_PARAMETERS).asString().split(","));
        assertSortedArrayEquals(new String[] {"mandatory-attr", "optional-attr"}, annotation.require(OPERATION_PARAMETERS).asString().split(","));
        Assert.assertEquals("subsystem.testsubsystem.resource.storage-resource", listAttr.require("refs").asList().get(0).require(FEATURE).asString());
        params = extractParamsToMap(listAttr);
        Assert.assertTrue(params.containsKey(SUBSYSTEM));
        Assert.assertTrue(params.containsKey(RESOURCE));
        Assert.assertTrue(params.containsKey("mandatory-attr"));
        Assert.assertTrue(params.containsKey("optional-attr"));

        ModelNode objectAttr = children.require("subsystem.testsubsystem.resource.storage-resource.object-attr");
        Assert.assertEquals("subsystem.testsubsystem.resource.storage-resource.object-attr", objectAttr.require(NAME).asString());
        annotation = objectAttr.require(ANNOTATION);
        Assert.assertEquals("write-attribute", annotation.require(NAME).asString());
        Assert.assertEquals("object-attr", annotation.require("complex-attribute").asString());
        Assert.assertArrayEquals(new String[] {SUBSYSTEM, RESOURCE}, annotation.require(ADDRESS_PARAMETERS).asString().split(","));
        assertSortedArrayEquals(new String[] {"mandatory-attr", "optional-attr"}, annotation.require(OPERATION_PARAMETERS).asString().split(","));
        Assert.assertEquals("subsystem.testsubsystem.resource.storage-resource", listAttr.require("refs").asList().get(0).require(FEATURE).asString());
        params = extractParamsToMap(objectAttr);
        assertFeatureIdParam(SUBSYSTEM, TEST_SUBSYSTEM, params);
        assertFeatureIdParam(RESOURCE, STORAGE_RESOURCE, params);
        Assert.assertTrue(params.containsKey("mandatory-attr"));
        Assert.assertTrue(params.containsKey("optional-attr"));

        // requires
        ModelNode requires = feature.require(REQUIRES);
        Assert.assertTrue(requires.asList().get(0).require("optional").asBoolean());
        Assert.assertEquals(ROOT_CAPABILITY, requires.asList().get(0).require(NAME).asString());
        Assert.assertFalse(requires.asList().get(1).require("optional").asBoolean());
        Assert.assertEquals("resource-capability.$mandatory-attr", requires.asList().get(1).require(NAME).asString()); // TODO: is that correct capability name?
        // TODO: resource requirement
    }

    /**
     * Reads runtime resource and storage resource marked as setFeature(false).
     *
     * Expectations: Operation should be registered but return `undefined`.
     */
    @Test
    public void testNonFeatureResources() throws OperationFailedException {
        assertReadFeatureUndefined(PathAddress.pathAddress(SUBSYSTEM, TEST_SUBSYSTEM).append(RESOURCE, RUNTIME_RESOURCE));
        assertReadFeatureUndefined(PathAddress.pathAddress(SUBSYSTEM, TEST_SUBSYSTEM).append(RESOURCE, NON_FEATURE_RESOURCE));
    }

    /**
     * Reads resource that:
     *
     * - contains attributes with special names ('host', 'profile'),
     * - contains an attribute that conflicts with resource path element (path '_test_/storage-resource', attribute 'test'),
     * - doesn't have add handler.
     *
     * Expectations:
     *
     * - mentioned attributes need to be remapped to '*-feature'.
     * - annotation will reference 'write-attribute' operation instead of 'add' operation.
     */
    @Test
    public void testSpecialAttributeNames() throws OperationFailedException {
        PathAddress address = PathAddress.pathAddress(SUBSYSTEM, TEST_SUBSYSTEM).append(TEST, STORAGE_RESOURCE);
        ModelNode result = readFeature(address);

        ModelNode feature = result.require(FEATURE);
        Assert.assertEquals(serializeAddress(address), feature.require(NAME).asString());
        Map<String, ModelNode> params = extractParamsToMap(feature);
        assertFeatureIdParam(SUBSYSTEM, TEST_SUBSYSTEM, params);
        assertFeatureIdParam(TEST, STORAGE_RESOURCE, params); // note that parameter "test" is bound to address element
        Assert.assertTrue(params.containsKey(TEST + "-feature")); // while resource attribute "test" is renamed to "test-feature"
        Assert.assertTrue(params.containsKey(HOST + "-feature")); // likewise "host" should be renamed to "host-feature"
        Assert.assertTrue(params.containsKey(PROFILE + "-feature")); // and the same for "profile"

        // annotation
        ModelNode annotation = feature.require(ANNOTATION);
        Assert.assertArrayEquals(new String[] {SUBSYSTEM, TEST}, annotation.require(ADDRESS_PARAMETERS).asString().split(","));
        Assert.assertEquals("write-attribute", annotation.require(NAME).asString());
        // the renamed "*-feature" parameters should be mapped to their original names
        assertSortedArrayEquals(new String[] {HOST, TEST, PROFILE},
                annotation.require(OPERATION_PARAMETERS_MAPPING).asString().split(","));
        assertSortedArrayEquals(new String[] {HOST + "-feature", TEST + "-feature", PROFILE + "-feature"},
                annotation.require(OPERATION_PARAMETERS).asString().split(","));
    }

    /**
     * Reads root. Root provides a capability and contains an attribute.
     *
     * Expectations:
     *
     * - contains a param for the attribute and an id-param,
     * - contains annotation,
     * - lists the capability in "provides" section.
     */
    @Test
    public void testServerRoot() throws OperationFailedException {
        ModelNode result = readFeature(PathAddress.EMPTY_ADDRESS);

        ModelNode feature = result.require(FEATURE);
        Assert.assertEquals("server-root", feature.require(NAME).asString());
        Map<String, ModelNode> params = extractParamsToMap(feature);

        // params
        assertFeatureIdParam("server-root", "/", params);
        Assert.assertTrue(params.containsKey(NAME));

        // annotation
        ModelNode annotation = feature.require(ANNOTATION);
        Assert.assertEquals("server-root", annotation.require(ADDRESS_PARAMETERS).asString());
        Assert.assertEquals("write-attribute", annotation.require(NAME).asString());
        Assert.assertEquals("name", annotation.require(OPERATION_PARAMETERS).asString());

        // capabilities
        ModelNode provides = feature.require("provides");
        Assert.assertEquals(1, provides.asList().size());
        Assert.assertEquals(ROOT_CAPABILITY, provides.asList().get(0).asString());
    }

    /**
     * Reads a subsystem recursively.
     *
     * The resource contains:
     *
     * - two storage resources,
     * - runtime resource and non-feature storage resource.
     *
     * Expectations:
     *
     * - only the two storage resources will be listed as children,
     * - TODO: children's children will be listed.
     *
     * (The usual stuff is tested by other tests.)
     */
    @Test
    public void testStorageResourceRecursive() throws OperationFailedException {
        ModelNode result = readFeature(PathAddress.pathAddress(SUBSYSTEM, TEST_SUBSYSTEM), true);

        ModelNode feature = result.require(FEATURE);
        Assert.assertEquals(SUBSYSTEM + "." + TEST_SUBSYSTEM, feature.require(NAME).asString());

        // subsystem params
        Map<String, ModelNode> params = extractParamsToMap(feature);
        assertFeatureIdParam(SUBSYSTEM, TEST_SUBSYSTEM, params);
        assertParamWithDefault(EXTENSION, TEST_EXTENSION, params);

        // subsystem children
        ModelNode resource1 = feature.require(CHILDREN).require("subsystem.testsubsystem.test.storage-resource");
        Assert.assertEquals("subsystem.testsubsystem.test.storage-resource", resource1.get(NAME).asString());
        params = extractParamsToMap(resource1);
        assertFeatureIdParam(SUBSYSTEM, TEST_SUBSYSTEM, params);
        assertFeatureIdParam(TEST, STORAGE_RESOURCE, params);
        Assert.assertTrue(params.containsKey("host-feature"));
        Assert.assertTrue(params.containsKey("test-feature"));
        Assert.assertTrue(params.containsKey("profile-feature"));

        ModelNode resource2 = feature.require(CHILDREN).require("subsystem.testsubsystem.resource.storage-resource");
        Assert.assertEquals("subsystem.testsubsystem.resource.storage-resource", resource2.get(NAME).asString());
        params = extractParamsToMap(resource2);
        assertFeatureIdParam(SUBSYSTEM, TEST_SUBSYSTEM, params);
        assertFeatureIdParam(RESOURCE, STORAGE_RESOURCE, params);
        Assert.assertTrue(params.containsKey("optional-attr"));
        Assert.assertTrue(params.containsKey("mandatory-attr"));
    }

    /**
     * Reads alias to a storage resource.
     *
     * Expectations:
     *
     * - the target resource is read.
     */
    @Test
    public void testAliasToResource() throws OperationFailedException {
        ModelNode result = readFeature(PathAddress.pathAddress("alias", "alias-to-resource"));

        ModelNode feature = result.require(FEATURE);
        Assert.assertEquals("subsystem.testsubsystem.resource.storage-resource", feature.require(NAME).asString());
        Map<String, ModelNode> params = extractParamsToMap(feature);
        assertFeatureIdParam(SUBSYSTEM, TEST_SUBSYSTEM, params);
        assertFeatureIdParam(RESOURCE, STORAGE_RESOURCE, params);
    }

    private ModelNode readFeature(PathAddress address) throws OperationFailedException {
        return readFeature(address, false);
    }

    private ModelNode readFeature(PathAddress address, boolean recursive) throws OperationFailedException {
        ModelNode operation = createOperation(READ_FEATURE_OPERATION, address);
        operation.get(RECURSIVE).set(recursive);
        ModelNode result = executeForResult(operation);
        System.out.println(result);
        return result;
    }

    private void assertSortedArrayEquals(String[] expectedArray, String[] array) {
        Arrays.sort(expectedArray);
        Arrays.sort(array);
        Assert.assertArrayEquals(expectedArray, array);
    }

    private void assertReadFeatureUndefined(PathAddress address) throws OperationFailedException {
        ModelNode result = readFeature(address);
        ModelNode feature = result.get(FEATURE);
        Assert.assertFalse(feature.isDefined());
    }

    private void assertFeatureIdParam(String name, String defVal, Map<String, ModelNode> params) {
        Assert.assertTrue(params.containsKey(name));
        Assert.assertEquals(defVal, params.get(name).require(DEFAULT).asString());
        Assert.assertTrue(params.get(name).require(FEATURE_ID).asBoolean());
    }

    private void assertParamWithDefault(String name, String defVal, Map<String, ModelNode> params) {
        Assert.assertTrue(params.containsKey(name));
        Assert.assertEquals(defVal, params.get(name).require(DEFAULT).asString());
    }

    private Map<String, ModelNode> extractParamsToMap(ModelNode feature) {
        Assert.assertTrue(feature.has(PARAMETERS));
        HashMap<String, ModelNode> map = new HashMap<>();
        for (ModelNode param : feature.get(PARAMETERS).asList()) {
            map.put(param.require(NAME).asString(), param);
        }
        return map;
    }

    private String serializeAddress(PathAddress address) {
        StringBuilder sb = new StringBuilder();
        for (PathElement elem : address) {
            if (sb.length() != 0) {
                sb.append(".");
            }
            sb.append(elem.getKey()).append(".").append(elem.getValue());
        }
        return sb.toString();
    }

    @Override
    protected void initModel(ManagementModel managementModel) {
        registration = managementModel.getRootResourceRegistration();

        // register root attr and capability
        ModelOnlyWriteAttributeHandler writeHandler = new ModelOnlyWriteAttributeHandler();
        registration.registerReadWriteAttribute(new SimpleAttributeDefinitionBuilder(NAME, ModelType.STRING).build(), null, writeHandler);
        registration.registerCapability(RuntimeCapability.Builder.of(ROOT_CAPABILITY).build());

        // register extension with child subsystem
        Resource extensionRes = Resource.Factory.create();
        extensionRes.registerChild(PathElement.pathElement(SUBSYSTEM, TEST_SUBSYSTEM), Resource.Factory.create());
        managementModel.getRootResource().registerChild(PathElement.pathElement(EXTENSION, TEST_EXTENSION), extensionRes);

        // register subsystem
        ManagementResourceRegistration subsysRegistration =
                registration.registerSubModel(new SimpleResourceDefinition(
                        new SimpleResourceDefinition.Parameters(PathElement.pathElement(SUBSYSTEM, TEST_SUBSYSTEM),
                                NonResolvingResourceDescriptionResolver.INSTANCE)));
        subsysRegistration.registerCapability(RuntimeCapability.Builder.of("subsystem-capability").build());

        // register storage resource
        ManagementResourceRegistration storageResRegistration =
                subsysRegistration.registerSubModel(new SimpleResourceDefinition(
                        new SimpleResourceDefinition.Parameters(PathElement.pathElement(RESOURCE, STORAGE_RESOURCE),
                            NonResolvingResourceDescriptionResolver.INSTANCE)
                                .addRequirement(DEPENDENT_RESOURCE_CAPABILITY, null, "non-existent", null)));
        storageResRegistration.registerOperationHandler(
                SimpleOperationDefinitionBuilder.of(ADD, new NonResolvingResourceDescriptionResolver())
                        .addParameter(OPTIONAL_ATTRIBUTE)
                        .addParameter(MANDATORY_ATTRIBUTE)
                        .build(),
                NoopOperationStepHandler.WITHOUT_RESULT);
        storageResRegistration.registerReadWriteAttribute(OPTIONAL_ATTRIBUTE, null, writeHandler);
        storageResRegistration.registerReadWriteAttribute(MANDATORY_ATTRIBUTE, null, writeHandler);
        storageResRegistration.registerReadOnlyAttribute(READ_ONLY_ATTRIBUTE, null);
        storageResRegistration.registerReadWriteAttribute(OBJECT_ATTRIBUTE, null, writeHandler);
        storageResRegistration.registerReadWriteAttribute(LIST_ATTRIBUTE, null, writeHandler);
        storageResRegistration.registerCapability(RuntimeCapability.Builder.of(RESOURCE_CAPABILITY, true).build());

        // register runtime resource
        subsysRegistration.registerSubModel(new SimpleResourceDefinition(
                new SimpleResourceDefinition.Parameters(PathElement.pathElement(RESOURCE, RUNTIME_RESOURCE),
                        NonResolvingResourceDescriptionResolver.INSTANCE)
                        .setRuntime()));

        // register another resource that is marked as not-a-feature
        subsysRegistration.registerSubModel(new SimpleResourceDefinition(
                new SimpleResourceDefinition.Parameters(PathElement.pathElement(RESOURCE, NON_FEATURE_RESOURCE),
                        NonResolvingResourceDescriptionResolver.INSTANCE)
                        .setFeature(false)));

        // register resource "test=storage-resource" with attribute "test", so that the "test" parameter will need to be
        // remapped to "test-feature"
        ManagementResourceRegistration testResRegistration = subsysRegistration.registerSubModel(new SimpleResourceDefinition(
                new SimpleResourceDefinition.Parameters(PathElement.pathElement(TEST, STORAGE_RESOURCE),
                        NonResolvingResourceDescriptionResolver.INSTANCE)));
        testResRegistration.registerReadWriteAttribute(
                new SimpleAttributeDefinitionBuilder(TEST, ModelType.INT).build(), null, writeHandler);
//        testResRegistration.registerReadWriteAttribute(TEST_FEATURE_ATTRIBUTE, null, writeHandler);
        testResRegistration.registerReadWriteAttribute(PROFILE_ATTRIBUTE, null, writeHandler);
        testResRegistration.registerReadWriteAttribute(HOST_ATTRIBUTE, null, writeHandler);

        registration.registerAlias(PathElement.pathElement("alias", "alias-to-resource"), new AliasEntry(storageResRegistration) {
            @Override
            public PathAddress convertToTargetAddress(PathAddress aliasAddress, AliasContext aliasContext) {
                return getTargetAddress();
            }
        });

        // TODO: remove
        /*registration.registerOperationHandler(TestUtils.SETUP_OPERATION_DEF, new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                final ModelNode model = new ModelNode();

                // create a model with the resource=storage-resource child
                model.get("subsystem", "testsubsystem", "resource", "storage-resource").setEmptyObject();

                createModel(context, model);
            }
        });*/
    }

}
