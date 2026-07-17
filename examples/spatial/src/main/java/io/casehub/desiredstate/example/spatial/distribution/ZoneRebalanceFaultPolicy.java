package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.example.spatial.specs.UnitSpec;
import io.casehub.desiredstate.example.spatial.specs.ZoneSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ZoneRebalanceFaultPolicy implements FaultPolicy {

    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        var node = current.nodes().get(event.node());
        if (node == null || !(node.spec() instanceof ZoneSpec zoneSpec)) {
            return List.of();
        }

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

        Map<NodeId, Double> surviving = new LinkedHashMap<>();
        for (var entry : zoneSpec.allocation().entrySet()) {
            NodeId unitId = NodeId.of("unit-" + entry.getKey().value());
            if (!absentUnits.contains(unitId)) {
                surviving.put(entry.getKey(), entry.getValue());
            }
        }

        if (surviving.isEmpty()) {
            return List.of();
        }

        double              total      = surviving.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<NodeId, Double> normalized = new LinkedHashMap<>();
        for (var entry : surviving.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / total);
        }

        List<GraphMutation> mutations = new ArrayList<>();
        ZoneSpec newZoneSpec = new ZoneSpec(
                zoneSpec.zoneName(), normalized, zoneSpec.totalForce());
        mutations.add(new GraphMutation.UpdateNode(event.node(),
                new DesiredNode(node.id(), node.type(), newZoneSpec, node.humanGating())));

        for (var entry : normalized.entrySet()) {
            NodeId unitId   = NodeId.of("unit-" + entry.getKey().value());
            int    strength = (int) Math.round(zoneSpec.totalForce() * entry.getValue());
            DesiredNode existingUnit = current.nodes().get(unitId);
            mutations.add(new GraphMutation.UpdateNode(unitId,
                new DesiredNode(unitId, existingUnit.type(), new UnitSpec(entry.getKey(), strength), existingUnit.humanGating())));
        }

        return mutations;
    }
}
