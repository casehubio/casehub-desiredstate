package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.org.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrgNodeProvisionerTest {

    private final DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore instanceStore =
        new DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore();
    private final DesiredStateOrgAdapterTest.InMemoryOrgRegistry registry =
        new DesiredStateOrgAdapterTest.InMemoryOrgRegistry();
    private final NoOpOrgChannelCreator channelCreator = new NoOpOrgChannelCreator();
    private final OrgNodeProvisioner provisioner =
        new OrgNodeProvisioner(instanceStore, registry, channelCreator);
    private final OrgGoalCompilerTest.TestGraphFactory factory = new OrgGoalCompilerTest.TestGraphFactory();

    @Test void provisionCreatesGroupInstanceAndRegistersUnit() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1")
            .selfDetermination(0.5).build();
        var node = new DesiredNode(NodeId.of("team-1"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var ctx = new ProvisionContext("t1", graph);

        var result = provisioner.provision(node, ctx);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(instanceStore.findById("team-1", "t1")).isPresent();
        assertThat(instanceStore.findById("team-1", "t1").get().state())
            .isEqualTo(GroupLifecycleState.ACTIVE);
        assertThat(registry.findUnit("team-1", "t1")).isPresent();
    }

    @Test void provisionSeedsMembers() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-m").name("Team M").tenancyId("t1")
            .members(List.of(
                new Membership("agent-a", "worker", null),
                new Membership("agent-b", "lead", null)))
            .build();
        var node = new DesiredNode(NodeId.of("team-m"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());

        provisioner.provision(node, new ProvisionContext("t1", graph));

        var gi = instanceStore.findById("team-m", "t1").orElseThrow();
        assertThat(gi.members()).hasSize(2);
        assertThat(gi.members().stream().map(ActualMembership::agentId))
            .containsExactlyInAnyOrder("agent-a", "agent-b");
    }

    @Test void provisionAlreadyActiveReturnsAlreadyConverged() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-2").name("Team 2").tenancyId("t1")
            .selfDetermination(0.5).build();
        var node = new DesiredNode(NodeId.of("team-2"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var ctx = new ProvisionContext("t1", graph);

        provisioner.provision(node, ctx);
        var result = provisioner.provision(node, ctx);

        assertThat(result).isInstanceOf(ProvisionResult.AlreadyConverged.class);
    }

    @Test void deprovisionDissolves() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-3").name("Team 3").tenancyId("t1")
            .selfDetermination(0.5).build();
        var node = new DesiredNode(NodeId.of("team-3"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        provisioner.provision(node, new ProvisionContext("t1", graph));

        var result = provisioner.deprovision(node, new DeprovisionContext("t1", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(instanceStore.findById("team-3", "t1"))
            .hasValueSatisfying(gi -> assertThat(gi.state()).isEqualTo(GroupLifecycleState.DISSOLVED));
    }

    @Test void deprovisionRemovesUnitFromRegistry() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-4").name("Team 4").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-4"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        provisioner.provision(node, new ProvisionContext("t1", graph));

        provisioner.deprovision(node, new DeprovisionContext("t1", graph));

        assertThat(registry.findUnit("team-4", "t1")).isEmpty();
    }

    @Test void handledTypesContainsBothOrgTypes() {
        assertThat(provisioner.handledTypes()).containsExactlyInAnyOrder(
            OrgNodeTypes.UNIT, OrgNodeTypes.RELATIONSHIP);
    }

    @Test void provisionRelationshipRegistersIt() {
        var rel = AgentRelationship.builder()
            .sourceAgentId("a").targetAgentId("b")
            .kind(RelationshipKind.SUPERVISES).tenancyId("t1").build();
        var node = new DesiredNode(
            NodeId.of("rel:a:b:SUPERVISES"),
            new OrgRelationshipNodeSpec(rel), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());

        var result = provisioner.provision(node, new ProvisionContext("t1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(registry.relationshipsFrom("a", "t1")).hasSize(1);
    }

    @Test void deprovisionRelationshipRemovesIt() {
        var rel = AgentRelationship.builder()
            .sourceAgentId("c").targetAgentId("d")
            .kind(RelationshipKind.DELEGATES_TO).tenancyId("t1").build();
        var node = new DesiredNode(
            NodeId.of("rel:c:d:DELEGATES_TO"),
            new OrgRelationshipNodeSpec(rel), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        provisioner.provision(node, new ProvisionContext("t1", graph));

        var result = provisioner.deprovision(node, new DeprovisionContext("t1", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(registry.relationshipsFrom("c", "t1")).isEmpty();
    }

    @Test void deprovisionNonExistentUnitSucceeds() {
        var unit = OrganizationalUnit.builder()
            .unitId("missing").name("Missing").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("missing"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());

        var result = provisioner.deprovision(node, new DeprovisionContext("t1", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }
}
