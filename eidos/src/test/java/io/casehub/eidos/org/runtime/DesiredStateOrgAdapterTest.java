package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.org.api.*;
import io.casehub.eidos.org.api.spi.GroupInstanceStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DesiredStateOrgAdapterTest {

    private final InMemoryGroupInstanceStore instanceStore = new InMemoryGroupInstanceStore();
    private final InMemoryOrgRegistry registry = new InMemoryOrgRegistry();
    private final DesiredStateOrgAdapter adapter = new DesiredStateOrgAdapter(instanceStore, registry);
    private final OrgGoalCompilerTest.TestGraphFactory factory = new OrgGoalCompilerTest.TestGraphFactory();

    @Test void activeGroupIsPresentNode() {
        var instance = new GroupInstance("team-1", "team-1", "t1",
            GroupLifecycleState.ACTIVE, FormationOrigin.TOP_DOWN,
            null, null, null, List.of(), List.of(), List.of(),
            Instant.now(), Instant.now(), 0);
        instanceStore.save(instance);

        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-1"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph, "t1");

        assertThat(actual.statusOf(NodeId.of("team-1"))).hasValue(NodeStatus.PRESENT);
    }

    @Test void absentGroupIsAbsentNode() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-2").name("Team 2").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-2"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph, "t1");

        assertThat(actual.statusOf(NodeId.of("team-2"))).hasValue(NodeStatus.ABSENT);
    }

    @Test void dissolvedGroupIsAbsent() {
        var instance = new GroupInstance("team-3", "team-3", "t1",
            GroupLifecycleState.DISSOLVED, FormationOrigin.TOP_DOWN,
            null, null, null, List.of(), List.of(), List.of(),
            Instant.now(), Instant.now(), 1);
        instanceStore.save(instance);

        var unit = OrganizationalUnit.builder()
            .unitId("team-3").name("Team 3").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-3"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph, "t1");

        assertThat(actual.statusOf(NodeId.of("team-3"))).hasValue(NodeStatus.ABSENT);
    }

    @Test void reorganisingGroupIsDrifted() {
        var instance = new GroupInstance("team-4", "team-4", "t1",
            GroupLifecycleState.REORGANISING, FormationOrigin.TOP_DOWN,
            null, null, null, List.of(), List.of(), List.of(),
            Instant.now(), Instant.now(), 2);
        instanceStore.save(instance);

        var unit = OrganizationalUnit.builder()
            .unitId("team-4").name("Team 4").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-4"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph, "t1");

        assertThat(actual.statusOf(NodeId.of("team-4"))).hasValue(NodeStatus.DRIFTED);
    }

    @Test void rejectedGroupIsAbsent() {
        var instance = new GroupInstance("team-5", "team-5", "t1",
            GroupLifecycleState.REJECTED, FormationOrigin.PROPOSAL,
            null, null, null, List.of(), List.of(), List.of(),
            Instant.now(), Instant.now(), 0);
        instanceStore.save(instance);

        var unit = OrganizationalUnit.builder()
            .unitId("team-5").name("Team 5").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-5"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph, "t1");

        assertThat(actual.statusOf(NodeId.of("team-5"))).hasValue(NodeStatus.ABSENT);
    }

    @Test void relationshipPresentWhenExistsInRegistry() {
        var rel = AgentRelationship.builder()
            .sourceAgentId("a").targetAgentId("b")
            .kind(RelationshipKind.SUPERVISES).tenancyId("t1").build();
        registry.addRelationship(rel);

        var node = new DesiredNode(
            NodeId.of("rel:a:b:SUPERVISES"),
            new OrgRelationshipNodeSpec(rel), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph, "t1");

        assertThat(actual.statusOf(NodeId.of("rel:a:b:SUPERVISES"))).hasValue(NodeStatus.PRESENT);
    }

    @Test void relationshipAbsentWhenMissing() {
        var rel = AgentRelationship.builder()
            .sourceAgentId("x").targetAgentId("y")
            .kind(RelationshipKind.DELEGATES_TO).tenancyId("t1").build();

        var node = new DesiredNode(
            NodeId.of("rel:x:y:DELEGATES_TO"),
            new OrgRelationshipNodeSpec(rel), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph, "t1");

        assertThat(actual.statusOf(NodeId.of("rel:x:y:DELEGATES_TO"))).hasValue(NodeStatus.ABSENT);
    }

    @Test void handledTypesIncludesBothOrgTypes() {
        assertThat(adapter.handledTypes()).containsExactlyInAnyOrder(
            OrgNodeTypes.UNIT, OrgNodeTypes.RELATIONSHIP);
    }

    // ── Test fixtures reused by other test classes ──

    static class InMemoryGroupInstanceStore implements GroupInstanceStore {
        private final Map<String, GroupInstance> store = new HashMap<>();

        @Override public void save(GroupInstance instance) {
            store.put(key(instance.instanceId(), instance.tenancyId()), instance);
        }
        @Override public Optional<GroupInstance> findById(String id, String tenancyId) {
            return Optional.ofNullable(store.get(key(id, tenancyId)));
        }
        @Override public List<GroupInstance> find(GroupInstanceQuery q) {
            return store.values().stream()
                .filter(gi -> gi.tenancyId().equals(q.tenancyId()))
                .filter(gi -> q.specId() == null || gi.specId().equals(q.specId()))
                .filter(gi -> q.state() == null || gi.state() == q.state())
                .filter(gi -> q.agentId() == null || gi.members().stream()
                    .anyMatch(m -> m.agentId().equals(q.agentId())))
                .toList();
        }
        @Override public void remove(String id, String tenancyId) { store.remove(key(id, tenancyId)); }
        private String key(String id, String tenancyId) { return tenancyId + ":" + id; }
    }

    static class InMemoryOrgRegistry implements OrgRegistry {
        private final Map<String, OrganizationalUnit> units = new HashMap<>();
        private final List<AgentRelationship> relationships = new ArrayList<>();

        @Override public void registerUnit(OrganizationalUnit u) { units.put(key(u.unitId(), u.tenancyId()), u); }
        @Override public void removeUnit(String id, String t) { units.remove(key(id, t)); }
        @Override public Optional<OrganizationalUnit> findUnit(String id, String t) {
            return Optional.ofNullable(units.get(key(id, t)));
        }
        @Override public List<OrganizationalUnit> findUnits(OrgQuery q) { return List.of(); }
        @Override public List<OrganizationalUnit> childUnits(String p, String t) { return List.of(); }
        @Override public List<OrganizationalUnit> ancestorUnits(String id, String t) { return List.of(); }
        @Override public List<OrganizationalUnit> unitsFor(String a, String t) { return List.of(); }
        @Override public List<Membership> membersOf(String id, String t) { return List.of(); }
        @Override public void addRelationship(AgentRelationship r) { relationships.add(r); }
        @Override public void removeRelationship(String s, String t, RelationshipKind k, String ten) {
            relationships.removeIf(r -> r.sourceAgentId().equals(s) && r.targetAgentId().equals(t) && r.kind() == k && r.tenancyId().equals(ten));
        }
        @Override public List<AgentRelationship> relationshipsFrom(String a, String t) {
            return relationships.stream().filter(r -> r.sourceAgentId().equals(a) && r.tenancyId().equals(t)).toList();
        }
        @Override public List<AgentRelationship> relationshipsTo(String a, String t) {
            return relationships.stream().filter(r -> r.targetAgentId().equals(a) && r.tenancyId().equals(t)).toList();
        }
        @Override public List<AgentRelationship> supervisors(String a, String t) { return List.of(); }
        @Override public List<AgentRelationship> subordinates(String a, String t) { return List.of(); }
        @Override public List<AgentRelationship> escalationPath(String a, String t) { return List.of(); }
        private String key(String id, String t) { return t + ":" + id; }
    }
}
