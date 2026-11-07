package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class OrgNodeProvisioner implements NodeProvisioner {

    private final DesiredStateGroupLifecycleService lifecycleService;

    @Inject
    public OrgNodeProvisioner(DesiredStateGroupLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(OrgNodeTypes.UNIT, OrgNodeTypes.RELATIONSHIP);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (node.type().equals(OrgNodeTypes.UNIT)) {
            return lifecycleService.materializeUnit(node, context);
        } else if (node.type().equals(OrgNodeTypes.RELATIONSHIP)) {
            return lifecycleService.materializeRelationship(node, context);
        }
        return new ProvisionResult.Failed("unknown node type: " + node.type());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (node.type().equals(OrgNodeTypes.UNIT)) {
            return lifecycleService.dematerializeUnit(node, context);
        } else if (node.type().equals(OrgNodeTypes.RELATIONSHIP)) {
            return lifecycleService.dematerializeRelationship(node, context);
        }
        return new DeprovisionResult.Failed("unknown node type: " + node.type());
    }
}
