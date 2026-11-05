package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.eidos.org.api.AgentRelationship;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;

@ApplicationScoped
public class OrgGoalCompiler implements GoalCompiler<OrgGoal> {

    @Override
    public CompilationResult compile(OrgGoal goal, DesiredStateGraphFactory factory) {
        var nodes = new ArrayList<DesiredNode>();
        var deps = new ArrayList<Dependency>();

        for (var unitGoal : goal.unitGoals()) {
            var unit = unitGoal.unit();
            var nodeId = NodeId.of(unit.unitId());
            var gating = unit.selfDetermination() < 0.3 ? HumanGating.ALL : HumanGating.NONE;
            var spec = new OrgUnitNodeSpec(unit, unitGoal.desiredMembers(),
                unitGoal.desiredRoles(), unitGoal.desiredMissions());
            nodes.add(new DesiredNode(nodeId, spec, gating));
            if (unit.parentUnitId() != null) {
                deps.add(new Dependency(nodeId, NodeId.of(unit.parentUnitId())));
            }
        }

        for (var rel : goal.relationships()) {
            var nodeId = NodeId.of(relationshipNodeId(rel));
            nodes.add(new DesiredNode(nodeId, new OrgRelationshipNodeSpec(rel), HumanGating.NONE));
        }

        return CompilationResult.single(factory.of(nodes, deps));
    }

    static String relationshipNodeId(AgentRelationship rel) {
        return "rel:" + rel.sourceAgentId() + ":" + rel.targetAgentId() + ":" + rel.kind().name();
    }
}
