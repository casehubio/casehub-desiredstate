package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.bridge.*;
import io.casehub.desiredstate.bridge.testing.MockBridgeProvider;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BridgeActualStateAdapterTest {

    private MockBridgeProvider provider;
    private BridgeActualStateAdapter adapter;
    private BridgeDescriptor descriptor;
    private final DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    @BeforeEach
    void setUp() {
        provider = new MockBridgeProvider("test-provider", "test_resource", "test_bucket");
        descriptor = BridgeManifestParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));
        adapter = new BridgeActualStateAdapter(descriptor, provider);
    }

    private DesiredNode bridgeNode(String id, String type) {
        return new DesiredNode(NodeId.of(id),
            new BridgeNodeSpec(NodeType.of(type), Map.of()), HumanGating.NONE);
    }

    @Test
    void handledTypesMatchDescriptor() {
        assertEquals(descriptor.handledTypes(), adapter.handledTypes());
    }

    @Test
    void mapsFoundStateViaManifest() {
        provider.setReadResult(new BridgeStateResult.Found(Map.of("status", "active")));
        var node = bridgeNode("r1", "test_resource");
        var graph = graphFactory.of(Set.of(node), Set.of());
        var actual = adapter.readActual(graph, "t1");
        assertEquals(Optional.of(NodeStatus.PRESENT), actual.statusOf(NodeId.of("r1")));
    }

    @Test
    void mapsNotFoundToAbsent() {
        provider.setReadResult(new BridgeStateResult.NotFound());
        var node = bridgeNode("r1", "test_resource");
        var graph = graphFactory.of(Set.of(node), Set.of());
        var actual = adapter.readActual(graph, "t1");
        assertEquals(Optional.of(NodeStatus.ABSENT), actual.statusOf(NodeId.of("r1")));
    }

    @Test
    void mapsErrorToUnknown() {
        provider.setReadResult(new BridgeStateResult.Error("timeout"));
        var node = bridgeNode("r1", "test_resource");
        var graph = graphFactory.of(Set.of(node), Set.of());
        var actual = adapter.readActual(graph, "t1");
        assertEquals(Optional.of(NodeStatus.UNKNOWN), actual.statusOf(NodeId.of("r1")));
    }

    @Test
    void usesDefaultStateMappingWhenKeyNotFound() {
        provider.setReadResult(new BridgeStateResult.Found(Map.of("status", "unknown-state")));
        var node = bridgeNode("r1", "test_resource");
        var graph = graphFactory.of(Set.of(node), Set.of());
        var actual = adapter.readActual(graph, "t1");
        assertEquals(Optional.of(NodeStatus.DRIFTED), actual.statusOf(NodeId.of("r1")));
    }
}
