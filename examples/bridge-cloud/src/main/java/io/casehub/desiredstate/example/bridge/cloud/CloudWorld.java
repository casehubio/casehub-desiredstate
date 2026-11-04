package io.casehub.desiredstate.example.bridge.cloud;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CloudWorld {

    private final Map<String, AppDeploymentSpec> deployments   = new ConcurrentHashMap<>();
    private final Set<String>                    suspendedApps = ConcurrentHashMap.newKeySet();

    public void deploy(String nodeId, AppDeploymentSpec spec) {
        deployments.put(nodeId, spec);
        suspendedApps.remove(nodeId);
    }

    public void undeploy(String nodeId) {
        deployments.remove(nodeId);
        suspendedApps.remove(nodeId);
    }

    public void suspend(String nodeId) {
        suspendedApps.add(nodeId);
    }

    public void resume(String nodeId) {
        suspendedApps.remove(nodeId);
    }

    public boolean isSuspended(String nodeId) {
        return suspendedApps.contains(nodeId);
    }

    public Map<String, AppDeploymentSpec> deployments() {
        return Map.copyOf(deployments);
    }

    public Set<String> suspendedDeployments() {
        return Set.copyOf(suspendedApps);
    }
}
