package io.casehub.desiredstate.yaml.registry;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeSpecFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class NodeSpecRegistry {

    private final Map<String, Class<? extends NodeSpec>> typeMap;
    private final Map<String, NodeSpecFactory> factoryMap;

    private NodeSpecRegistry(Map<String, Class<? extends NodeSpec>> typeMap,
                             Map<String, NodeSpecFactory> factoryMap) {
        this.typeMap = Map.copyOf(typeMap);
        this.factoryMap = Map.copyOf(factoryMap);
    }

    @SuppressWarnings("unchecked")
    public static NodeSpecRegistry of(Map<String, String> typeToClassName) {
        return of(typeToClassName, Map.of());
    }

    @SuppressWarnings("unchecked")
    public static NodeSpecRegistry of(Map<String, String> typeToClassName,
                                      Map<String, NodeSpecFactory> factories) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Map<String, Class<? extends NodeSpec>> resolved = new HashMap<>();
        for (Map.Entry<String, String> entry : typeToClassName.entrySet()) {
            try {
                Class<?> cls = cl.loadClass(entry.getValue());
                resolved.put(entry.getKey(), (Class<? extends NodeSpec>) cls);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("NodeSpec class not found: " + entry.getValue(), e);
            }
        }
        return new NodeSpecRegistry(resolved, factories);
    }

    public Class<? extends NodeSpec> resolve(String typeName) {
        Class<? extends NodeSpec> cls = typeMap.get(typeName);
        if (cls == null) {
            throw new IllegalArgumentException("Unknown node type: '" + typeName
                    + "'. Available types: " + typeMap.keySet());
        }
        return cls;
    }

    public Optional<NodeSpecFactory> resolveFactory(String typeName) {
        return Optional.ofNullable(factoryMap.get(typeName));
    }

    public boolean isFactoryType(String typeName) {
        return factoryMap.containsKey(typeName);
    }

    public Class<? extends NodeSpec> resolveByClassName(String className) {
        for (Class<? extends NodeSpec> cls : typeMap.values()) {
            if (cls.getName().equals(className)) return cls;
        }
        throw new IllegalArgumentException("No NodeSpec registered with class: " + className);
    }

    public Set<String> availableTypes() {
        Set<String> all = new HashSet<>(typeMap.keySet());
        all.addAll(factoryMap.keySet());
        return Collections.unmodifiableSet(all);
    }
}
