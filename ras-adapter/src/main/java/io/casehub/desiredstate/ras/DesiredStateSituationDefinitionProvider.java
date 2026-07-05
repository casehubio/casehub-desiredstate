package io.casehub.desiredstate.ras;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.ras.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class DesiredStateSituationDefinitionProvider implements SituationDefinitionProvider {

    @Override
    public List<SituationRegistration> registrations() {
        List<SituationRegistration> registrations = new ArrayList<>();

        // Per-node: repeated failure (default extractor — subject = nodeId)
        registrations.add(new SituationRegistration(
            new SituationDefinition(
                "desiredstate.repeated-failure",
                Set.of(DesiredStateEventTypes.NODE_FAULTED,
                       DesiredStateEventTypes.NODE_RECOVERED),
                Duration.ofMinutes(10),
                null,
                new ChainMode.Streak(NodeFaultGanglion.ID, 3),
                new CaseTriggerConfig("desiredstate", "replan", "1.0", Map.of()),
                new TriggerMode.FireOnce()),
            null));  // null = default extractor

        // Per-node: persistent drift
        registrations.add(new SituationRegistration(
            new SituationDefinition(
                "desiredstate.persistent-drift",
                Set.of(DesiredStateEventTypes.NODE_DRIFTED,
                       DesiredStateEventTypes.NODE_RECOVERED),
                Duration.ofMinutes(15),
                null,
                new ChainMode.Count(PersistentDriftGanglion.ID, 3),
                new CaseTriggerConfig("desiredstate", "escalate", "1.0", Map.of()),
                new TriggerMode.FireOnce()),
            null));

        // Zone-level: zone degradation (custom extractor)
        registrations.add(new SituationRegistration(
            new SituationDefinition(
                "desiredstate.zone-degradation",
                Set.of(DesiredStateEventTypes.NODE_FAULTED,
                       DesiredStateEventTypes.NODE_RECOVERED),
                Duration.ofMinutes(30),
                null,
                new ChainMode.Rate(Set.of(NodeFaultGanglion.ID), 0.6, 10),
                new CaseTriggerConfig("desiredstate", "escalate", "1.0", Map.of()),
                new TriggerMode.Repeating(Duration.ofMinutes(5))),
            new DesiredStateCorrelationKeyExtractor()));

        return registrations;
    }
}
