package io.casehub.desiredstate.runtime;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SituationRecompilerDispatchTest {

    @Test
    void toActiveSituationMapsFieldsCorrectly() {
        var ctx = SituationContext.initial("sit-1", "key-1", "t1", Instant.parse("2026-09-14T10:00:00Z"))
                .withDetection(new DetectionResult("g1", 0.85, DetectionSignal.DETECTED, Map.of()),
                        Instant.parse("2026-09-14T10:01:00Z"));
        var event = new SituationChangeEvent("t1", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, ctx, Map.of("k", "v"));

        var result = SituationRecompilerDispatchCore.toActiveSituation(event);

        assertThat(result.situationId()).isEqualTo("sit-1");
        assertThat(result.correlationKey()).isEqualTo("key-1");
        assertThat(result.tenancyId()).isEqualTo("t1");
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.evidence()).containsEntry("k", "v");
        assertThat(result.since()).isEqualTo(Instant.parse("2026-09-14T10:00:00Z"));
        assertThat(result.lastSignal()).isEqualTo(Instant.parse("2026-09-14T10:01:00Z"));
    }

    @Test
    void toActiveSituationDefaultsConfidenceWhenNoDetections() {
        var ctx = SituationContext.initial("sit-1", "key-1", "t1", Instant.now());
        var event = new SituationChangeEvent("t1", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, ctx);

        var result = SituationRecompilerDispatchCore.toActiveSituation(event);

        assertThat(result.confidence()).isEqualTo(0.5);
    }

    @Test
    void toActiveSituationUsesLastDetectionConfidence() {
        var ctx = SituationContext.initial("sit-1", "key-1", "t1", Instant.now())
                .withDetection(new DetectionResult("g1", 0.6, DetectionSignal.DETECTED, Map.of()), Instant.now())
                .withDetection(new DetectionResult("g1", 0.95, DetectionSignal.DETECTED, Map.of()), Instant.now());
        var event = new SituationChangeEvent("t1", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, ctx);

        var result = SituationRecompilerDispatchCore.toActiveSituation(event);

        assertThat(result.confidence()).isEqualTo(0.95);
    }
}
