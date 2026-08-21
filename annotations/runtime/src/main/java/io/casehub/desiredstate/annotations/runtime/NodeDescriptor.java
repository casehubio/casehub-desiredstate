package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.HumanGating;

public record NodeDescriptor(
        String id,
        String methodName,
        String returnTypeName,
        HumanGating humanGating) {}
