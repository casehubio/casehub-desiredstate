package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Builds CloudEvents from reconciliation cycle data.
 * <p>
 * Pure function — no side effects, no state. Takes data records and produces CloudEvents
 * with correct type, subject, source, and JSON-serialized data payloads.
 */
public class ReconciliationEventEmitter {

    private static final URI SOURCE = URI.create("urn:io.casehub:desiredstate");
    private static final ObjectMapper OBJECT_MAPPER;

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
    }

    public CloudEvent reconciliationCompleted(ReconciliationCompletedData data) {
        return base(DesiredStateEventTypes.RECONCILIATION_COMPLETED)
            .withSubject(data.tenancyId())
            .withExtension("tenancyid", data.tenancyId())
            .withData("application/json", serialize(data))
            .build();
    }

    public CloudEvent nodeFaulted(NodeFaultedData data) {
        return base(DesiredStateEventTypes.NODE_FAULTED)
            .withSubject(data.nodeId())
            .withExtension("tenancyid", data.tenancyId())
            .withExtension("faulttype", data.faultType())
            .withData("application/json", serialize(data))
            .build();
    }

    public CloudEvent nodeDrifted(NodeDriftedData data) {
        return base(DesiredStateEventTypes.NODE_DRIFTED)
            .withSubject(data.nodeId())
            .withExtension("tenancyid", data.tenancyId())
            .withData("application/json", serialize(data))
            .build();
    }

    public CloudEvent nodeRecovered(NodeRecoveredData data) {
        return base(DesiredStateEventTypes.NODE_RECOVERED)
            .withSubject(data.nodeId())
            .withExtension("tenancyid", data.tenancyId())
            .withData("application/json", serialize(data))
            .build();
    }

    private CloudEventBuilder base(String type) {
        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(SOURCE)
            .withType(type)
            .withTime(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private byte[] serialize(Object data) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize CloudEvent data", e);
        }
    }
}
