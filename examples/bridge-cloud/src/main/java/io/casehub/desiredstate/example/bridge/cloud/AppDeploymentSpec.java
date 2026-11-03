package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record AppDeploymentSpec(String appName, String version, String vmNodeId) implements NodeSpec {
    @Override
    public NodeType nodeType() {
        return CloudNodeTypes.APP_DEPLOYMENT;
    }
}
