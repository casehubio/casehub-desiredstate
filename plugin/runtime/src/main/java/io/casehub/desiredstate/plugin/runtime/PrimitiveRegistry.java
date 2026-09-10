package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.api.StepPrimitive;

import java.util.Map;

public class PrimitiveRegistry {

    private final Map<String, StepPrimitive> primitives;

    private PrimitiveRegistry(Map<String, StepPrimitive> primitives) {
        this.primitives = Map.copyOf(primitives);
    }

    public static PrimitiveRegistry of(Map<String, StepPrimitive> primitives) {
        return new PrimitiveRegistry(primitives);
    }

    public StepPrimitive resolve(String name) {
        StepPrimitive p = primitives.get(name);
        if (p == null) {
            throw new StepExecutionException("Unknown primitive: " + name);
        }
        return p;
    }

    public boolean contains(String name) {
        return primitives.containsKey(name);
    }
}
