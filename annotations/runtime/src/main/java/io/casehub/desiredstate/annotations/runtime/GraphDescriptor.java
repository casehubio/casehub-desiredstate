package io.casehub.desiredstate.annotations.runtime;

import java.util.List;

public record GraphDescriptor(
        String namespace,
        String name,
        String interfaceName,
        String implClassName,
        List<NodeDescriptor> nodes,
        List<DependencyDescriptor> dependencies,
        List<FaultPolicyDescriptor> faultPolicies,
        GoalMethodDescriptor goalMethod) {}
