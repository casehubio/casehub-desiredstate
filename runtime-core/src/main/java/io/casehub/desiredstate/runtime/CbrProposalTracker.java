package io.casehub.desiredstate.runtime;

import io.casehub.neocortex.memory.cbr.CbrOutcomeData;
import io.casehub.desiredstate.api.CbrProposal;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class CbrProposalTracker {

    private final ConcurrentHashMap<String, List<CbrProposal>> pending =
        new ConcurrentHashMap<>();

    public void recordProposal(String tenancyId, CbrProposal proposal) {
        pending.computeIfAbsent(tenancyId, k -> new CopyOnWriteArrayList<>())
            .add(proposal);
    }

    public List<CbrOutcomeData> matchOutcomes(String tenancyId,
            TransitionResult result, DesiredStateGraph currentGraph) {
        List<CbrProposal> proposals = pending.remove(tenancyId);
        if (proposals == null || proposals.isEmpty()) {return List.of();}

        Instant              now      = Instant.now();
        List<CbrOutcomeData> outcomes = new ArrayList<>();

        for (CbrProposal proposal : proposals) {
            Map<String, String> nodeOutcomes = new LinkedHashMap<>();
            int                 success      = 0, failure = 0;

            for (String nodeIdStr : proposal.affectedNodeIds()) {
                NodeId      nodeId  = NodeId.of(nodeIdStr);
                StepOutcome outcome = result.outcomes().get(nodeId);
                if (outcome == null) {
                    if (!currentGraph.nodes().containsKey(nodeId)) {
                        nodeOutcomes.put(nodeIdStr, "SUPERSEDED");
                    } else {
                        nodeOutcomes.put(nodeIdStr, "ALREADY_PRESENT");
                        success++;
                    }
                } else {
                    switch (outcome) {
                        case StepOutcome.Succeeded s -> {
                            nodeOutcomes.put(nodeIdStr, "SUCCEEDED");
                            success++;
                        }
                        case StepOutcome.Failed f -> {
                            nodeOutcomes.put(nodeIdStr, "FAILED");
                            failure++;
                        }
                        case StepOutcome.Skipped s -> nodeOutcomes.put(nodeIdStr, "SKIPPED");
                        case StepOutcome.Rejected r -> {
                            nodeOutcomes.put(nodeIdStr, "REJECTED");
                            failure++;
                        }
                    }
                }
            }

            int resolved = success + failure;
            if (resolved == 0) {continue;}
            double successRate = (double) success / resolved;
            outcomes.add(new CbrOutcomeData(
                    tenancyId, proposal.sourceId(), proposal.path(),
                    nodeOutcomes, success, failure, resolved, successRate,
                    proposal.timestamp(), now));
        }
        return outcomes;}

    public void clearTenant(String tenancyId) {
        pending.remove(tenancyId);
    }
}
