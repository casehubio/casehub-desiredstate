package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;

/**
 * Evaluates all registered fault policies against a fault event and merges their mutations.
 * Detects conflicts when multiple policies propose incompatible mutations for the same node.
 */
@ApplicationScoped
public class FaultPolicyEngine {

    private final List<FaultPolicy> policies;

    public FaultPolicyEngine(List<FaultPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    /**
     * Runs all fault policies against the event and current graph.
     * Merges mutations, deduplicating identical mutations.
     * Throws ConflictingMutationException if multiple policies propose different mutations for the same node.
     *
     * @param event   the fault event
     * @param current the current desired state graph
     * @param actual  the actual state snapshot
     * @return merged list of graph mutations
     * @throws ConflictingMutationException if conflicting mutations are detected
     */
    public List<GraphMutation> evaluate(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        // Collect all mutations from all policies
        List<GraphMutation> allMutations = new ArrayList<>();
        for (FaultPolicy policy : policies) {
            List<GraphMutation> policyMutations = policy.onFault(event, current, actual);
            allMutations.addAll(policyMutations);
        }

        // Group by target NodeId
        Map<NodeId, List<GraphMutation>> byNode = new HashMap<>();
        List<GraphMutation> dependencyMutations = new ArrayList<>();

        for (GraphMutation mutation : allMutations) {
            NodeId targetNodeId = getTargetNodeId(mutation);
            if (targetNodeId != null) {
                byNode.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(mutation);
            } else {
                // Dependency mutations have no single node target
                dependencyMutations.add(mutation);
            }
        }

        // Merge mutations per node
        List<GraphMutation> merged = new ArrayList<>();

        for (Map.Entry<NodeId, List<GraphMutation>> entry : byNode.entrySet()) {
            NodeId nodeId = entry.getKey();
            List<GraphMutation> nodeMutations = entry.getValue();

            // Deduplicate
            Set<GraphMutation> uniqueMutations = new LinkedHashSet<>(nodeMutations);

            if (uniqueMutations.size() > 1) {
                // Conflicting mutations
                Iterator<GraphMutation> it = uniqueMutations.iterator();
                GraphMutation first = it.next();
                GraphMutation second = it.next();
                throw new ConflictingMutationException(nodeId, first, second);
            }

            merged.addAll(uniqueMutations);
        }

        // Add dependency mutations (no conflict checking needed)
        merged.addAll(dependencyMutations);

        return merged;
    }

    /**
     * Extracts the target NodeId from a mutation, or null for dependency mutations.
     */
    private NodeId getTargetNodeId(GraphMutation mutation) {
        return switch (mutation) {
            case GraphMutation.AddNode add -> add.node().id();
            case GraphMutation.RemoveNode remove -> remove.id();
            case GraphMutation.UpdateNode update -> update.id();
            case GraphMutation.AddDependency ignored -> null;
            case GraphMutation.RemoveDependency ignored -> null;
        };
    }
}
