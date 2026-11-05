package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.org.api.GroupInstance;
import io.casehub.eidos.org.api.OrganizationalUnit;
import io.casehub.eidos.org.api.spi.GroupInstanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.annotation.Priority;

import java.util.Objects;

@ApplicationScoped
@Priority(100)
public class OrgDriftPolicy implements DriftPolicyEvaluator {

    private final GroupInstanceStore instanceStore;

    @Inject
    public OrgDriftPolicy(GroupInstanceStore instanceStore) {
        this.instanceStore = Objects.requireNonNull(instanceStore);
    }

    @Override
    public DriftDecision evaluate(NodeId nodeId, NodeStatus status, DesiredNode node, DriftContext context) {
        if (!(node.spec() instanceof OrgUnitNodeSpec orgSpec)) {
            return DriftDecision.reconcile();
        }

        var unit = orgSpec.unit();
        var instance = instanceStore.findById(nodeId.value(), context.tenancyId());
        if (instance.isEmpty()) {
            return DriftDecision.reconcile();
        }

        var gi = instance.get();

        if (isAutonomousTopologyChange(unit, gi)) {
            return DriftDecision.exempt(new ExemptionSpec(new RevertCondition.Never(), null));
        }

        return DriftDecision.reconcile();
    }

    private boolean isAutonomousTopologyChange(OrganizationalUnit unit, GroupInstance gi) {
        if (unit.topology() == null || gi.topology() == null) return false;
        if (unit.topology().equals(gi.topology())) return false;
        return unit.selfDetermination() > 0.7;
    }
}
