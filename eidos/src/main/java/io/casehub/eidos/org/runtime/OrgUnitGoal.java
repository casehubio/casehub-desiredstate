package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.OrganizationalUnit;

import java.util.ArrayList;
import java.util.List;

public record OrgUnitGoal(
    OrganizationalUnit unit,
    List<String> desiredMembers,
    List<DesiredRoleAdoption> desiredRoles,
    List<DesiredMissionCommitment> desiredMissions
) {
    public OrgUnitGoal {
        desiredMembers = desiredMembers != null ? List.copyOf(desiredMembers) : List.of();
        desiredRoles = desiredRoles != null ? List.copyOf(desiredRoles) : List.of();
        desiredMissions = desiredMissions != null ? List.copyOf(desiredMissions) : List.of();
    }

    public OrgUnitGoal(OrganizationalUnit unit) {
        this(unit, List.of(), List.of(), List.of());
    }

    public OrgUnitGoal withUnit(OrganizationalUnit newUnit) {
        return new OrgUnitGoal(newUnit, desiredMembers, desiredRoles, desiredMissions);
    }

    public OrgUnitGoal withMember(String agentId) {
        if (desiredMembers.contains(agentId)) return this;
        var members = new ArrayList<>(desiredMembers);
        members.add(agentId);
        return new OrgUnitGoal(unit, members, desiredRoles, desiredMissions);
    }

    public OrgUnitGoal withoutRolesForAgent(String agentId) {
        var roles = desiredRoles.stream()
            .filter(r -> !r.agentId().equals(agentId))
            .toList();
        return new OrgUnitGoal(unit, desiredMembers, roles, desiredMissions);
    }

    public OrgUnitGoal withoutMember(String agentId) {
        var members = desiredMembers.stream()
            .filter(id -> !id.equals(agentId))
            .toList();
        return new OrgUnitGoal(unit, members, desiredRoles, desiredMissions);
    }

    public OrgUnitGoal withRole(DesiredRoleAdoption role) {
        var roles = new ArrayList<>(desiredRoles);
        roles.add(role);
        return new OrgUnitGoal(unit, desiredMembers, roles, desiredMissions);
    }

    public OrgUnitGoal withoutRole(String agentId, String roleName) {
        var roles = desiredRoles.stream()
            .filter(r -> !(r.agentId().equals(agentId) && r.roleName().equals(roleName)))
            .toList();
        return new OrgUnitGoal(unit, desiredMembers, roles, desiredMissions);
    }

    public OrgUnitGoal withMission(DesiredMissionCommitment mission) {
        var missions = new ArrayList<>(desiredMissions);
        missions.add(mission);
        return new OrgUnitGoal(unit, desiredMembers, desiredRoles, missions);
    }

    public OrgUnitGoal withoutMission(String agentId, String missionName) {
        var missions = desiredMissions.stream()
            .filter(m -> !(m.agentId().equals(agentId) && m.missionName().equals(missionName)))
            .toList();
        return new OrgUnitGoal(unit, desiredMembers, desiredRoles, missions);
    }

    public OrgUnitGoal withoutMissionByName(String missionName) {
        var missions = desiredMissions.stream()
            .filter(m -> !m.missionName().equals(missionName))
            .toList();
        return new OrgUnitGoal(unit, desiredMembers, desiredRoles, missions);
    }
}
