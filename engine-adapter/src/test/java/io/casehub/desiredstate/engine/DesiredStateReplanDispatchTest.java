package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.desiredstate.runtime.LifecycleManager;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.desiredstate.runtime.SituationRecompilerEngine;
import io.casehub.engine.flow.CallableDispatchRegistry;
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

    private LifecycleManager mockLifecycleManager;
    private ReconciliationLoop mockLoop;
    private SituationRecompiler mockRecompiler;
    private DesiredStateGraphFactory mockFactory;
    private CallableDispatchRegistry registry;
    private DesiredStateReplanDispatch dispatch;

    private DesiredStateGraph currentGraph;
    private DesiredStateGraph newGraph;
    private CompilationResult capturedResult;

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
            new DesiredNode(NodeId.of("fallback-1"), new TestNodeSpec("fallback config"), HumanGating.NONE));

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

        mockLifecycleManager = new LifecycleManager(mockLoop) {
            @Override
            public void updateDesired(String tenancyId, CompilationResult result) {
                capturedResult = result;
                super.updateDesired(tenancyId, result);
            }
        };

        mockRecompiler = (tid, current, actual, situation, factory) -> Optional.of(CompilationResult.single(newGraph));
        SituationRecompilerEngine engine = new SituationRecompilerEngine(java.util.List.of(mockRecompiler));

        ActualStateAdapterRouter stubRouter = new ActualStateAdapterRouter() {
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) { return new ActualState(Map.of()); }
            @Override public java.util.Set<NodeType> allHandledTypes() { return java.util.Set.of(); }
        };

        registry = new CallableDispatchRegistry();
        dispatch = new DesiredStateReplanDispatch(mockLifecycleManager, mockLoop, engine, mockFactory, registry, stubRouter);
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
        assertThat(capturedResult).isNotNull();
        assertThat(capturedResult).isInstanceOf(CompilationResult.SingleGraph.class);
        assertThat(currentGraph).isSameAs(newGraph);
    }

    @Test
    void shouldReturnNoChangeWhenRecompilerReturnsEmpty() throws Exception {
        SituationRecompiler emptyRecompiler = (tid, current, actual, situation, factory) -> Optional.empty();
        SituationRecompilerEngine emptyEngine = new SituationRecompilerEngine(java.util.List.of(emptyRecompiler));
        ActualStateAdapterRouter stubRouter = new ActualStateAdapterRouter() {
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) { return new ActualState(Map.of()); }
            @Override public java.util.Set<NodeType> allHandledTypes() { return java.util.Set.of(); }
        };
        dispatch = new DesiredStateReplanDispatch(mockLifecycleManager, mockLoop, emptyEngine, mockFactory, registry, stubRouter);

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

    @Test
    void shouldDelegateToCompositionEngineWhenActive() throws Exception {
        DesiredStateGraph composedGraph = mockFactory.empty().withNode(
                new DesiredNode(NodeId.of("composed-1"), new TestNodeSpec("composed config"), HumanGating.NONE));
        CompilationResult composedResult     = CompilationResult.single(composedGraph);
        boolean[]         handleReplanCalled = {false};

        io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine fakeEngine =
                new io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine(mockFactory) {
                    @Override
                    public boolean isActive() {return true;}

                    @Override
                    public Optional<CompilationResult> handleReplan(
                            String tenancyId, ActualState actual,
                            io.casehub.ras.api.ActiveSituation situation,
                            DesiredStateGraphFactory factory) {
                        handleReplanCalled[0] = true;
                        return Optional.of(composedResult);
                    }
                };

        jakarta.enterprise.inject.Instance<io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine> fakeInstance =
                new FakeInstance<>(fakeEngine);

        SituationRecompiler noopRecompiler = (tid, current, actual, situation, factory) -> {
            throw new AssertionError("recompilerEngine should not be called when composition is active");
        };
        SituationRecompilerEngine noopEngine = new SituationRecompilerEngine(java.util.List.of(noopRecompiler));

        ActualStateAdapterRouter stubRouter = new ActualStateAdapterRouter() {
            @Override
            public ActualState readActual(DesiredStateGraph desired, String tenancyId) {return new ActualState(Map.of());}

            @Override
            public java.util.Set<NodeType> allHandledTypes()                           {return java.util.Set.of();}
        };

        CallableDispatchRegistry localRegistry = new CallableDispatchRegistry();
        DesiredStateReplanDispatch compositionDispatch = new DesiredStateReplanDispatch(
                mockLifecycleManager, mockLoop, noopEngine, mockFactory, localRegistry, stubRouter, fakeInstance);
        compositionDispatch.register();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tenancyId", "tenant-1");
        args.put("situationId", "sit-comp");
        args.put("correlationKey", "zone-C");
        args.put("confidence", 0.9);
        args.put("evidence", Map.of());
        args.put("since", java.time.Instant.now().minusSeconds(120).toString());
        args.put("lastSignal", java.time.Instant.now().toString());
        args.put("triggerCount", 2);

        CompletableFuture<Map<String, Object>> future = compositionDispatch.replan("workflow-comp", args);
        Map<String, Object>                    result = future.get();

        assertThat(result).containsEntry("status", "REPLANNED");
        assertThat(handleReplanCalled[0]).isTrue();
    }

    @Test
    void shouldNotDelegateToCompositionEngineWhenInactive() throws Exception {
        io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine inactiveEngine =
                new io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine(mockFactory) {
                    @Override
                    public boolean isActive() {return false;}

                    @Override
                    public Optional<CompilationResult> handleReplan(
                            String tenancyId, ActualState actual,
                            io.casehub.ras.api.ActiveSituation situation,
                            DesiredStateGraphFactory factory) {
                        throw new AssertionError("should not be called when inactive");
                    }
                };

        jakarta.enterprise.inject.Instance<io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine> fakeInstance =
                new FakeInstance<>(inactiveEngine);

        SituationRecompiler       normalRecompiler = (tid, current, actual, situation, factory) -> Optional.of(CompilationResult.single(newGraph));
        SituationRecompilerEngine normalEngine     = new SituationRecompilerEngine(java.util.List.of(normalRecompiler));

        ActualStateAdapterRouter stubRouter = new ActualStateAdapterRouter() {
            @Override
            public ActualState readActual(DesiredStateGraph desired, String tenancyId) {return new ActualState(Map.of());}

            @Override
            public java.util.Set<NodeType> allHandledTypes()                           {return java.util.Set.of();}
        };

        CallableDispatchRegistry localRegistry = new CallableDispatchRegistry();
        capturedResult = null;
        DesiredStateReplanDispatch inactiveDispatch = new DesiredStateReplanDispatch(
                mockLifecycleManager, mockLoop, normalEngine, mockFactory, localRegistry, stubRouter, fakeInstance);
        inactiveDispatch.register();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tenancyId", "tenant-1");
        args.put("situationId", "sit-inactive");
        args.put("correlationKey", "zone-D");
        args.put("confidence", 0.7);
        args.put("evidence", Map.of());
        args.put("since", java.time.Instant.now().minusSeconds(60).toString());
        args.put("lastSignal", java.time.Instant.now().toString());
        args.put("triggerCount", 1);

        CompletableFuture<Map<String, Object>> future = inactiveDispatch.replan("workflow-inactive", args);
        Map<String, Object>                    result = future.get();

        assertThat(result).containsEntry("status", "REPLANNED");
        assertThat(capturedResult).isNotNull();
    }


    private record TestNodeSpec(String config) implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }

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

    private static class FakeInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final T value;

        FakeInstance(T value)                                                                                                                                            {this.value = value;}

        @Override
        public T get()                                                                                                                                                   {return value;}

        @Override
        public boolean isResolvable()                                                                                                                                    {return true;}

        @Override
        public boolean isAmbiguous()                                                                                                                                     {return false;}

        @Override
        public boolean isUnsatisfied()                                                                                                                                   {return false;}

        @Override
        public jakarta.enterprise.inject.Instance<T> select(java.lang.annotation.Annotation... qualifiers)                                                               {return this;}

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers)                               {throw new UnsupportedOperationException();}

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) {throw new UnsupportedOperationException();}

        @Override
        public void destroy(T instance)                                                                                                                                  {}

        @Override
        public jakarta.enterprise.inject.Instance.Handle<T> getHandle()                                                                                                  {throw new UnsupportedOperationException();}

        @Override
        public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<T>> handles()                                                                                {throw new UnsupportedOperationException();}

        @Override
        public java.util.stream.Stream<T> stream()                                                                                                                       {return java.util.stream.Stream.of(value);}

        @Override
        public java.util.Iterator<T> iterator()                                                                                                                          {return java.util.List.of(value).iterator();}
    }
}
