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
        GoalCompiler<String> compiler = (goals, factory) -> factory.empty();
        DesiredStateGraphFactory mockFactory = new DesiredStateGraphFactory() {
            @Override public DesiredStateGraph empty() { return null; }
            @Override public DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps) { return null; }
        };
        assertThat(compiler.compile("test", mockFactory)).isNull();
    }

    @Test void actualStateAdapter_canBeImplemented() {
        ActualStateAdapter adapter = (desired, tenancyId) -> new ActualState(Map.of());
        assertThat(adapter.readActual(null, "test-tenant")).isNotNull();
    }

    @Test void nodeProvisioner_canBeImplemented() {
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public Set<NodeType> handledTypes() { return Set.of(NodeType.of("test")); }
            @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) { return new ProvisionResult.Success(); }
            @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) { return new DeprovisionResult.Success(); }
        };
        var node = new DesiredNode(new NodeId("a"), new NodeType("t"), new TestSpec("x"), false);
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
        FaultPolicy policy = (event, graph) -> List.of();
        assertThat(policy.onFault(null, null)).isEmpty();
    }

    @Test void eventSource_canBeImplemented() {
        EventSource source = () -> Multi.createFrom().empty();
        assertThat(source.stream()).isNotNull();
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
        GraphMutation b = new GraphMutation.UpdateNode(id, new TestSpec("new"));
        var ex = new ConflictingMutationException(id, a, b);
        assertThat(ex.getNodeId()).isEqualTo(id);
        assertThat(ex.getMutationA()).isEqualTo(a);
        assertThat(ex.getMutationB()).isEqualTo(b);
    }
}
