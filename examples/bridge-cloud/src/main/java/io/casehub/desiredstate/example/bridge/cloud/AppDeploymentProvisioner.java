package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.ResumeContext;
import io.casehub.desiredstate.api.ResumeResult;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;

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

    @Override
    public SuspendResult suspend(DesiredNode node, SuspendContext context) {
        world.suspend(node.id().value());
        return new SuspendResult.Success();
    }

    @Override
    public ResumeResult resume(DesiredNode node, ResumeContext context) {
        world.resume(node.id().value());
        return new ResumeResult.Success();
    }

    @Override
    public boolean supportsStatefulLifecycle() {
        return true;
    }
}
