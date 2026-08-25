package io.casehub.desiredstate.annotations.runtime;

import java.util.List;

public record GraphRuleDescriptor(
        String methodName,
        boolean imperative,
        List<PatternParameterDescriptor> patterns,
        String sourceClassName) {}
