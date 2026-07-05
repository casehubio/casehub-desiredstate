package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.engine.flow.CallableDispatchRegistry;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesiredStateReplanDispatchTest {

    private ReconciliationLoop mockLoop;
    private SituationRecompiler mockRecompiler;
    private DesiredStateGraphFactory mockFactory;
    private CallableDispatchRegistry registry;
    private DesiredStateReplanDispatch dispatch;

    private DesiredStateGraph currentGraph;
    private DesiredStateGraph newGraph;

    @BeforeEach
    void setUp() {
        mockFactory = new DesiredStateGraphFactory() {
            @Override
            public DesiredStateGraph empty() {
                return new MockDesiredStateGraph();
            }

            @Override
            public DesiredStateGraph of(java.util.Collection<DesiredNode> nodes,
                                         java.util.Collection<Dependency> deps) {
                DesiredStateGraph graph = empty();
                for (DesiredNode node : nodes) {
                    graph = graph.withNode(node);
                }
                for (Dependency dep : deps) {
                    graph = graph.withDependency(dep);
                }
                return graph;
            }
        };

        currentGraph = mockFactory.empty();
        newGraph = mockFactory.empty().withNode(
            new DesiredNode(
                NodeId.of("fallback-1"),
                NodeType.of("fallback"),
                new TestNodeSpec("fallback config"),
                false));

        mockLoop = new ReconciliationLoop(null, null, null, null, null) {
            @Override
            public DesiredStateGraph getDesired(String tenancyId) {
                return currentGraph;
            }

            @Override
            public void updateDesired(String tenancyId, DesiredStateGraph desired) {
                // Capture the update
                currentGraph = desired;
            }
        };

        mockRecompiler = (current, situation, factory) -> Optional.of(newGraph);

        registry = new CallableDispatchRegistry();
        dispatch = new DesiredStateReplanDispatch(mockLoop, mockRecompiler, mockFactory, registry);
        dispatch.register();
    }

    @Test
    void shouldRegisterReplanDispatch() {
        assertThat(registry.get("desiredstate:replan")).isNotNull();
    }

    @Test
    void shouldCallRecompilerAndUpdateDesired() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tenancyId", "tenant-1");
        args.put("situationId", "sit-1");
        args.put("correlationKey", "zone-A");
        args.put("confidence", 0.95);
        args.put("evidence", Map.of("nodeId", "node-123"));
        args.put("since", Instant.now().minusSeconds(300).toString());
        args.put("lastSignal", Instant.now().toString());
        args.put("triggerCount", 5);

        CompletableFuture<Map<String, Object>> future = dispatch.replan("workflow-1", args);
        Map<String, Object> result = future.get();

        assertThat(result).containsEntry("status", "REPLANNED");
        assertThat(result).containsEntry("situationId", "sit-1");
        assertThat(currentGraph).isSameAs(newGraph);
    }

    @Test
    void shouldReturnNoChangeWhenRecompilerReturnsEmpty() throws Exception {
        SituationRecompiler emptyRecompiler = (current, situation, factory) -> Optional.empty();
        dispatch = new DesiredStateReplanDispatch(mockLoop, emptyRecompiler, mockFactory, registry);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tenancyId", "tenant-1");
        args.put("situationId", "sit-2");
        args.put("correlationKey", "zone-B");
        args.put("confidence", 0.8);
        args.put("evidence", Map.of());
        args.put("since", Instant.now().minusSeconds(60).toString());
        args.put("lastSignal", Instant.now().toString());
        args.put("triggerCount", 1);

        CompletableFuture<Map<String, Object>> future = dispatch.replan("workflow-2", args);
        Map<String, Object> result = future.get();

        assertThat(result).containsEntry("status", "NO_CHANGE");
        assertThat(result).containsEntry("situationId", "sit-2");
    }

    @Test
    void shouldFailWhenMissingRequiredArg() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tenancyId", "tenant-1");
        // Missing situationId

        CompletableFuture<Map<String, Object>> future = dispatch.replan("workflow-3", args);

        assertThatThrownBy(future::get)
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required arg");
    }

    private record TestNodeSpec(String config) implements NodeSpec {}

    private static class MockDesiredStateGraph implements DesiredStateGraph {
        private final Map<NodeId, DesiredNode> nodes = new LinkedHashMap<>();
        private final java.util.Set<Dependency> dependencies = new java.util.LinkedHashSet<>();

        @Override
        public Map<NodeId, DesiredNode> nodes() {
            return Map.copyOf(nodes);
        }

        @Override
        public java.util.Set<Dependency> dependencies() {
            return java.util.Set.copyOf(dependencies);
        }

        @Override
        public java.util.Set<NodeId> dependenciesOf(NodeId node) {
            return java.util.Set.of();
        }

        @Override
        public java.util.Set<NodeId> dependentsOf(NodeId node) {
            return java.util.Set.of();
        }

        @Override
        public java.util.Set<NodeId> roots() {
            return java.util.Set.of();
        }

        @Override
        public java.util.Set<NodeId> leaves() {
            return java.util.Set.of();
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return nodes.isEmpty();
        }

        @Override
        public DesiredStateGraph withNode(DesiredNode node) {
            MockDesiredStateGraph copy = new MockDesiredStateGraph();
            copy.nodes.putAll(this.nodes);
            copy.dependencies.addAll(this.dependencies);
            copy.nodes.put(node.id(), node);
            return copy;
        }

        @Override
        public DesiredStateGraph withoutNode(NodeId id) {
            MockDesiredStateGraph copy = new MockDesiredStateGraph();
            copy.nodes.putAll(this.nodes);
            copy.dependencies.addAll(this.dependencies);
            copy.nodes.remove(id);
            return copy;
        }

        @Override
        public DesiredStateGraph withDependency(Dependency dep) {
            MockDesiredStateGraph copy = new MockDesiredStateGraph();
            copy.nodes.putAll(this.nodes);
            copy.dependencies.addAll(this.dependencies);
            copy.dependencies.add(dep);
            return copy;
        }

        @Override
        public DesiredStateGraph withoutDependency(Dependency dep) {
            MockDesiredStateGraph copy = new MockDesiredStateGraph();
            copy.nodes.putAll(this.nodes);
            copy.dependencies.addAll(this.dependencies);
            copy.dependencies.remove(dep);
            return copy;
        }

        @Override
        public DesiredStateGraph withMutation(GraphMutation mutation) {
            return this;
        }

        @Override
        public DesiredStateGraph overlay(DesiredStateGraph other) {
            return this;
        }

        @Override
        public DesiredStateGraph connect(DesiredStateGraph other) {
            return this;
        }
    }
}
