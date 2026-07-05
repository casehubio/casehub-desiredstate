package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpSituationRecompilerTest {

    @Test
    void shouldReturnEmpty() {
        NoOpSituationRecompiler recompiler = new NoOpSituationRecompiler();

        DesiredStateGraph current = ImmutableDesiredStateGraph.empty();
        ActiveSituation situation = new ActiveSituation(
            "sit-1", "zone-A", "tenant-1", 0.95,
            Map.of("nodeId", "node-123", "reason", "persistent-drift"),
            Instant.now().minusSeconds(300), Instant.now(), 5);

        Optional<DesiredStateGraph> result = recompiler.recompile(current, situation, null);

        assertThat(result).isEmpty();
    }
}
