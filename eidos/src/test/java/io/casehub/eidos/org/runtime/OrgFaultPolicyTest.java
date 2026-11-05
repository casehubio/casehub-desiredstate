package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.org.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrgFaultPolicyTest {

    private final InMemoryFaultCountStore store = new InMemoryFaultCountStore();
    private final FaultPolicy policy = OrgFaultPolicy.create(store);
    private final OrgGoalCompilerTest.TestGraphFactory factory = new OrgGoalCompilerTest.TestGraphFactory();

    @Test void belowThresholdReturnsEmpty() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-1"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var event = new FaultEvent(NodeId.of("team-1"), FaultType.PROVISION_FAILED, "timeout");

        var mutations = policy.onFault("t1", event, graph, new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test void secondFaultStillBelowThreshold() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-1"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var event = new FaultEvent(NodeId.of("team-1"), FaultType.PROVISION_FAILED, "timeout");

        policy.onFault("t1", event, graph, new ActualState(Map.of()));
        var mutations = policy.onFault("t1", event, graph, new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test void atTier1ThresholdAddsReviewNode() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-1"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var event = new FaultEvent(NodeId.of("team-1"), FaultType.PROVISION_FAILED, "timeout");

        policy.onFault("t1", event, graph, new ActualState(Map.of()));
        policy.onFault("t1", event, graph, new ActualState(Map.of()));
        var mutations = policy.onFault("t1", event, graph, new ActualState(Map.of()));

        assertThat(mutations).isNotEmpty();
        var addNode = mutations.stream()
            .filter(m -> m instanceof GraphMutation.AddNode)
            .findFirst();
        assertThat(addNode).isPresent();
    }

    @Test void nonOrgFaultTypeIgnored() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-2").name("Team 2").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-2"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var event = new FaultEvent(NodeId.of("team-2"), FaultType.HUMAN_NODE_TIMEOUT, "timeout");

        for (int i = 0; i < 5; i++) {
            policy.onFault("t1", event, graph, new ActualState(Map.of()));
        }

        assertThat(store.getCount("org-faults", "t1", NodeId.of("team-2"))).isEqualTo(0);
    }

    @Test void deprovisionFailedAlsoTriggersPolicy() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-3").name("Team 3").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-3"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var event = new FaultEvent(NodeId.of("team-3"), FaultType.DEPROVISION_FAILED, "access denied");

        policy.onFault("t1", event, graph, new ActualState(Map.of()));
        policy.onFault("t1", event, graph, new ActualState(Map.of()));
        var mutations = policy.onFault("t1", event, graph, new ActualState(Map.of()));

        assertThat(mutations).isNotEmpty();
    }
}
