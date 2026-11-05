package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.eidos.org.api.OrganizationalUnit;

import java.util.List;

@NodeTypeId("org:unit")
public record OrgUnitNodeSpec(
    OrganizationalUnit unit,
    List<String> desiredMembers,
    List<DesiredRoleAdoption> desiredRoles,
    List<DesiredMissionCommitment> desiredMissions
) implements NodeSpec {

    public OrgUnitNodeSpec {
        desiredMembers = desiredMembers != null ? List.copyOf(desiredMembers) : List.of();
        desiredRoles = desiredRoles != null ? List.copyOf(desiredRoles) : List.of();
        desiredMissions = desiredMissions != null ? List.copyOf(desiredMissions) : List.of();
    }

    public OrgUnitNodeSpec(OrganizationalUnit unit) {
        this(unit, List.of(), List.of(), List.of());
    }

    @Override
    public NodeType nodeType() {return OrgNodeTypes.UNIT;}
}
