package io.casehub.desiredstate.example.bridge.cloud;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CloudWorld {

    private final Map<String, AppDeploymentSpec> deployments = new ConcurrentHashMap<>();

    public void deploy(String nodeId, AppDeploymentSpec spec) {
        deployments.put(nodeId, spec);
    }

    public void undeploy(String nodeId) {
        deployments.remove(nodeId);
    }

    public Map<String, AppDeploymentSpec> deployments() {
        return Map.copyOf(deployments);
    }
}
