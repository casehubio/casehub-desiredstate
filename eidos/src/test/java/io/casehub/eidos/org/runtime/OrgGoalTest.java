package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.AgentRelationship;
import io.casehub.eidos.org.api.OrganizationalUnit;
import io.casehub.eidos.org.api.RelationshipKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrgGoalTest {

    private OrganizationalUnit unit(String id) {
        return OrganizationalUnit.builder()
            .unitId(id).name(id).tenancyId("t1")
            .selfDetermination(0.5).build();
    }

    @Test void emptyGoalHasNoUnitsOrRelationships() {
        var goal = OrgGoal.empty();
        assertThat(goal.unitGoals()).isEmpty();
        assertThat(goal.relationships()).isEmpty();
    }

    @Test void withUnitGoalAddsUnit() {
        var goal = OrgGoal.empty()
            .withUnitGoal(new OrgUnitGoal(unit("team-1")));
        assertThat(goal.unitGoals()).hasSize(1);
        assertThat(goal.unitGoals().getFirst().unit().unitId()).isEqualTo("team-1");
    }

    @Test void withUnitGoalReplacesExistingByUnitId() {
        var original = unit("team-1");
        var replacement = OrganizationalUnit.builder()
            .unitId("team-1").name("Updated Name").tenancyId("t1")
            .selfDetermination(0.8).build();
        var goal = OrgGoal.empty()
            .withUnitGoal(new OrgUnitGoal(original))
            .withUnitGoal(new OrgUnitGoal(replacement));
        assertThat(goal.unitGoals()).hasSize(1);
        assertThat(goal.unitGoals().getFirst().unit().name()).isEqualTo("Updated Name");
    }

    @Test void withoutUnitRemovesByUnitId() {
        var goal = OrgGoal.empty()
            .withUnitGoal(new OrgUnitGoal(unit("team-1")))
            .withUnitGoal(new OrgUnitGoal(unit("team-2")))
            .withoutUnit("team-1");
        assertThat(goal.unitGoals()).hasSize(1);
        assertThat(goal.unitGoals().getFirst().unit().unitId()).isEqualTo("team-2");
    }

    @Test void findUnitGoalReturnsMatchingUnit() {
        var goal = OrgGoal.empty()
            .withUnitGoal(new OrgUnitGoal(unit("team-1")));
        assertThat(goal.findUnitGoal("team-1")).isPresent();
        assertThat(goal.findUnitGoal("missing")).isEmpty();
    }

    @Test void withMemberAddsDesiredMember() {
        var unitGoal = new OrgUnitGoal(unit("team-1"))
            .withMember("agent-1");
        assertThat(unitGoal.desiredMembers()).containsExactly("agent-1");
    }

    @Test void withoutMemberRemovesDesiredMember() {
        var unitGoal = new OrgUnitGoal(unit("team-1"))
            .withMember("agent-1")
            .withMember("agent-2")
            .withoutMember("agent-1");
        assertThat(unitGoal.desiredMembers()).containsExactly("agent-2");
    }

    @Test void withRoleAddsDesiredRole() {
        var unitGoal = new OrgUnitGoal(unit("team-1"))
            .withRole(new DesiredRoleAdoption("agent-1", "leader"));
        assertThat(unitGoal.desiredRoles()).hasSize(1);
        assertThat(unitGoal.desiredRoles().getFirst().agentId()).isEqualTo("agent-1");
    }

    @Test void withoutRoleRemovesByAgentAndName() {
        var unitGoal = new OrgUnitGoal(unit("team-1"))
            .withRole(new DesiredRoleAdoption("agent-1", "leader"))
            .withRole(new DesiredRoleAdoption("agent-1", "scribe"))
            .withoutRole("agent-1", "leader");
        assertThat(unitGoal.desiredRoles()).hasSize(1);
        assertThat(unitGoal.desiredRoles().getFirst().roleName()).isEqualTo("scribe");
    }

    @Test void withMissionAddsDesiredMission() {
        var unitGoal = new OrgUnitGoal(unit("team-1"))
            .withMission(new DesiredMissionCommitment("agent-1", "deploy"));
        assertThat(unitGoal.desiredMissions()).hasSize(1);
    }

    @Test void withoutMissionByNameRemovesAllCommitmentsForMission() {
        var unitGoal = new OrgUnitGoal(unit("team-1"))
            .withMission(new DesiredMissionCommitment("agent-1", "deploy"))
            .withMission(new DesiredMissionCommitment("agent-2", "deploy"))
            .withoutMissionByName("deploy");
        assertThat(unitGoal.desiredMissions()).isEmpty();
    }

    @Test void withRelationshipAddsRelationship() {
        var rel = AgentRelationship.builder()
            .sourceAgentId("a1").targetAgentId("a2")
            .kind(RelationshipKind.SUPERVISES).tenancyId("t1").build();
        var goal = OrgGoal.empty().withRelationship(rel);
        assertThat(goal.relationships()).hasSize(1);
    }

    @Test void withUnitReplacesOrganizationalUnit() {
        var updated = OrganizationalUnit.builder()
            .unitId("team-1").name("New Name").tenancyId("t1").build();
        var unitGoal = new OrgUnitGoal(unit("team-1"))
            .withMember("agent-1")
            .withUnit(updated);
        assertThat(unitGoal.unit().name()).isEqualTo("New Name");
        assertThat(unitGoal.desiredMembers()).containsExactly("agent-1");
    }
}
