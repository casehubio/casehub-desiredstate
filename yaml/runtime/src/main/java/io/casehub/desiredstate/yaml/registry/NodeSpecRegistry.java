package io.casehub.desiredstate.yaml.registry;

import io.casehub.desiredstate.api.NodeSpec;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class NodeSpecRegistry {

    private final Map<String, Class<? extends NodeSpec>> typeMap;

    private NodeSpecRegistry(Map<String, Class<? extends NodeSpec>> typeMap) {
        this.typeMap = Map.copyOf(typeMap);
    }

    @SuppressWarnings("unchecked")
    public static NodeSpecRegistry of(Map<String, String> typeToClassName) {
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
        return new NodeSpecRegistry(resolved);
    }

    public Class<? extends NodeSpec> resolve(String typeName) {
        Class<? extends NodeSpec> cls = typeMap.get(typeName);
        if (cls == null) {
            throw new IllegalArgumentException("Unknown node type: '" + typeName
                    + "'. Available types: " + typeMap.keySet());
        }
        return cls;
    }

    public Class<? extends NodeSpec> resolveByClassName(String className) {
        for (Class<? extends NodeSpec> cls : typeMap.values()) {
            if (cls.getName().equals(className)) return cls;
        }
        throw new IllegalArgumentException("No NodeSpec registered with class: " + className);
    }

    public Set<String> availableTypes() {
        return Collections.unmodifiableSet(typeMap.keySet());
    }
}
