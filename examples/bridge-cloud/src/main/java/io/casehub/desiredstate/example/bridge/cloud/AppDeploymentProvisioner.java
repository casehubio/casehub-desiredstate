package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.api.*;

import java.util.Set;

public class AppDeploymentProvisioner implements NodeProvisioner {

    private final CloudWorld world;

    public AppDeploymentProvisioner(CloudWorld world) {
        this.world = world;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(CloudNodeTypes.APP_DEPLOYMENT);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof AppDeploymentSpec spec)) {
            return new ProvisionResult.Failed("Expected AppDeploymentSpec");
        }
        world.deploy(node.id().value(), spec);
        return new ProvisionResult.Success();
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        world.undeploy(node.id().value());
        return new DeprovisionResult.Success();
    }
}
