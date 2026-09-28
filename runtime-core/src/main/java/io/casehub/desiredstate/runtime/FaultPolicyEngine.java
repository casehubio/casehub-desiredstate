package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GraphMutation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public List<GraphMutation<DesiredNode>> evaluate(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
        List<GraphMutation<DesiredNode>> allMutations = new ArrayList<>();
        for (FaultPolicy policy : policies) {
            List<GraphMutation<DesiredNode>> policyMutations = policy.onFault(tenancyId, event, current, actual);
            allMutations.addAll(policyMutations);
        }

        Map<String, List<GraphMutation<DesiredNode>>> byNode              = new HashMap<>();
        List<GraphMutation<DesiredNode>>              dependencyMutations = new ArrayList<>();

        for (GraphMutation<DesiredNode> mutation : allMutations) {
            String targetNodeId = getTargetNodeId(mutation);
            if (targetNodeId != null) {
                byNode.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(mutation);
            } else {
                dependencyMutations.add(mutation);
            }
        }

        List<GraphMutation<DesiredNode>> merged = new ArrayList<>();

        for (Map.Entry<String, List<GraphMutation<DesiredNode>>> entry : byNode.entrySet()) {
            String                           nodeId        = entry.getKey();
            List<GraphMutation<DesiredNode>> nodeMutations = entry.getValue();

            Set<GraphMutation<DesiredNode>> uniqueMutations = new LinkedHashSet<>(nodeMutations);

            if (uniqueMutations.size() > 1) {
                Iterator<GraphMutation<DesiredNode>> it     = uniqueMutations.iterator();
                GraphMutation<DesiredNode>           first  = it.next();
                GraphMutation<DesiredNode>           second = it.next();
                throw new ConflictingMutationException(nodeId, first, second);
            }

            merged.addAll(uniqueMutations);
        }

        merged.addAll(dependencyMutations);

        return merged;
    }

    private String getTargetNodeId(GraphMutation<?> mutation) {return GraphDiff.targetNodeId(mutation);}
}
