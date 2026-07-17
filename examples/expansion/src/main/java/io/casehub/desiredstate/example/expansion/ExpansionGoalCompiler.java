package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import java.util.ArrayList;
import java.util.List;

public class ExpansionGoalCompiler implements GoalCompiler<ExpansionGoal> {

    @Override
    public CompilationResult compile(ExpansionGoal goals, DesiredStateGraphFactory factory) {
        DesiredStateGraph buildGraph = compileBuildPhase(goals, factory);
        DesiredStateGraph defendGraph = compileDefendPhase(goals, factory);

        return CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));
    }

    private DesiredStateGraph compileBuildPhase(ExpansionGoal goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        // Probe is always first
        NodeId probeId = NodeId.of("probe-" + goals.locationId());
        nodes.add(new DesiredNode(probeId, ExpansionNodeTypes.PROBE,
            new ProbeSpec(goals.locationId()), HumanGating.NONE));

        NodeId prevId = probeId;
        for (String structure : goals.requiredStructures()) {
            NodeId nodeId = NodeId.of(structure + "-" + goals.locationId());
            NodeType type = resolveType(structure);
            NodeSpec spec = resolveSpec(structure, goals.locationId());
            nodes.add(new DesiredNode(nodeId, type, spec, HumanGating.NONE));
            deps.add(new Dependency(nodeId, prevId));
            prevId = nodeId;
        }

        return factory.of(nodes, deps);
    }

    private DesiredStateGraph compileDefendPhase(ExpansionGoal goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        // Carry forward nexus — it needs continuous reconciliation
        NodeId nexusId = NodeId.of("nexus-" + goals.locationId());
        nodes.add(new DesiredNode(nexusId, ExpansionNodeTypes.NEXUS,
            new NexusSpec(goals.locationId()), HumanGating.NONE));

        // Defense nodes
        NodeId patrolId = NodeId.of("patrol-" + goals.locationId());
        NodeId monitorId = NodeId.of("monitor-" + goals.locationId());
        NodeId responseId = NodeId.of("response-" + goals.locationId());

        nodes.add(new DesiredNode(patrolId, ExpansionNodeTypes.PATROL,
            new PatrolSpec(goals.locationId()), HumanGating.NONE));
        nodes.add(new DesiredNode(monitorId, ExpansionNodeTypes.MONITOR,
            new MonitorSpec(goals.locationId()), HumanGating.NONE));
        nodes.add(new DesiredNode(responseId, ExpansionNodeTypes.RESPONSE,
            new ResponseSpec(goals.locationId(), goals.defensePosture()), HumanGating.NONE));

        // patrol and monitor depend on nexus
        deps.add(new Dependency(patrolId, nexusId));
        deps.add(new Dependency(monitorId, nexusId));
        // response depends on patrol and monitor
        deps.add(new Dependency(responseId, patrolId));
        deps.add(new Dependency(responseId, monitorId));

        return factory.of(nodes, deps);
    }

    private NodeType resolveType(String structure) {
        return switch (structure) {
            case "nexus" -> ExpansionNodeTypes.NEXUS;
            case "pylon" -> ExpansionNodeTypes.PYLON;
            case "cannon" -> ExpansionNodeTypes.CANNON;
            default -> throw new IllegalArgumentException("Unknown structure: " + structure);
        };
    }

    private NodeSpec resolveSpec(String structure, String locationId) {
        return switch (structure) {
            case "nexus" -> new NexusSpec(locationId);
            case "pylon" -> new PylonSpec(locationId);
            case "cannon" -> new CannonSpec(locationId);
            default -> throw new IllegalArgumentException("Unknown structure: " + structure);
        };
    }
}
