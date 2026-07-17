package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExpansionDomainTest {

    private ExpansionWorld world;
    private ExpansionProvisioner provisioner;
    private ExpansionActualStateAdapter adapter;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        world = new ExpansionWorld();
        provisioner = new ExpansionProvisioner(world);
        adapter = new ExpansionActualStateAdapter(world);
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void handledTypes_allSeven() {
        assertThat(provisioner.handledTypes()).containsExactlyInAnyOrder(
            ExpansionNodeTypes.PROBE,
            ExpansionNodeTypes.NEXUS,
            ExpansionNodeTypes.PYLON,
            ExpansionNodeTypes.CANNON,
            ExpansionNodeTypes.PATROL,
            ExpansionNodeTypes.MONITOR,
            ExpansionNodeTypes.RESPONSE
        );
    }

    @Test
    void provisionProbe_worldShowsBuilt_adapterReturnsPresent() {
        var probeNode = new DesiredNode(NodeId.of("probe-1"), ExpansionNodeTypes.PROBE,
            new ProbeSpec("loc-1"), HumanGating.NONE);
        var graph = factory.of(List.of(probeNode), List.of());
        var ctx = new ProvisionContext("test", graph);

        var result = provisioner.provision(probeNode, ctx);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(world.state(probeNode.id())).isEqualTo(ExpansionWorld.StructureState.BUILT);

        var actual = adapter.readActual(graph, "test");
        assertThat(actual.statusOf(probeNode.id()).orElseThrow()).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void provisionNexus_worldShowsBuilt() {
        var nexusNode = new DesiredNode(NodeId.of("nexus-1"), ExpansionNodeTypes.NEXUS,
            new NexusSpec("loc-1"), HumanGating.NONE);
        var graph = factory.of(List.of(nexusNode), List.of());
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(nexusNode, ctx);

        assertThat(world.state(nexusNode.id())).isEqualTo(ExpansionWorld.StructureState.BUILT);
    }

    @Test
    void provisionPylon_worldShowsBuilt() {
        var pylonNode = new DesiredNode(NodeId.of("pylon-1"), ExpansionNodeTypes.PYLON,
            new PylonSpec("loc-1"), HumanGating.NONE);
        var graph = factory.of(List.of(pylonNode), List.of());
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(pylonNode, ctx);

        assertThat(world.state(pylonNode.id())).isEqualTo(ExpansionWorld.StructureState.BUILT);
    }

    @Test
    void provisionCannon_worldShowsBuilt() {
        var cannonNode = new DesiredNode(NodeId.of("cannon-1"), ExpansionNodeTypes.CANNON,
            new CannonSpec("loc-1"), HumanGating.NONE);
        var graph = factory.of(List.of(cannonNode), List.of());
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(cannonNode, ctx);

        assertThat(world.state(cannonNode.id())).isEqualTo(ExpansionWorld.StructureState.BUILT);
    }

    @Test
    void provisionPatrol_worldShowsPatrolling() {
        var patrolNode = new DesiredNode(NodeId.of("patrol-1"), ExpansionNodeTypes.PATROL,
            new PatrolSpec("loc-1"), HumanGating.NONE);
        var graph = factory.of(List.of(patrolNode), List.of());
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(patrolNode, ctx);

        assertThat(world.state(patrolNode.id())).isEqualTo(ExpansionWorld.StructureState.PATROLLING);

        var actual = adapter.readActual(graph, "test");
        assertThat(actual.statusOf(patrolNode.id()).orElseThrow()).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void provisionMonitor_worldShowsMonitoring() {
        var monitorNode = new DesiredNode(NodeId.of("monitor-1"), ExpansionNodeTypes.MONITOR,
            new MonitorSpec("loc-1"), HumanGating.NONE);
        var graph = factory.of(List.of(monitorNode), List.of());
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(monitorNode, ctx);

        assertThat(world.state(monitorNode.id())).isEqualTo(ExpansionWorld.StructureState.MONITORING);

        var actual = adapter.readActual(graph, "test");
        assertThat(actual.statusOf(monitorNode.id()).orElseThrow()).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void provisionResponse_worldShowsResponding() {
        var responseNode = new DesiredNode(NodeId.of("response-1"), ExpansionNodeTypes.RESPONSE,
            new ResponseSpec("loc-1", DefensePosture.FORTIFY), HumanGating.NONE);
        var graph = factory.of(List.of(responseNode), List.of());
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(responseNode, ctx);

        assertThat(world.state(responseNode.id())).isEqualTo(ExpansionWorld.StructureState.RESPONDING);

        var actual = adapter.readActual(graph, "test");
        assertThat(actual.statusOf(responseNode.id()).orElseThrow()).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void deprovisionNexus_worldShowsDestroyed_adapterReturnsAbsent() {
        var nexusNode = new DesiredNode(NodeId.of("nexus-1"), ExpansionNodeTypes.NEXUS,
            new NexusSpec("loc-1"), HumanGating.NONE);
        var graph = factory.of(List.of(nexusNode), List.of());
        var ctx = new ProvisionContext("test", graph);
        var dctx = new DeprovisionContext("test", graph);

        provisioner.provision(nexusNode, ctx);
        var result = provisioner.deprovision(nexusNode, dctx);

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(world.state(nexusNode.id())).isEqualTo(ExpansionWorld.StructureState.DESTROYED);

        var actual = adapter.readActual(graph, "test");
        assertThat(actual.statusOf(nexusNode.id()).orElseThrow()).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void expansionGoal_withDefensePosture() {
        var goal = new ExpansionGoal("loc-1", List.of("probe", "nexus"), DefensePosture.PATROL);
        var modified = goal.withDefensePosture(DefensePosture.FORTIFY);

        assertThat(modified.defensePosture()).isEqualTo(DefensePosture.FORTIFY);
        assertThat(modified.locationId()).isEqualTo("loc-1");
        assertThat(modified.requiredStructures()).containsExactly("probe", "nexus");
    }
}
