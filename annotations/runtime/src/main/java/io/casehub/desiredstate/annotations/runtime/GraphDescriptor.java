package io.casehub.desiredstate.annotations.runtime;

import io.quarkus.runtime.annotations.RecordableConstructor;

import java.util.List;

public record GraphDescriptor(
        String namespace,
        String name,
        String interfaceName,
        String implClassName,
        List<NodeDescriptor> nodes,
        List<DependencyDescriptor> dependencies,
        List<FaultPolicyDescriptor> faultPolicies,
        GoalMethodDescriptor goalMethod,
        List<GraphRuleDescriptor> graphRules,
        List<GraphInvariantDescriptor> graphInvariants,
        List<OrderingConstraintDescriptor> orderingConstraints) {

    @RecordableConstructor
    public GraphDescriptor {}

    public GraphDescriptor(
            String namespace, String name,
            String interfaceName, String implClassName,
            List<NodeDescriptor> nodes, List<DependencyDescriptor> dependencies,
            List<FaultPolicyDescriptor> faultPolicies, GoalMethodDescriptor goalMethod,
            List<GraphRuleDescriptor> graphRules, List<GraphInvariantDescriptor> graphInvariants) {
        this(namespace, name, interfaceName, implClassName, nodes, dependencies,
             faultPolicies, goalMethod, graphRules, graphInvariants, List.of());
    }
}
