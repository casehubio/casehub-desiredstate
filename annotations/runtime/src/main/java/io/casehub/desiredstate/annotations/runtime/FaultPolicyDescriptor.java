package io.casehub.desiredstate.annotations.runtime;

import java.util.List;

public record FaultPolicyDescriptor(
        List<String> faultTypes,
        List<String> nodeTypes,
        List<String> ignoreTypes,
        String namespace,
        List<TierDescriptor> tiers,
        String sourceClassName) {}
