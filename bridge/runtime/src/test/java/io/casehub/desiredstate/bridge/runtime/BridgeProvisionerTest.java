package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;
import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.BridgeImportResult;
import io.casehub.desiredstate.bridge.BridgeNodeSpec;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeProvisionerTest {

    private MockBridgeProvider provider;
    private BridgeProvisioner provisioner;
    private final DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    @BeforeEach
    void setUp() {
        provider = new MockBridgeProvider("test-provider", "test_resource", "test_bucket");
        var descriptor = BridgeManifestParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));
        provisioner = new BridgeProvisioner(descriptor, provider);
    }

    private DesiredNode bridgeNode(String id, String type, Map<String, Object> fields) {
        return new DesiredNode(NodeId.of(id),
            new BridgeNodeSpec(NodeType.of(type), fields), HumanGating.NONE);
    }

    @Test
    void handledTypesMatchDescriptor() {
        assertEquals(Set.of(NodeType.of("test_resource"), NodeType.of("test_bucket")),
            provisioner.handledTypes());
    }

    @Test
    void provisionDelegatesToProvider() {
        var node = bridgeNode("r1", "test_resource", Map.of("key", "value"));
        var graph = graphFactory.of(Set.of(node), Set.of());
        var ctx = new ProvisionContext("tenant-1", graph);
        var result = provisioner.provision(node, ctx);
        assertInstanceOf(ProvisionResult.Success.class, result);
        assertEquals(1, provider.applyCalls().size());
        assertEquals("test_resource", provider.applyCalls().getFirst().typeId());
    }

    @Test
    void provisionFailedMapsToProvisionResultFailed() {
        provider.setApplyResult(new BridgeResult.Failed("boom", BridgeErrorCategory.TRANSIENT));
        var node = bridgeNode("r1", "test_resource", Map.of());
        var graph = graphFactory.of(Set.of(node), Set.of());
        var result = provisioner.provision(node, new ProvisionContext("t1", graph));
        assertInstanceOf(ProvisionResult.Failed.class, result);
        assertEquals("boom", ((ProvisionResult.Failed) result).reason());
    }

    @Test
    void deprovisionDelegatesToProvider() {
        var node = bridgeNode("r1", "test_resource", Map.of());
        var graph = graphFactory.of(Set.of(node), Set.of());
        var ctx = new DeprovisionContext("tenant-1", graph);
        var result = provisioner.deprovision(node, ctx);
        assertInstanceOf(DeprovisionResult.Success.class, result);
        assertEquals(1, provider.destroyCalls().size());
    }

    @Test
    void suspendWithoutSuspendCapableReturnsFailed() {
        var node = bridgeNode("r1", "test_resource", Map.of());
        var graph = graphFactory.of(Set.of(node), Set.of());
        var result = provisioner.suspend(node, new SuspendContext("t1", graph));
        assertInstanceOf(SuspendResult.Failed.class, result);
    }

    @Test
    void supportsStatefulLifecycleIsFalseByDefault() {
        assertFalse(provisioner.supportsStatefulLifecycle());
    }

    @Test
    void provisionWithImportIdCallsImportInsteadOfApply() {
        var importProvider = new MockImportCapableBridgeProvider("test-provider", "test_resource");
        var descriptor = BridgeManifestParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));
        var stateStore        = new InMemoryBridgeOutputStateStore();
        var importProvisioner = new BridgeProvisioner(descriptor, importProvider, stateStore);

        var node   = bridgeNode("r1", "test_resource", Map.of("import", "i-abc123", "key", "value"));
        var graph  = graphFactory.of(Set.of(node), Set.of());
        var result = importProvisioner.provision(node, new ProvisionContext("tenant-1", graph));

        assertInstanceOf(ProvisionResult.Success.class, result);
        assertEquals(1, importProvider.importCalls().size());
        assertEquals("i-abc123", importProvider.importCalls().getFirst().importId());
        assertEquals(0, importProvider.applyCalls().size());
        assertTrue(stateStore.load("tenant-1", "r1").isPresent());
    }

    @Test
    void provisionWithImportIdSkipsImportWhenStateExists() {
        var importProvider = new MockImportCapableBridgeProvider("test-provider", "test_resource");
        var descriptor = BridgeManifestParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));
        var stateStore = new InMemoryBridgeOutputStateStore();
        stateStore.store("tenant-1", "r1", Map.of("id", "existing"));
        var importProvisioner = new BridgeProvisioner(descriptor, importProvider, stateStore);

        var node   = bridgeNode("r1", "test_resource", Map.of("import", "i-abc123", "key", "value"));
        var graph  = graphFactory.of(Set.of(node), Set.of());
        var result = importProvisioner.provision(node, new ProvisionContext("tenant-1", graph));

        assertInstanceOf(ProvisionResult.Success.class, result);
        assertEquals(0, importProvider.importCalls().size());
        assertEquals(1, importProvider.applyCalls().size());
    }

    @Test
    void provisionWithImportIdFallsThroughWhenProviderNotImportCapable() {
        var node   = bridgeNode("r1", "test_resource", Map.of("import", "i-abc123"));
        var graph  = graphFactory.of(Set.of(node), Set.of());
        var result = provisioner.provision(node, new ProvisionContext("tenant-1", graph));

        assertInstanceOf(ProvisionResult.Success.class, result);
        assertEquals(1, provider.applyCalls().size());
    }

    @Test
    void provisionImportFailedMapsToProvisionResultFailed() {
        var importProvider = new MockImportCapableBridgeProvider("test-provider", "test_resource");
        importProvider.setImportResult(new BridgeImportResult.Failed("resource not found"));
        var descriptor = BridgeManifestParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));
        var importProvisioner = new BridgeProvisioner(descriptor, importProvider);

        var node   = bridgeNode("r1", "test_resource", Map.of("import", "i-abc123"));
        var graph  = graphFactory.of(Set.of(node), Set.of());
        var result = importProvisioner.provision(node, new ProvisionContext("tenant-1", graph));

        assertInstanceOf(ProvisionResult.Failed.class, result);
        assertEquals("resource not found", ((ProvisionResult.Failed) result).reason());
    }

}
