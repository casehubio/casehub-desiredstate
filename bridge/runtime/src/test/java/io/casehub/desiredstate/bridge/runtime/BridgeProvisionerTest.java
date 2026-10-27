package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.bridge.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

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
}
