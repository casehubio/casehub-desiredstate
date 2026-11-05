package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.eidos.org.api.AgentRelationship;
import io.casehub.eidos.org.api.Membership;
import io.casehub.eidos.org.api.OrganizationalUnit;
import io.casehub.eidos.org.api.RelationshipKind;
import io.casehub.eidos.org.api.RelationshipScope;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrgGoalCompilerTest {

    private final OrgGoalCompiler compiler = new OrgGoalCompiler();
    private final TestGraphFactory factory = new TestGraphFactory();

    @Test void emptyGoalProducesEmptyGraph() {
        var goal = new OrgGoal(List.of(), List.of());
        var result = compiler.compile(goal, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        var graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.dependencies()).isEmpty();
    }

    @Test void unitBecomesNode() {
        var unit = OrganizationalUnit.builder()
            .unitId("rig-1").name("Rig").tenancyId("t")
            .members(List.of(new Membership("agent-1", "witness", null)))
            .build();
        var goal = new OrgGoal(List.of(new OrgUnitGoal(unit)), List.of());
        var result = compiler.compile(goal, factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).containsKey(NodeId.of("rig-1"));
        var node = graph.nodes().get(NodeId.of("rig-1"));
        assertThat(node.spec()).isInstanceOf(OrgUnitNodeSpec.class);
        assertThat(((OrgUnitNodeSpec) node.spec()).unit().unitId()).isEqualTo("rig-1");
        assertThat(node.type()).isEqualTo(OrgNodeTypes.UNIT);
    }

    @Test void parentUnitCreatesDependency() {
        var parent = OrganizationalUnit.builder()
            .unitId("cluster").name("Cluster").tenancyId("t").build();
        var child = OrganizationalUnit.builder()
            .unitId("rig-1").name("Rig").tenancyId("t").parentUnitId("cluster").build();
        var goal = new OrgGoal(List.of(new OrgUnitGoal(parent), new OrgUnitGoal(child)), List.of());
        var result = compiler.compile(goal, factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.dependencies()).contains(
            new Dependency(NodeId.of("rig-1"), NodeId.of("cluster")));
    }

    @Test void relationshipBecomesNode() {
        var rel = AgentRelationship.builder()
            .sourceAgentId("witness").targetAgentId("polecat")
            .kind(RelationshipKind.SUPERVISES).tenancyId("t").build();
        var goal = new OrgGoal(List.of(), List.of(rel));
        var result = compiler.compile(goal, factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        var expectedId = NodeId.of("rel:witness:polecat:SUPERVISES");
        assertThat(graph.nodes()).containsKey(expectedId);
        var node = graph.nodes().get(expectedId);
        assertThat(node.spec()).isInstanceOf(OrgRelationshipNodeSpec.class);
        assertThat(((OrgRelationshipNodeSpec) node.spec()).relationship().sourceAgentId())
            .isEqualTo("witness");
        assertThat(node.type()).isEqualTo(OrgNodeTypes.RELATIONSHIP);
    }

    @Test void gastownTopologyCompiles() {
        var oversight = OrganizationalUnit.builder()
            .unitId("oversight").name("Oversight").tenancyId("gastown")
            .members(List.of(
                new Membership("boot", "root-watchdog", null),
                new Membership("deacon", "cross-rig-watchdog", null)))
            .build();
        var rig = OrganizationalUnit.builder()
            .unitId("rig-alpha").name("Rig Alpha").tenancyId("gastown")
            .members(List.of(
                new Membership("witness-alpha", "witness", null),
                new Membership("polecat-1", "worker", null)))
            .build();

        var supervises = AgentRelationship.builder()
            .sourceAgentId("boot").targetAgentId("deacon")
            .kind(RelationshipKind.SUPERVISES).tenancyId("gastown").build();
        var scopedSupervises = AgentRelationship.builder()
            .sourceAgentId("deacon").targetAgentId("witness-alpha")
            .kind(RelationshipKind.SUPERVISES).tenancyId("gastown")
            .scope(new RelationshipScope("rig-monitoring", null, null)).build();
        var backup = AgentRelationship.builder()
            .sourceAgentId("polecat-1").targetAgentId("witness-alpha")
            .kind(RelationshipKind.BACKS_UP).tenancyId("gastown").build();

        var goal = new OrgGoal(
            List.of(new OrgUnitGoal(oversight), new OrgUnitGoal(rig)),
            List.of(supervises, scopedSupervises, backup));
        var result = compiler.compile(goal, factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).hasSize(5);
        assertThat(graph.dependencies()).isEmpty();
    }

    @Test void lowSelfDeterminationSetsHumanGating() {
        var unit = OrganizationalUnit.builder()
            .unitId("guarded").name("Guarded").tenancyId("t")
            .selfDetermination(0.2)
            .build();
        var goal = new OrgGoal(List.of(new OrgUnitGoal(unit)), List.of());
        var result = compiler.compile(goal, factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        var node = graph.nodes().get(NodeId.of("guarded"));
        assertThat(node.humanGating()).isEqualTo(io.casehub.desiredstate.api.HumanGating.ALL);
    }

    @Test void highSelfDeterminationNoHumanGating() {
        var unit = OrganizationalUnit.builder()
            .unitId("autonomous").name("Autonomous").tenancyId("t")
            .selfDetermination(0.8)
            .build();
        var goal = new OrgGoal(List.of(new OrgUnitGoal(unit)), List.of());
        var result = compiler.compile(goal, factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        var node = graph.nodes().get(NodeId.of("autonomous"));
        assertThat(node.humanGating()).isEqualTo(io.casehub.desiredstate.api.HumanGating.NONE);
    }

    @Test void boundarySelfDeterminationAtThreshold() {
        var unit = OrganizationalUnit.builder()
            .unitId("boundary").name("Boundary").tenancyId("t")
            .selfDetermination(0.3)
            .build();
        var goal = new OrgGoal(List.of(new OrgUnitGoal(unit)), List.of());
        var result = compiler.compile(goal, factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        var node = graph.nodes().get(NodeId.of("boundary"));
        assertThat(node.humanGating()).isEqualTo(io.casehub.desiredstate.api.HumanGating.NONE);
    }

    @Test void relationshipNodeIdIsStable() {
        var rel = AgentRelationship.builder()
            .sourceAgentId("a").targetAgentId("b")
            .kind(RelationshipKind.DELEGATES_TO).tenancyId("t").build();
        assertThat(OrgGoalCompiler.relationshipNodeId(rel))
            .isEqualTo("rel:a:b:DELEGATES_TO");
    }

    static class TestGraphFactory implements DesiredStateGraphFactory {
        @Override public DesiredStateGraph empty() {
            return new TestGraph(Map.of(), Set.of());
        }
        @Override public DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps) {
            var map = new LinkedHashMap<NodeId, DesiredNode>();
            for (var n : nodes) map.put(n.id(), n);
            return new TestGraph(map, new LinkedHashSet<>(deps));
        }
    }

    record TestGraph(Map<NodeId, DesiredNode> nodes, Set<Dependency> dependencies) implements DesiredStateGraph {
        @Override public Set<NodeId> dependenciesOf(NodeId node) { return Set.of(); }
        @Override public Set<NodeId> dependentsOf(NodeId node) { return Set.of(); }
        @Override public Set<NodeId> roots() { return Set.of(); }
        @Override public Set<NodeId> leaves() { return Set.of(); }
        @Override public int version() { return 0; }
        @Override public boolean isEmpty() { return nodes.isEmpty(); }
        @Override public DesiredStateGraph withNode(DesiredNode node) { throw new UnsupportedOperationException(); }
        @Override public DesiredStateGraph withoutNode(NodeId id) { throw new UnsupportedOperationException(); }
        @Override public DesiredStateGraph withDependency(Dependency dep) { throw new UnsupportedOperationException(); }
        @Override public DesiredStateGraph withoutDependency(Dependency dep) { throw new UnsupportedOperationException(); }
        @Override public DesiredStateGraph withMutation(io.casehub.desiredstate.api.GraphMutation m) { throw new UnsupportedOperationException(); }
        @Override public DesiredStateGraph overlay(DesiredStateGraph other) { throw new UnsupportedOperationException(); }
        @Override public DesiredStateGraph connect(DesiredStateGraph other) { throw new UnsupportedOperationException(); }
    }
}
