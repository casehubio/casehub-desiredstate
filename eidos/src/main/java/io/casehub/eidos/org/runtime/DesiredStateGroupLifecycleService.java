package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.LifecycleManager;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.eidos.org.api.*;
import io.casehub.eidos.org.api.spi.GroupInstanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@ApplicationScoped
public class DesiredStateGroupLifecycleService implements GroupLifecycleService {

    private static final int MAX_CAS_ATTEMPTS = 3;

    private final OrgRegistry registry;
    private final GroupInstanceStore instanceStore;
    private final OrgGoalStore goalStore;
    private final OrgGoalCompiler compiler;
    private final DesiredStateGraphFactory graphFactory;
    private final ReconciliationLoop reconciliationLoop;
    private final LifecycleManager lifecycleManager;

    @Inject
    public DesiredStateGroupLifecycleService(
            OrgRegistry registry,
            GroupInstanceStore instanceStore,
            OrgGoalStore goalStore,
            OrgGoalCompiler compiler,
            DesiredStateGraphFactory graphFactory,
            ReconciliationLoop reconciliationLoop,
            LifecycleManager lifecycleManager) {
        this.registry = registry;
        this.instanceStore = instanceStore;
        this.goalStore = goalStore;
        this.compiler = compiler;
        this.graphFactory = graphFactory;
        this.reconciliationLoop = reconciliationLoop;
        this.lifecycleManager = lifecycleManager;
    }

    @Override
    public GroupInstance createGroup(String specId, String tenancyId, FormationOrigin origin) {
        var unit = registry.findUnit(specId, tenancyId)
            .orElseThrow(() -> new OrgValidationException("specId",
                "no OrganizationalUnit found for specId '" + specId
                    + "' in tenancy '" + tenancyId + "'"));
        mutate(tenancyId, goal -> goal.withUnitGoal(new OrgUnitGoal(unit)));
        var now = Instant.now();
        return new GroupInstance(
            specId, specId, tenancyId, GroupLifecycleState.PROPOSED, origin,
            null, null, null,
            List.of(), List.of(), List.of(), now, now, 0);
    }

    @Override
    public GroupInstance proposeGroup(String specId, String tenancyId, String proposerAgentId) {
        return createGroup(specId, tenancyId, FormationOrigin.PROPOSAL);
    }

    @Override
    public void approveProposal(String instanceId, String tenancyId) {
        var goal = goalStore.load(tenancyId).orElse(OrgGoal.empty());
        requireUnitGoal(goal, instanceId, tenancyId);
    }

    @Override
    public void rejectProposal(String instanceId, String tenancyId, String reason) {
        mutate(tenancyId, goal -> {
            requireUnitGoal(goal, instanceId, tenancyId);
            return goal.withoutUnit(instanceId);
        });
    }

    @Override
    public void activateGroup(String instanceId, String tenancyId) {
        var goal = goalStore.load(tenancyId).orElse(OrgGoal.empty());
        requireUnitGoal(goal, instanceId, tenancyId);
    }

    @Override
    public void dissolveGroup(String instanceId, String tenancyId, String reason) {
        mutate(tenancyId, goal -> {
            requireUnitGoal(goal, instanceId, tenancyId);
            return goal.withoutUnit(instanceId);
        });
    }

    @Override
    public void changeTopology(String instanceId, String newTopology,
                               String newTopologyVocabulary, String tenancyId) {
        mutateUnit(instanceId, tenancyId, unitGoal -> {
            var u = unitGoal.unit();
            var updated = OrganizationalUnit.builder()
                .unitId(u.unitId()).name(u.name())
                .kind(u.kind()).kindVocabulary(u.kindVocabulary())
                .tenancyId(u.tenancyId()).parentUnitId(u.parentUnitId())
                .topology(newTopology).topologyVocabulary(newTopologyVocabulary)
                .authorityModel(u.authorityModel())
                .selfDetermination(u.selfDetermination())
                .policy(u.policy())
                .members(u.members())
                .roleSpecs(u.roleSpecs())
                .missions(u.missions())
                .capabilities(u.capabilities())
                .goals(u.goals())
                .constraints(u.constraints())
                .build();
            return unitGoal.withUnit(updated);
        });
    }

    @Override
    public void joinGroup(String instanceId, String agentId, String tenancyId) {
        mutateUnit(instanceId, tenancyId, unitGoal -> unitGoal.withMember(agentId));
    }

    @Override
    public void leaveGroup(String instanceId, String agentId, String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withoutMember(agentId).withoutRolesForAgent(agentId));
    }

    @Override
    public void adoptRole(String instanceId, String agentId, String roleName,
                          String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withRole(new DesiredRoleAdoption(agentId, roleName)));
    }

    @Override
    public void releaseRole(String instanceId, String agentId, String roleName,
                            String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withoutRole(agentId, roleName));
    }

    @Override
    public void commitToMission(String instanceId, String agentId, String missionName,
                                String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withMission(
                new DesiredMissionCommitment(agentId, missionName)));
    }

    @Override
    public void withdrawFromMission(String instanceId, String agentId,
                                     String missionName, String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withoutMission(agentId, missionName));
    }

    @Override
    public void fulfillMission(String instanceId, String missionName,
                               String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withoutMissionByName(missionName));
    }

    @Override
    public Optional<GroupInstance> findInstance(String instanceId, String tenancyId) {
        return instanceStore.findById(instanceId, tenancyId);
    }

    @Override
    public List<GroupInstance> findInstances(GroupInstanceQuery query) {
        return instanceStore.find(query);
    }

    @Override
    public List<GroupInstance> instancesForAgent(String agentId, String tenancyId) {
        return instanceStore.find(new GroupInstanceQuery(tenancyId, null, null, agentId));
    }

    private void mutateUnit(String instanceId, String tenancyId,
                             Function<OrgUnitGoal, OrgUnitGoal> unitMutation) {
        mutate(tenancyId, goal -> {
            var unitGoal = requireUnitGoal(goal, instanceId, tenancyId);
            return goal.withUnitGoal(unitMutation.apply(unitGoal));
        });
    }

    private OrgUnitGoal requireUnitGoal(OrgGoal goal, String instanceId, String tenancyId) {
        return goal.findUnitGoal(instanceId)
            .orElseThrow(() -> new OrgValidationException("instanceId",
                "no unit found for '" + instanceId + "' in tenancy '" + tenancyId + "'"));
    }

    private void mutate(String tenancyId, Function<OrgGoal, OrgGoal> mutation) {
        OrgGoal goal = goalStore.load(tenancyId).orElse(OrgGoal.empty());
        OrgGoal updated = mutation.apply(goal);
        CompilationResult result = compiler.compile(updated, graphFactory);

        if (!reconciliationLoop.tenantIds().contains(tenancyId)) {
            lifecycleManager.start(tenancyId, result);
            goalStore.store(tenancyId, updated);
            return;
        }

        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            DesiredStateGraph current = reconciliationLoop.getDesired(tenancyId);
            DesiredStateGraph newGraph = switch (result) {
                case CompilationResult.SingleGraph sg -> sg.graph();
                case CompilationResult.Lifecycle lc ->
                    throw new UnsupportedOperationException(
                        "Org domain uses single-graph compilation");
            };
            if (reconciliationLoop.compareAndSetDesired(tenancyId, current, newGraph)) {
                goalStore.store(tenancyId, updated);
                return;
            }
            goal = goalStore.load(tenancyId).orElse(OrgGoal.empty());
            updated = mutation.apply(goal);
            result = compiler.compile(updated, graphFactory);
        }
        throw new ConcurrentModificationException(
            "CAS failed after " + MAX_CAS_ATTEMPTS + " attempts for tenant " + tenancyId);
    }
}
