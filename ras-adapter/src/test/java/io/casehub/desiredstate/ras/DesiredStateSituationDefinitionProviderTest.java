package io.casehub.desiredstate.ras;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DesiredStateSituationDefinitionProviderTest {

    private Map<String, SituationRegistration> registrationsByName;

    @BeforeEach
    void setUp() {
        var provider = new DesiredStateSituationDefinitionProvider();
        List<SituationRegistration> registrations = provider.registrations();
        registrationsByName = registrations.stream()
                .collect(Collectors.toMap(r -> r.definition().situationId(), Function.identity()));
    }

    @Test
    void provides_threeRegistrations() {
        assertThat(registrationsByName).hasSize(3);
    }

    @Test
    void repeatedFailure_configuredCorrectly() {
        var reg = registrationsByName.get("desiredstate.repeated-failure");
        assertThat(reg).isNotNull();

        var def = reg.definition();
        assertThat(def.eventTypes()).containsExactlyInAnyOrder(
                DesiredStateEventTypes.NODE_FAULTED,
                DesiredStateEventTypes.NODE_RECOVERED);
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(10));
        assertThat(def.chainMode()).isInstanceOf(ChainMode.Streak.class);
        var streak = (ChainMode.Streak) def.chainMode();
        assertThat(streak.ganglionId()).isEqualTo(NodeFaultGanglion.ID);
        assertThat(streak.requiredCount()).isEqualTo(3);
        assertThat(def.triggerAction()).isInstanceOf(TriggerAction.CreateCase.class);
        var trigger = (TriggerAction.CreateCase) def.triggerAction();
        assertThat(trigger.config().caseNamespace()).isEqualTo("desiredstate");
        assertThat(trigger.config().caseName()).isEqualTo("replan");
        assertThat(def.triggerMode()).isInstanceOf(TriggerMode.FireOnce.class);
        assertThat(reg.correlationKeyExtractor()).isNotInstanceOf(DesiredStateCorrelationKeyExtractor.class);
    }

    @Test
    void persistentDrift_configuredCorrectly() {
        var reg = registrationsByName.get("desiredstate.persistent-drift");
        assertThat(reg).isNotNull();

        var def = reg.definition();
        assertThat(def.eventTypes()).containsExactlyInAnyOrder(
                DesiredStateEventTypes.NODE_DRIFTED,
                DesiredStateEventTypes.NODE_RECOVERED);
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(def.chainMode()).isInstanceOf(ChainMode.Count.class);
        var count = (ChainMode.Count) def.chainMode();
        assertThat(count.ganglionId()).isEqualTo(PersistentDriftGanglion.ID);
        assertThat(count.requiredCount()).isEqualTo(3);
        assertThat(def.triggerAction()).isInstanceOf(TriggerAction.CreateCase.class);
        var trigger = (TriggerAction.CreateCase) def.triggerAction();
        assertThat(trigger.config().caseName()).isEqualTo("escalate");
        assertThat(def.triggerMode()).isInstanceOf(TriggerMode.FireOnce.class);
        assertThat(reg.correlationKeyExtractor()).isNotInstanceOf(DesiredStateCorrelationKeyExtractor.class);
    }

    @Test
    void zoneDegradation_configuredWithCustomExtractor() {
        var reg = registrationsByName.get("desiredstate.zone-degradation");
        assertThat(reg).isNotNull();

        var def = reg.definition();
        assertThat(def.eventTypes()).containsExactlyInAnyOrder(
                DesiredStateEventTypes.NODE_FAULTED,
                DesiredStateEventTypes.NODE_RECOVERED);
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(30));
        assertThat(def.chainMode()).isInstanceOf(ChainMode.Rate.class);
        var rate = (ChainMode.Rate) def.chainMode();
        assertThat(rate.minRate()).isEqualTo(0.6);
        assertThat(rate.windowSize()).isEqualTo(10);
        assertThat(def.triggerAction()).isInstanceOf(TriggerAction.CreateCase.class);
        var trigger = (TriggerAction.CreateCase) def.triggerAction();
        assertThat(trigger.config().caseName()).isEqualTo("escalate");
        assertThat(def.triggerMode()).isInstanceOf(TriggerMode.Repeating.class);
        var repeating = (TriggerMode.Repeating) def.triggerMode();
        assertThat(repeating.cooldown()).isEqualTo(Duration.ofMinutes(5));
        assertThat(reg.correlationKeyExtractor()).isInstanceOf(DesiredStateCorrelationKeyExtractor.class);
    }
}
