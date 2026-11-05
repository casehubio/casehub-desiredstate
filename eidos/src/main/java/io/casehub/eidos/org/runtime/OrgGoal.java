package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.AgentRelationship;
import io.casehub.eidos.org.api.RelationshipKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record OrgGoal(
    List<OrgUnitGoal> unitGoals,
    List<AgentRelationship> relationships
) {
    public OrgGoal {
        unitGoals = unitGoals != null ? List.copyOf(unitGoals) : List.of();
        relationships = relationships != null ? List.copyOf(relationships) : List.of();
    }

    public static OrgGoal empty() {
        return new OrgGoal(List.of(), List.of());
    }

    public OrgGoal withUnitGoal(OrgUnitGoal unitGoal) {
        var units = new ArrayList<>(unitGoals);
        units.removeIf(u -> u.unit().unitId().equals(unitGoal.unit().unitId()));
        units.add(unitGoal);
        return new OrgGoal(units, relationships);
    }

    public OrgGoal withoutUnit(String unitId) {
        var units = unitGoals.stream()
            .filter(u -> !u.unit().unitId().equals(unitId))
            .toList();
        return new OrgGoal(units, relationships);
    }

    public Optional<OrgUnitGoal> findUnitGoal(String unitId) {
        return unitGoals.stream()
            .filter(u -> u.unit().unitId().equals(unitId))
            .findFirst();
    }

    public OrgGoal withRelationship(AgentRelationship rel) {
        var rels = new ArrayList<>(relationships);
        rels.removeIf(r -> r.sourceAgentId().equals(rel.sourceAgentId())
            && r.targetAgentId().equals(rel.targetAgentId())
            && r.kind() == rel.kind());
        rels.add(rel);
        return new OrgGoal(unitGoals, rels);
    }

    public OrgGoal withoutRelationship(String sourceAgentId, String targetAgentId,
                                        RelationshipKind kind) {
        var rels = relationships.stream()
            .filter(r -> !(r.sourceAgentId().equals(sourceAgentId)
                && r.targetAgentId().equals(targetAgentId)
                && r.kind() == kind))
            .toList();
        return new OrgGoal(unitGoals, rels);
    }
}
