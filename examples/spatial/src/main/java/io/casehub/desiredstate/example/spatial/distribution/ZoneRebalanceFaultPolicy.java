package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.UnitSpec;
import io.casehub.desiredstate.example.spatial.specs.ZoneSpec;
import java.util.*;

public class ZoneRebalanceFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        var node = current.nodes().get(event.node());
        if (node == null || !(node.spec() instanceof ZoneSpec zoneSpec)) {
            return List.of();
        }

        // Identify which member units are ABSENT
        Set<NodeId> absentUnits = new HashSet<>();
        for (NodeId dependentId : current.dependentsOf(event.node())) {
            DesiredNode dependent = current.nodes().get(dependentId);
            if (dependent != null && dependent.spec() instanceof UnitSpec) {
                NodeStatus status = actual.statuses()
                    .getOrDefault(dependentId, NodeStatus.UNKNOWN);
                if (status == NodeStatus.ABSENT) {
                    absentUnits.add(dependentId);
                }
            }
        }

        if (absentUnits.isEmpty()) {
            return List.of();
        }

        // Redistribute allocation among surviving units
        Map<NodeId, Double> surviving = new LinkedHashMap<>();
        for (var entry : zoneSpec.allocation().entrySet()) {
            // Find the unit for this cell
            NodeId unitId = NodeId.of("unit-" + entry.getKey().value());
            if (!absentUnits.contains(unitId)) {
                surviving.put(entry.getKey(), entry.getValue());
            }
        }

        if (surviving.isEmpty()) {
            return List.of();
        }

        // Normalize ratios to sum to 1.0
        double total = surviving.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<NodeId, Double> normalized = new LinkedHashMap<>();
        for (var entry : surviving.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / total);
        }

        // Emit mutations: update zone spec + update surviving unit strengths
        List<GraphMutation> mutations = new ArrayList<>();
        ZoneSpec newZoneSpec = new ZoneSpec(
            zoneSpec.zoneName(), normalized, zoneSpec.totalForce());
        mutations.add(new GraphMutation.UpdateNode(event.node(), newZoneSpec));

        for (var entry : normalized.entrySet()) {
            NodeId unitId = NodeId.of("unit-" + entry.getKey().value());
            int strength = (int) Math.round(zoneSpec.totalForce() * entry.getValue());
            mutations.add(new GraphMutation.UpdateNode(unitId, new UnitSpec(entry.getKey(), strength)));
        }

        return mutations;
    }
}
