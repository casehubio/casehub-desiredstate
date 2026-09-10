package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.plugin.model.PluginRasDef;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlPluginRasRegistrarTest {

    @Test
    void registersSituationFromPluginYaml() {
        var rasDef = new PluginRasDef(List.of(
            new PluginRasDef.Situation("crash-loop",
                List.of("NODE_FAULTED", "NODE_RECOVERED"),
                "10m",
                Map.of("streak", 3),
                "create-case", "fire-once", "${spec.namespace}")));

        var registrar = new YamlPluginRasRegistrar("k8s-deployment", rasDef);
        var registrations = registrar.registrations();

        assertThat(registrations).hasSize(1);
        var def = registrations.get(0).definition();
        assertThat(def.situationId()).isEqualTo("plugin.k8s-deployment.crash-loop");
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(10));
        assertThat(def.eventTypes()).containsExactlyInAnyOrder(
            DesiredStateEventTypes.NODE_FAULTED,
            DesiredStateEventTypes.NODE_RECOVERED);
    }

    @Test
    void streakChainModeMapsToCorrectGanglion() {
        var chainMode = YamlPluginRasRegistrar.mapChainMode(
            Map.of("streak", 3),
            List.of("NODE_FAULTED", "NODE_RECOVERED"));

        assertThat(chainMode).isInstanceOf(ChainMode.Streak.class);
        var streak = (ChainMode.Streak) chainMode;
        assertThat(streak.ganglionId()).isEqualTo("desiredstate-node-fault");
        assertThat(streak.requiredCount()).isEqualTo(3);
    }

    @Test
    void countChainMode() {
        var chainMode = YamlPluginRasRegistrar.mapChainMode(
            Map.of("count", 5),
            List.of("NODE_DRIFTED", "NODE_RECOVERED"));

        assertThat(chainMode).isInstanceOf(ChainMode.Count.class);
        var count = (ChainMode.Count) chainMode;
        assertThat(count.ganglionId()).isEqualTo("desiredstate-persistent-drift");
        assertThat(count.requiredCount()).isEqualTo(5);
    }

    @Test
    void rateChainMode() {
        var chainMode = YamlPluginRasRegistrar.mapChainMode(
            Map.of("rate", Map.of("threshold", 0.8, "window", 10)),
            List.of("NODE_FAULTED", "NODE_RECOVERED"));

        assertThat(chainMode).isInstanceOf(ChainMode.Rate.class);
        var rate = (ChainMode.Rate) chainMode;
        assertThat(rate.minRate()).isEqualTo(0.8);
        assertThat(rate.windowSize()).isEqualTo(10);
        assertThat(rate.ganglia()).contains("desiredstate-node-fault");
    }

    @Test
    void mixedEventsWithStreakFails() {
        assertThatThrownBy(() -> YamlPluginRasRegistrar.mapChainMode(
            Map.of("streak", 3),
            List.of("NODE_FAULTED", "NODE_DRIFTED")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one ganglion");
    }

    @Test
    void createCaseTriggerAction() {
        var action = YamlPluginRasRegistrar.mapTriggerAction("create-case");
        assertThat(action).isInstanceOf(TriggerAction.CreateCase.class);
    }

    @Test
    void notifyOnlyTriggerAction() {
        var action = YamlPluginRasRegistrar.mapTriggerAction("emit-event");
        assertThat(action).isInstanceOf(TriggerAction.NotifyOnly.class);
    }

    @Test
    void fireOnceTriggerMode() {
        var mode = YamlPluginRasRegistrar.mapTriggerMode("fire-once");
        assertThat(mode).isInstanceOf(TriggerMode.FireOnce.class);
    }

    @Test
    void repeatingTriggerMode() {
        var mode = YamlPluginRasRegistrar.mapTriggerMode("repeating:5m");
        assertThat(mode).isInstanceOf(TriggerMode.Repeating.class);
        assertThat(((TriggerMode.Repeating) mode).cooldown())
            .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void mapsEventTypes() {
        var events = YamlPluginRasRegistrar.mapEventTypes(
            List.of("NODE_FAULTED", "NODE_RECOVERED"));
        assertThat(events).containsExactlyInAnyOrder(
            DesiredStateEventTypes.NODE_FAULTED,
            DesiredStateEventTypes.NODE_RECOVERED);
    }

    @Test
    void unknownEventTypeThrows() {
        assertThatThrownBy(() ->
            YamlPluginRasRegistrar.mapEventTypes(List.of("UNKNOWN_EVENT")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UNKNOWN_EVENT");
    }

    @Test
    void parsesDurations() {
        assertThat(YamlPluginRasRegistrar.parseDuration("10m"))
            .isEqualTo(Duration.ofMinutes(10));
        assertThat(YamlPluginRasRegistrar.parseDuration("30s"))
            .isEqualTo(Duration.ofSeconds(30));
        assertThat(YamlPluginRasRegistrar.parseDuration("2h"))
            .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void multipleSituationsRegistered() {
        var rasDef = new PluginRasDef(List.of(
            new PluginRasDef.Situation("sit-1",
                List.of("NODE_FAULTED", "NODE_RECOVERED"), "5m",
                Map.of("streak", 2), "create-case", "fire-once", null),
            new PluginRasDef.Situation("sit-2",
                List.of("NODE_DRIFTED", "NODE_RECOVERED"), "15m",
                Map.of("count", 5), "emit-event", "fire-once", null)));

        var registrar = new YamlPluginRasRegistrar("test-type", rasDef);
        var registrations = registrar.registrations();

        assertThat(registrations).hasSize(2);
        assertThat(registrations.get(0).definition().situationId())
            .isEqualTo("plugin.test-type.sit-1");
        assertThat(registrations.get(1).definition().situationId())
            .isEqualTo("plugin.test-type.sit-2");
    }
}
