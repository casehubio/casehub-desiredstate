package io.casehub.desiredstate.plugin.api;

import io.casehub.desiredstate.api.NodeType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ExtensionRegistry {

    private final Set<NodeType> staticTypes = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<NodeType, Object> dynamicManifests = new ConcurrentHashMap<>();

    public void registerStatic(NodeType type) {
        staticTypes.add(type);
    }

    public void registerDynamic(NodeType type, Object manifest) {
        dynamicManifests.put(type, manifest);
    }

    public void unregisterDynamic(NodeType type) {
        dynamicManifests.remove(type);
    }

    public Set<NodeType> availableTypes() {
        Set<NodeType> all = new HashSet<>(staticTypes);
        all.addAll(dynamicManifests.keySet());
        return Set.copyOf(all);
    }

    public boolean isTypeAvailable(NodeType type) {
        return staticTypes.contains(type) || dynamicManifests.containsKey(type);
    }

    public Set<NodeType> dynamicTypes() {
        return Set.copyOf(dynamicManifests.keySet());
    }

    public Set<NodeType> staticTypes() {
        return Set.copyOf(staticTypes);
    }
}
