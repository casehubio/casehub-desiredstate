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
import java.util.Set;
import java.util.stream.Collectors;
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
        validateFormationPath(unit, origin);
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
        mutateUnit(instanceId, tenancyId, unitGoal -> {
            validateJoinGroup(unitGoal);
            return unitGoal.withMember(agentId);
        });
    }

    @Override
    public void leaveGroup(String instanceId, String agentId, String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withoutMember(agentId).withoutRolesForAgent(agentId));
    }

    @Override
    public void adoptRole(String instanceId, String agentId, String roleName,
                          String tenancyId) {
        mutateUnit(instanceId, tenancyId, unitGoal -> {
            validateAdoptRole(unitGoal, agentId, roleName);
            return unitGoal.withRole(new DesiredRoleAdoption(agentId, roleName));
        });
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

    private void validateFormationPath(OrganizationalUnit unit, FormationOrigin origin) {
        if (unit.policy() == null || unit.policy().formationRules() == null) return;
        var allowed = unit.policy().formationRules().allowedPaths();
        if (allowed.isEmpty()) return;
        var path = switch (origin) {
            case TOP_DOWN -> FormationPath.TOP_DOWN;
            case EMERGENCE -> FormationPath.EMERGENCE;
            case PROPOSAL -> FormationPath.BOTTOM_UP;
        };
        if (!allowed.contains(path)) {
            throw new OrgValidationException("formationOrigin",
                "formation path " + path + " not in allowedPaths " + allowed);
        }
    }

    private void validateJoinGroup(OrgUnitGoal unitGoal) {
        int currentSize = unitGoal.desiredMembers().size();
        if (currentSize >= OrganizationalUnit.MAX_MEMBERS) {
            throw new OrgValidationException("members",
                "exceeds MAX_MEMBERS (" + OrganizationalUnit.MAX_MEMBERS + ")");
        }
        if (unitGoal.unit().policy() != null && unitGoal.unit().policy().formationRules() != null) {
            int maxSize = unitGoal.unit().policy().formationRules().maxGroupSize();
            if (maxSize != -1 && currentSize >= maxSize) {
                throw new OrgValidationException("members",
                    "exceeds maxGroupSize (" + maxSize + ")");
            }
        }
    }

    private void validateAdoptRole(OrgUnitGoal unitGoal, String agentId, String roleName) {
        var roleSpec = unitGoal.unit().roleSpecs().stream()
            .filter(rs -> rs.roleName().equals(roleName))
            .findFirst()
            .orElseThrow(() -> new OrgValidationException("roleName",
                "role '" + roleName + "' not defined in unit '" + unitGoal.unit().unitId() + "'"));

        if (roleSpec.maxCardinality() != -1) {
            long count = unitGoal.desiredRoles().stream()
                .filter(r -> r.roleName().equals(roleName)).count();
            if (count >= roleSpec.maxCardinality()) {
                throw new OrgValidationException("maxCardinality",
                    "role '" + roleName + "' at capacity (" + count + "/" + roleSpec.maxCardinality() + ")");
            }
        }

        Set<String> agentRoles = unitGoal.desiredRoles().stream()
            .filter(r -> r.agentId().equals(agentId))
            .map(DesiredRoleAdoption::roleName)
            .collect(Collectors.toSet());
        for (String incompatible : roleSpec.incompatibleWith()) {
            if (agentRoles.contains(incompatible)) {
                throw new OrgValidationException("incompatibleWith",
                    "role '" + roleName + "' incompatible with '" + incompatible
                        + "' held by agent '" + agentId + "'");
            }
        }
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
