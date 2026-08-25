package io.casehub.desiredstate.annotations.runtime;

import java.lang.reflect.Method;
import java.util.List;

public record ResolvedGraphRule(
        String name,
        Method method,
        Object instance,
        boolean imperative,
        List<PatternParameterDescriptor> patterns) {}
