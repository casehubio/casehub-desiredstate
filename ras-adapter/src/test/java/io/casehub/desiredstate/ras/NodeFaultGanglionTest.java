package io.casehub.desiredstate.ras;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NodeFaultGanglionTest {

    private final NodeFaultGanglion ganglion = new NodeFaultGanglion();

    @Test
    void handlesCorrectEventTypes() {
        assertThat(ganglion.handledEventTypes()).containsExactlyInAnyOrder(
            DesiredStateEventTypes.NODE_FAULTED,
            DesiredStateEventTypes.NODE_RECOVERED);
    }

    @Test
    void faultEvent_returnsDetected() {
        CloudEvent event = buildEvent(DesiredStateEventTypes.NODE_FAULTED, "unit-1");
        SituationContext ctx = SituationContext.initial(
            "test-situation", "unit-1", "tenant-1", Instant.now());

        DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test
    void recoveredEvent_returnsAnti() {
        CloudEvent event = buildEvent(DesiredStateEventTypes.NODE_RECOVERED, "unit-1");
        SituationContext ctx = SituationContext.initial(
            "test-situation", "unit-1", "tenant-1", Instant.now());

        DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.ANTI);
    }

    @Test
    void unrelatedEventType_returnsNoise() {
        CloudEvent event = buildEvent("io.casehub.other.event", "unit-1");
        SituationContext ctx = SituationContext.initial(
            "test-situation", "unit-1", "tenant-1", Instant.now());

        DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    private CloudEvent buildEvent(String type, String subject) {
        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("test"))
            .withType(type)
            .withSubject(subject)
            .withTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withExtension("tenancyid", "tenant-1")
            .build();
    }
}
