package io.casehub.desiredstate.annotations.runtime;

import java.util.List;

public record GraphInvariantDescriptor(
        String methodName,
        boolean imperative,
        List<PatternParameterDescriptor> patterns,
        String sourceClassName) {}
