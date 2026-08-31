package io.casehub.desiredstate.yaml.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeSpecFactory;
import io.casehub.desiredstate.api.NodeSpecFactoryProvider;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NodeSpecRegistry {

    private final Map<String, NodeSpecFactory> factoryMap;
    private final Map<String, NodeSpecFactory> byClassName;

    private NodeSpecRegistry(Map<String, NodeSpecFactory> factoryMap,
                             Map<String, NodeSpecFactory> byClassName) {
        this.factoryMap  = Map.copyOf(factoryMap);
        this.byClassName = Map.copyOf(byClassName);
    }

    public static NodeSpecRegistry of(Map<String, String> typeToClassName) {
        return of(typeToClassName, List.of());
    }

    @SuppressWarnings("unchecked")
    public static NodeSpecRegistry of(Map<String, String> typeToClassName,
                                      List<NodeSpecFactoryProvider> providers) {
        ObjectMapper mapper = new ObjectMapper();

        Map<String, NodeSpecFactory> providerFactories = new HashMap<>();
        for (NodeSpecFactoryProvider provider : providers) {
            providerFactories.putAll(provider.provide());
        }

        Map<String, NodeSpecFactory> factories      = new HashMap<>();
        Map<String, NodeSpecFactory> classNameIndex = new HashMap<>();
        ClassLoader                  cl             = Thread.currentThread().getContextClassLoader();

        for (Map.Entry<String, String> entry : typeToClassName.entrySet()) {
            String typeId    = entry.getKey();
            String className = entry.getValue();

            if (providerFactories.containsKey(typeId)) {
                NodeSpecFactory pf = providerFactories.get(typeId);
                factories.put(typeId, pf);
                classNameIndex.put(className, pf);
            } else {
                try {
                    Class<?>                  cls        = cl.loadClass(className);
                    Class<? extends NodeSpec> specClass  = (Class<? extends NodeSpec>) cls;
                    NodeSpecFactory           directCast = specMap -> mapper.convertValue(specMap, specClass);
                    factories.put(typeId, directCast);
                    classNameIndex.put(className, directCast);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("NodeSpec class not found: " + className, e);
                }
            }
        }

        for (Map.Entry<String, NodeSpecFactory> pe : providerFactories.entrySet()) {
            factories.putIfAbsent(pe.getKey(), pe.getValue());
        }

        return new NodeSpecRegistry(factories, classNameIndex);
    }

    public NodeSpecFactory resolve(String typeName) {
        NodeSpecFactory factory = factoryMap.get(typeName);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown node type: '" + typeName
                                               + "'. Available types: " + factoryMap.keySet());
        }
        return factory;
    }

    public NodeSpecFactory resolveByClassName(String className) {
        NodeSpecFactory factory = byClassName.get(className);
        if (factory == null) {
            throw new IllegalArgumentException("No NodeSpec registered with class: " + className);
        }
        return factory;
    }

    public Set<String> availableTypes() {
        return Collections.unmodifiableSet(factoryMap.keySet());
    }
}
