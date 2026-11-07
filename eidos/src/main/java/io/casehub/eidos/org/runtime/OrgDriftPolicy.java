package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DriftContext;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.DriftPolicyEvaluator;
import io.casehub.desiredstate.api.ExemptionSpec;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.RevertCondition;
import io.casehub.eidos.org.api.GroupInstance;
import io.casehub.eidos.org.api.OrganizationalUnit;
import io.casehub.eidos.org.api.spi.GroupInstanceStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

        var unit     = orgSpec.unit();
        var instance = instanceStore.findById(nodeId.value(), context.tenancyId());
        if (instance.isEmpty()) {
            return DriftDecision.reconcile();
        }

        var gi = instance.get();

        if (isNaturalDissolution(unit, gi)) {
            if (unit.selfDetermination() > 0.7) {
                return DriftDecision.exempt(new ExemptionSpec(new RevertCondition.Never(), null));
            }
            return DriftDecision.reconcile();
        }

        if (isAutonomousTopologyChange(unit, gi)) {
            return DriftDecision.exempt(new ExemptionSpec(new RevertCondition.Never(), null));
        }

        if (hasMemberDepartureBelowMinCardinality(unit, gi)) {
            return DriftDecision.reconcile();
        }

        if (hasRoleGap(unit, gi)) {
            return DriftDecision.reconcile();
        }

        return DriftDecision.reconcile();
    }

    private boolean isAutonomousTopologyChange(OrganizationalUnit unit, GroupInstance gi) {
        if (unit.topology() == null || gi.topology() == null) return false;
        if (unit.topology().equals(gi.topology())) return false;
        return unit.selfDetermination() > 0.7;
    }

    private boolean isNaturalDissolution(OrganizationalUnit unit, GroupInstance gi) {
        return !unit.members().isEmpty() && gi.members().isEmpty();
    }

    private boolean hasMemberDepartureBelowMinCardinality(OrganizationalUnit unit, GroupInstance gi) {
        for (var roleSpec : unit.roleSpecs()) {
            long actual = gi.roleAdoptions().stream()
                            .filter(ra -> ra.roleName().equals(roleSpec.roleName()))
                            .count();
            if (actual < roleSpec.minCardinality()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRoleGap(OrganizationalUnit unit, GroupInstance gi) {
        for (var roleSpec : unit.roleSpecs()) {
            if (roleSpec.obligations().isEmpty()) {continue;}
            boolean filled = gi.roleAdoptions().stream()
                               .anyMatch(ra -> ra.roleName().equals(roleSpec.roleName()));
            if (!filled) {
                return true;
            }
        }
        return false;
    }


}
