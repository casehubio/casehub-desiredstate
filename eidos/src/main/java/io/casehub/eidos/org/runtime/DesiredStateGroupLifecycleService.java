package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.LifecycleManager;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.eidos.org.api.ActualMembership;
import io.casehub.eidos.org.api.FormationOrigin;
import io.casehub.eidos.org.api.FormationPath;
import io.casehub.eidos.org.api.FormationProposal;
import io.casehub.eidos.org.api.GroupInstance;
import io.casehub.eidos.org.api.GroupInstanceQuery;
import io.casehub.eidos.org.api.GroupLifecycleListener;
import io.casehub.eidos.org.api.GroupLifecycleService;
import io.casehub.eidos.org.api.GroupLifecycleState;
import io.casehub.eidos.org.api.OrgRegistry;
import io.casehub.eidos.org.api.OrgValidationException;
import io.casehub.eidos.org.api.OrganizationalUnit;
import io.casehub.eidos.org.api.spi.GroupInstanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    private final List<GroupLifecycleListener> listeners;
    private final OrgChannelCreator            channelCreator;
    private final ConcurrentHashMap<String, ReentrantLock> unitLocks = new ConcurrentHashMap<>();


    @Inject
    public DesiredStateGroupLifecycleService(
            OrgRegistry registry,
            GroupInstanceStore instanceStore,
            OrgGoalStore goalStore,
            OrgGoalCompiler compiler,
            DesiredStateGraphFactory graphFactory,
            ReconciliationLoop reconciliationLoop,
            LifecycleManager lifecycleManager,
            OrgChannelCreator channelCreator) {
        this(registry, instanceStore, goalStore, compiler, graphFactory,
             reconciliationLoop, lifecycleManager, channelCreator, List.of());
    }

    public DesiredStateGroupLifecycleService(
            OrgRegistry registry,
            GroupInstanceStore instanceStore,
            OrgGoalStore goalStore,
            OrgGoalCompiler compiler,
            DesiredStateGraphFactory graphFactory,
            ReconciliationLoop reconciliationLoop,
            LifecycleManager lifecycleManager,
            OrgChannelCreator channelCreator,
            List<GroupLifecycleListener> listeners) {
        this.registry           = registry;
        this.instanceStore      = instanceStore;
        this.goalStore          = goalStore;
        this.compiler           = compiler;
        this.graphFactory       = graphFactory;
        this.reconciliationLoop = reconciliationLoop;
        this.lifecycleManager   = lifecycleManager;
        this.channelCreator     = channelCreator;
        this.listeners          = listeners != null ? List.copyOf(listeners) : List.of();
    }

    @Override
    public GroupInstance createGroup(String specId, String tenancyId, FormationOrigin origin) {
        return withUnitLock(specId, tenancyId, () -> {
            var unit = registry.findUnit(specId, tenancyId)
                               .orElseThrow(() -> new OrgValidationException("specId",
                                                                             "no OrganizationalUnit found for specId '" + specId
                                                                             + "' in tenancy '" + tenancyId + "'"));
            validateFormationPath(unit, origin);
            mutate(tenancyId, goal -> goal.withUnitGoal(new OrgUnitGoal(unit)));
            var proposal = new FormationProposal(specId, tenancyId, origin, List.of(), java.util.Map.of());
            listeners.forEach(l -> l.onFormationProposal(proposal));
            var initialState = origin == FormationOrigin.PROPOSAL
                    ? GroupLifecycleState.PROPOSED
                    : GroupLifecycleState.ACTIVE;
            var now = Instant.now();
            return new GroupInstance(
                    specId, specId, tenancyId, initialState, origin,
                    null, null, null,
                    List.of(), List.of(), List.of(), now, now, 0);
        });
    }

    GroupInstance createGroupFromUnit(OrganizationalUnit unit, String tenancyId, FormationOrigin origin) {
        registry.registerUnit(unit);
        return createGroup(unit.unitId(), tenancyId, origin);
    }


    @Override
    public GroupInstance proposeGroup(String specId, String tenancyId, String proposerAgentId) {
        var unit = registry.findUnit(specId, tenancyId)
                           .orElseThrow(() -> new OrgValidationException("specId",
                                                                         "no OrganizationalUnit found for specId '" + specId
                                                                         + "' in tenancy '" + tenancyId + "'"));
        if (unit.selfDetermination() < 0.5) {
            throw new OrgValidationException("selfDetermination",
                                             "bottom-up proposals require selfDetermination >= 0.5 (was " + unit.selfDetermination() + ")");
        }
        return createGroup(specId, tenancyId, FormationOrigin.PROPOSAL);
    }

    @Override
    public void approveProposal(String instanceId, String tenancyId) {
        var goal = goalStore.load(tenancyId).orElse(OrgGoal.empty());
        requireUnitGoal(goal, instanceId, tenancyId);
    }

    @Override
    public void rejectProposal(String instanceId, String tenancyId, String reason) {
        withUnitLock(instanceId, tenancyId, () -> {
            mutate(tenancyId, goal -> {
                requireUnitGoal(goal, instanceId, tenancyId);
                return goal.withoutUnit(instanceId);
            });
        });
    }

    @Override
    public void activateGroup(String instanceId, String tenancyId) {
        var goal = goalStore.load(tenancyId).orElse(OrgGoal.empty());
        requireUnitGoal(goal, instanceId, tenancyId);
    }

    @Override
    public void dissolveGroup(String instanceId, String tenancyId, String reason) {
        withUnitLock(instanceId, tenancyId, () -> {
            instanceStore.findById(instanceId, tenancyId).ifPresent(gi -> {
                instanceStore.save(gi.withState(GroupLifecycleState.DISSOLVING)
                                     .withLastTransitionAt(Instant.now()));
            });
            mutate(tenancyId, goal -> {
                requireUnitGoal(goal, instanceId, tenancyId);
                return goal.withoutUnit(instanceId);
            });
            listeners.forEach(l -> l.onDissolution(instanceId, tenancyId, reason));
        });
    }

    @Override
    public void changeTopology(String instanceId, String newTopology,
                               String newTopologyVocabulary, String tenancyId) {
        withUnitLock(instanceId, tenancyId, () -> {
            var unitGoalOpt = goalStore.load(tenancyId)
                                       .flatMap(g -> g.findUnitGoal(instanceId));
            var oldTopology = unitGoalOpt.map(ug -> ug.unit().topology()).orElse(null);

            unitGoalOpt.ifPresent(ug -> {
                if (ug.unit().selfDetermination() <= 0.7) {
                    throw new OrgValidationException("selfDetermination",
                        "topology changes require selfDetermination > 0.7 (was " + ug.unit().selfDetermination() + ")");
                }
            });

            setReorganisingIfActive(instanceId, tenancyId);

            try {
                doMutateUnit(instanceId, tenancyId, unitGoal -> {
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
            } catch (Exception e) {
                restoreActiveIfReorganising(instanceId, tenancyId);
                throw e;
            }

            listeners.forEach(l -> l.onTopologyChange(instanceId, oldTopology, newTopology, tenancyId));
        });
    }

    @Override
    public void joinGroup(String instanceId, String agentId, String tenancyId) {
        mutateUnit(instanceId, tenancyId, unitGoal -> {
            validateJoinGroup(unitGoal);
            return unitGoal.withMember(agentId);
        });
        listeners.forEach(l -> l.onMemberJoined(instanceId, agentId, tenancyId));
    }

    @Override
    public void leaveGroup(String instanceId, String agentId, String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withoutMember(agentId).withoutRolesForAgent(agentId));
        listeners.forEach(l -> l.onMemberLeft(instanceId, agentId, tenancyId));
    }

    @Override
    public void adoptRole(String instanceId, String agentId, String roleName,
                          String tenancyId) {
        mutateUnit(instanceId, tenancyId, unitGoal -> {
            validateAdoptRole(unitGoal, agentId, roleName);
            return unitGoal.withRole(new DesiredRoleAdoption(agentId, roleName));
        });
        listeners.forEach(l -> l.onRoleAdoption(instanceId, agentId, roleName, tenancyId));
    }

    @Override
    public void releaseRole(String instanceId, String agentId, String roleName,
                            String tenancyId) {
        mutateUnit(instanceId, tenancyId,
            unitGoal -> unitGoal.withoutRole(agentId, roleName));
        listeners.forEach(l -> l.onRoleRelease(instanceId, agentId, roleName, tenancyId));
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


    ProvisionResult materializeUnit(DesiredNode node, ProvisionContext context) {
        var spec = (OrgUnitNodeSpec) node.spec();
        var unit = spec.unit();

        var existing = instanceStore.findById(node.id().value(), context.tenancyId());
        if (existing.isPresent() && existing.get().state().isActive()) {
            return new ProvisionResult.AlreadyConverged();
        }

        registry.registerUnit(unit);

        var now = Instant.now();
        var members = unit.members().stream()
                          .map(m -> new ActualMembership(m.agentId(), now))
                          .toList();

        var instance = new GroupInstance(
                unit.unitId(), unit.unitId(), context.tenancyId(),
                GroupLifecycleState.ACTIVE, FormationOrigin.TOP_DOWN,
                unit.topology(), unit.topologyVocabulary(),
                unit.authorityModel(), members, List.of(), List.of(),
                now, now, 0);
        instanceStore.save(instance);

        var memberIds = unit.members().stream()
                            .map(io.casehub.eidos.org.api.Membership::agentId).toList();
        var coordinatorId = unit.members().stream()
                                .filter(m -> "coordinator".equals(m.role()))
                                .map(io.casehub.eidos.org.api.Membership::agentId)
                                .findFirst().orElse(memberIds.isEmpty() ? null : memberIds.getFirst());
        channelCreator.createGroupChannel(new OrgChannelCreator.ChannelRequest(
                unit.unitId(), unit.name(), memberIds, coordinatorId, context.tenancyId()));

        return new ProvisionResult.Success();
    }

    DeprovisionResult dematerializeUnit(DesiredNode node, DeprovisionContext context) {
        var existing = instanceStore.findById(node.id().value(), context.tenancyId());
        if (existing.isPresent()) {
            var gi = existing.get();
            if (gi.state() == GroupLifecycleState.DISSOLVING && !gi.members().isEmpty()) {
                return new DeprovisionResult.Failed(
                        "dissolution drain in progress — " + gi.members().size() + " members remaining");
            }
            var dissolved = new GroupInstance(
                    gi.instanceId(), gi.specId(), gi.tenancyId(),
                    GroupLifecycleState.DISSOLVED, gi.formationOrigin(),
                    gi.topology(), gi.topologyVocabulary(),
                    gi.effectiveAuthority(), gi.members(), gi.roleAdoptions(),
                    gi.missionCommitments(), gi.createdAt(), Instant.now(),
                    gi.version() + 1);
            instanceStore.save(dissolved);
        }
        registry.removeUnit(node.id().value(), context.tenancyId());
        channelCreator.deleteGroupChannel(node.id().value(), context.tenancyId());
        return new DeprovisionResult.Success();
    }

    ProvisionResult materializeRelationship(DesiredNode node, ProvisionContext context) {
        var spec = (OrgRelationshipNodeSpec) node.spec();
        registry.addRelationship(spec.relationship());
        return new ProvisionResult.Success();
    }

    DeprovisionResult dematerializeRelationship(DesiredNode node, DeprovisionContext context) {
        var spec = (OrgRelationshipNodeSpec) node.spec();
        var rel  = spec.relationship();
        registry.removeRelationship(rel.sourceAgentId(), rel.targetAgentId(),
                                    rel.kind(), context.tenancyId());
        return new DeprovisionResult.Success();
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
        withUnitLock(instanceId, tenancyId, () -> {
            rejectIfReorganising(instanceId, tenancyId);
            doMutateUnit(instanceId, tenancyId, unitMutation);
        });
    }

    private void doMutateUnit(String instanceId, String tenancyId,
                              Function<OrgUnitGoal, OrgUnitGoal> unitMutation) {
        mutate(tenancyId, goal -> {
            var unitGoal = requireUnitGoal(goal, instanceId, tenancyId);
            return goal.withUnitGoal(unitMutation.apply(unitGoal));
        });
    }

    private void rejectIfReorganising(String instanceId, String tenancyId) {
        instanceStore.findById(instanceId, tenancyId)
                     .filter(gi -> gi.state() == GroupLifecycleState.REORGANISING)
                     .ifPresent(gi -> {
                         throw new OrgValidationException("state",
                                                          "group '" + instanceId + "' is reorganising — concurrent mutations blocked");
                     });
    }

    private void setReorganisingIfActive(String instanceId, String tenancyId) {
        instanceStore.findById(instanceId, tenancyId)
                     .filter(gi -> gi.state().isActive())
                     .ifPresent(gi -> {
                         var reorganising = new GroupInstance(
                                 gi.instanceId(), gi.specId(), gi.tenancyId(),
                                 GroupLifecycleState.REORGANISING, gi.formationOrigin(),
                                 gi.topology(), gi.topologyVocabulary(),
                                 gi.effectiveAuthority(), gi.members(), gi.roleAdoptions(),
                                 gi.missionCommitments(), gi.createdAt(), Instant.now(),
                                 gi.version() + 1);
                         instanceStore.save(reorganising);
                     });
    }

    private void restoreActiveIfReorganising(String instanceId, String tenancyId) {
        instanceStore.findById(instanceId, tenancyId)
                     .filter(gi -> gi.state() == GroupLifecycleState.REORGANISING)
                     .ifPresent(gi -> {
                         var active = new GroupInstance(
                                 gi.instanceId(), gi.specId(), gi.tenancyId(),
                                 GroupLifecycleState.ACTIVE, gi.formationOrigin(),
                                 gi.topology(), gi.topologyVocabulary(),
                                 gi.effectiveAuthority(), gi.members(), gi.roleAdoptions(),
                                 gi.missionCommitments(), gi.createdAt(), Instant.now(),
                                 gi.version() + 1);
                         instanceStore.save(active);
                     });
    }

    private <T> T withUnitLock(String unitId, String tenancyId, Supplier<T> action) {
        var lock = unitLocks.computeIfAbsent(tenancyId + ":" + unitId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void withUnitLock(String unitId, String tenancyId, Runnable action) {
        var lock = unitLocks.computeIfAbsent(tenancyId + ":" + unitId, k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
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
