package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExemptionTest {

    private static final NodeId NODE = NodeId.of("n1");
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void shouldRevert_onDuration_notExpired() {
        var spec = new ExemptionSpec(
            new RevertCondition.OnDuration(Duration.ofMinutes(30)), Map.of());
        var exemption = new Exemption(NODE, spec, NOW, NOW.plus(Duration.ofMinutes(30)));
        assertFalse(exemption.shouldRevert(NOW.plusSeconds(60), NodeStatus.DRIFTED));
    }

    @Test
    void shouldRevert_onDuration_expired() {
        var spec = new ExemptionSpec(
            new RevertCondition.OnDuration(Duration.ofMinutes(30)), Map.of());
        var exemption = new Exemption(NODE, spec, NOW, NOW.plus(Duration.ofMinutes(30)));
        assertTrue(exemption.shouldRevert(NOW.plus(Duration.ofMinutes(31)), NodeStatus.DRIFTED));
    }

    @Test
    void shouldRevert_onStatusChange_statusMatches() {
        var spec = new ExemptionSpec(
            new RevertCondition.OnStatusChange(Set.of(NodeStatus.PRESENT)), Map.of());
        var exemption = new Exemption(NODE, spec, NOW, null);
        assertTrue(exemption.shouldRevert(NOW, NodeStatus.PRESENT));
    }

    @Test
    void shouldRevert_onStatusChange_statusDoesNotMatch() {
        var spec = new ExemptionSpec(
            new RevertCondition.OnStatusChange(Set.of(NodeStatus.PRESENT)), Map.of());
        var exemption = new Exemption(NODE, spec, NOW, null);
        assertFalse(exemption.shouldRevert(NOW, NodeStatus.DRIFTED));
    }

    @Test
    void shouldRevert_never() {
        var spec = new ExemptionSpec(new RevertCondition.Never(), Map.of());
        var exemption = new Exemption(NODE, spec, NOW, null);
        assertFalse(exemption.shouldRevert(NOW.plus(Duration.ofDays(365)), NodeStatus.DRIFTED));
    }

    @Test
    void shouldRevert_expiresAt_overridesDuration() {
        var spec = new ExemptionSpec(
            new RevertCondition.OnDuration(Duration.ofHours(1)), Map.of());
        var exemption = new Exemption(NODE, spec, NOW, NOW.plusSeconds(10));
        assertTrue(exemption.shouldRevert(NOW.plusSeconds(11), NodeStatus.DRIFTED));
    }

    @Test
    void rejectsNullNodeId() {
        assertThrows(NullPointerException.class,
            () -> new Exemption(null, new ExemptionSpec(new RevertCondition.Never(), Map.of()), NOW, null));
    }

    @Test
    void rejectsNullSpec() {
        assertThrows(NullPointerException.class,
            () -> new Exemption(NODE, null, NOW, null));
    }
}
