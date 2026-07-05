package io.casehub.desiredstate.ras;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class DesiredStateCorrelationKeyExtractorTest {

    private final DesiredStateCorrelationKeyExtractor extractor =
        new DesiredStateCorrelationKeyExtractor();

    @Test
    void extractsParentNodeIdFromData() {
        // Build event with parentNodeId in JSON data
        String json = """
            {"tenancyId":"t1","nodeId":"unit-1","nodeType":"UNIT",
             "faultType":"PROVISION_FAILED","reason":"timeout",
             "graphVersion":3,"parentNodeId":"zone-frontier"}""";

        CloudEvent event = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("test"))
            .withType("io.casehub.desiredstate.node.faulted")
            .withSubject("unit-1")
            .withTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withData("application/json", json.getBytes())
            .build();

        String key = extractor.extract(event);
        assertThat(key).isEqualTo("zone-frontier");
    }

    @Test
    void fallsBackToSubjectWhenNoParentNodeId() {
        String json = """
            {"tenancyId":"t1","nodeId":"zone-frontier","nodeType":"ZONE",
             "faultType":"NODE_DEGRADED","reason":"member missing",
             "graphVersion":3,"parentNodeId":null}""";

        CloudEvent event = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("test"))
            .withType("io.casehub.desiredstate.node.faulted")
            .withSubject("zone-frontier")
            .withData("application/json", json.getBytes())
            .build();

        String key = extractor.extract(event);
        assertThat(key).isEqualTo("zone-frontier");
    }
}
