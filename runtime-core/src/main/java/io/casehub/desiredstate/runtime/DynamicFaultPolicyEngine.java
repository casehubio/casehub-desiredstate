package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultPolicyEvaluator;
import io.casehub.desiredstate.api.GraphMutation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class DynamicFaultPolicyEngine implements FaultPolicyEvaluator {

    private final CopyOnWriteArrayList<FaultPolicy> policies;

    public DynamicFaultPolicyEngine(List<FaultPolicy> staticPolicies) {
        this.policies = new CopyOnWriteArrayList<>(staticPolicies);
    }

    public void addPolicy(FaultPolicy policy) {
        policies.add(policy);
    }

    public void removePolicy(FaultPolicy policy) {
        policies.remove(policy);
    }

    @Override
    public List<GraphMutation<DesiredNode>> evaluate(String tenancyId, FaultEvent event,
                                                     DesiredStateGraph current, ActualState actual) {
        List<GraphMutation<DesiredNode>> allMutations = new ArrayList<>();
        for (FaultPolicy policy : policies) {
            List<GraphMutation<DesiredNode>> policyMutations = policy.onFault(tenancyId, event, current, actual);
            allMutations.addAll(policyMutations);
        }

        Map<String, List<GraphMutation<DesiredNode>>> byNode = new HashMap<>();
        List<GraphMutation<DesiredNode>> dependencyMutations = new ArrayList<>();

        for (GraphMutation<DesiredNode> mutation : allMutations) {
            String targetNodeId = GraphDiff.targetNodeId(mutation);
            if (targetNodeId != null) {
                byNode.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(mutation);
            } else {
                dependencyMutations.add(mutation);
            }
        }

        List<GraphMutation<DesiredNode>> merged = new ArrayList<>();

        for (Map.Entry<String, List<GraphMutation<DesiredNode>>> entry : byNode.entrySet()) {
            String nodeId = entry.getKey();
            List<GraphMutation<DesiredNode>> nodeMutations = entry.getValue();

            Set<GraphMutation<DesiredNode>> uniqueMutations = new LinkedHashSet<>(nodeMutations);

            if (uniqueMutations.size() > 1) {
                Iterator<GraphMutation<DesiredNode>> it = uniqueMutations.iterator();
                GraphMutation<DesiredNode> first = it.next();
                GraphMutation<DesiredNode> second = it.next();
                throw new ConflictingMutationException(nodeId, first, second);
            }

            merged.addAll(uniqueMutations);
        }

        merged.addAll(dependencyMutations);
        return merged;
    }
}
