package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.org.api.*;
import io.casehub.eidos.org.api.spi.GroupInstanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class OrgNodeProvisioner implements NodeProvisioner {

    private final GroupInstanceStore instanceStore;
    private final OrgRegistry registry;
    private final OrgChannelCreator channelCreator;

    @Inject
    public OrgNodeProvisioner(GroupInstanceStore instanceStore, OrgRegistry registry,
                              OrgChannelCreator channelCreator) {
        this.instanceStore = instanceStore;
        this.registry = registry;
        this.channelCreator = channelCreator;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(OrgNodeTypes.UNIT, OrgNodeTypes.RELATIONSHIP);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (node.type().equals(OrgNodeTypes.UNIT)) {
            return provisionUnit(node, context);
        } else if (node.type().equals(OrgNodeTypes.RELATIONSHIP)) {
            return provisionRelationship(node, context);
        }
        return new ProvisionResult.Failed("unknown node type: " + node.type());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (node.type().equals(OrgNodeTypes.UNIT)) {
            return deprovisionUnit(node, context);
        } else if (node.type().equals(OrgNodeTypes.RELATIONSHIP)) {
            return deprovisionRelationship(node, context);
        }
        return new DeprovisionResult.Failed("unknown node type: " + node.type());
    }

    private ProvisionResult provisionUnit(DesiredNode node, ProvisionContext context) {
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

        channelCreator.createGroupChannel(unit.unitId(), context.tenancyId());

        return new ProvisionResult.Success();
    }

    private DeprovisionResult deprovisionUnit(DesiredNode node, DeprovisionContext context) {
        var existing = instanceStore.findById(node.id().value(), context.tenancyId());
        if (existing.isPresent()) {
            var gi = existing.get();
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

    private ProvisionResult provisionRelationship(DesiredNode node, ProvisionContext context) {
        var spec = (OrgRelationshipNodeSpec) node.spec();
        registry.addRelationship(spec.relationship());
        return new ProvisionResult.Success();
    }

    private DeprovisionResult deprovisionRelationship(DesiredNode node, DeprovisionContext context) {
        var spec = (OrgRelationshipNodeSpec) node.spec();
        var rel = spec.relationship();
        registry.removeRelationship(rel.sourceAgentId(), rel.targetAgentId(),
            rel.kind(), context.tenancyId());
        return new DeprovisionResult.Success();
    }
}
