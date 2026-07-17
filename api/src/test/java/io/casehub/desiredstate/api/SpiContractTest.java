package io.casehub.desiredstate.api;

import static org.assertj.core.api.Assertions.assertThat;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpiContractTest {
    record TestSpec(String name) implements NodeSpec {}

    @Test void goalCompiler_canBeImplemented() {
        DesiredStateGraph mockGraph = new DesiredStateGraph() {
            @Override public Map<NodeId, DesiredNode> nodes() { return Map.of(); }
            @Override public Set<Dependency> dependencies() { return Set.of(); }
            @Override public Set<NodeId> dependenciesOf(NodeId node) { return Set.of(); }
            @Override public Set<NodeId> dependentsOf(NodeId node) { return Set.of(); }
            @Override public Set<NodeId> roots() { return Set.of(); }
            @Override public Set<NodeId> leaves() { return Set.of(); }
            @Override public int version() { return 1; }
            @Override public boolean isEmpty() { return true; }
            @Override public DesiredStateGraph withNode(DesiredNode node) { return this; }
            @Override public DesiredStateGraph withoutNode(NodeId id) { return this; }
            @Override public DesiredStateGraph withDependency(Dependency dep) { return this; }
            @Override public DesiredStateGraph withoutDependency(Dependency dep) { return this; }
            @Override public DesiredStateGraph withMutation(GraphMutation mutation) { return this; }
            @Override public DesiredStateGraph overlay(DesiredStateGraph other) { return this; }
            @Override public DesiredStateGraph connect(DesiredStateGraph other) { return this; }
        };
        GoalCompiler<String> compiler = (goals, factory) -> CompilationResult.single(mockGraph);
        DesiredStateGraphFactory mockFactory = new DesiredStateGraphFactory() {
            @Override public DesiredStateGraph empty() { return mockGraph; }
            @Override public DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps) { return mockGraph; }
        };
        CompilationResult result = compiler.compile("test", mockFactory);
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph).isSameAs(mockGraph);
    }

    @Test void actualStateAdapter_canBeImplemented() {
        ActualStateAdapter adapter = new ActualStateAdapter() {
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                return new ActualState(Map.of());
            }
            @Override public Set<NodeType> handledTypes() {
                return Set.of(NodeType.of("test-type"));
            }
        };
        assertThat(adapter.handledTypes()).containsExactly(NodeType.of("test-type"));
        assertThat(adapter.readActual(null, "test-tenant")).isNotNull();
    }

    @Test void nodeProvisioner_canBeImplemented() {
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public Set<NodeType> handledTypes() { return Set.of(NodeType.of("test")); }
            @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) { return new ProvisionResult.Success(); }
            @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) { return new DeprovisionResult.Success(); }
        };
        var node = new DesiredNode(new NodeId("a"), new NodeType("t"), new TestSpec("x"), HumanGating.NONE);
        assertThat(provisioner.provision(node, null)).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test void nodeProvisioner_resyncInterval_defaultIsFiveMinutes() {
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public Set<NodeType> handledTypes() { return Set.of(NodeType.of("test")); }
            @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) { return new ProvisionResult.Success(); }
            @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) { return new DeprovisionResult.Success(); }
        };
        assertThat(provisioner.resyncInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test void faultPolicy_canBeImplemented() {
        FaultPolicy policy = (tid, event, graph, actual) -> List.of();
        assertThat(policy.onFault("tenant-1", null, null, null)).isEmpty();
    }

    @Test void eventSource_canBeImplemented() {
        EventSource source = () -> Multi.createFrom().empty();
        assertThat(source.stream()).isNotNull();
    }

    @Test void mergedEventSource_canBeImplemented() {
        MergedEventSource merged = () -> Multi.createFrom().empty();
        assertThat(merged.stream()).isNotNull();
    }

    @Test void transitionExecutor_canBeImplemented() {
        TransitionExecutor executor = (plan, tenancyId) -> Uni.createFrom().item(new TransitionResult(Map.of()));
        assertThat(executor.execute(null, "test-tenant").await().indefinitely()).isNotNull();
    }

    @Test void cyclicDependencyException_carriesCycle() {
        var cycle = List.of(new NodeId("a"), new NodeId("b"), new NodeId("a"));
        var ex = new CyclicDependencyException(cycle);
        assertThat(ex.getCycle()).hasSize(3);
        assertThat(ex.getMessage()).contains("a");
    }

    @Test void danglingDependencyException_carriesNodes() {
        var from = new NodeId("creature");
        var missing = new NodeId("room");
        var ex = new DanglingDependencyException(from, missing);
        assertThat(ex.getFrom()).isEqualTo(from);
        assertThat(ex.getMissingTo()).isEqualTo(missing);
    }

    @Test void conflictingMutationException_carriesBothMutations() {
        var id = new NodeId("lib");
        GraphMutation a = new GraphMutation.RemoveNode(id);
        GraphMutation b = new GraphMutation.UpdateNode(id, new DesiredNode(id, new NodeType("t"), new TestSpec("new"), HumanGating.NONE));
        var ex = new ConflictingMutationException(id, a, b);
        assertThat(ex.getNodeId()).isEqualTo(id);
        assertThat(ex.getMutationA()).isEqualTo(a);
        assertThat(ex.getMutationB()).isEqualTo(b);
    }
}
