package io.casehub.desiredstate.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.LifecycleStateEnteredData;
import io.casehub.desiredstate.api.LifecycleStateExitedData;
import io.casehub.desiredstate.api.NodeAlreadyConvergedData;
import io.casehub.desiredstate.api.NodeDriftExemptedData;
import io.casehub.desiredstate.api.NodeDriftedData;
import io.casehub.desiredstate.api.NodeFaultedData;
import io.casehub.desiredstate.api.NodeRecoveredData;
import io.casehub.desiredstate.api.ReconciliationCompletedData;
import io.casehub.neocortex.memory.cbr.CbrEventTypes;
import io.casehub.neocortex.memory.cbr.CbrOutcomeData;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

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

    public CloudEvent nodeDriftExempted(NodeDriftExemptedData data) {
        return base(DesiredStateEventTypes.NODE_DRIFT_EXEMPTED)
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

    public CloudEvent nodeAlreadyConverged(NodeAlreadyConvergedData data) {
        return base(DesiredStateEventTypes.NODE_ALREADY_CONVERGED)
                       .withSubject(data.nodeId())
                       .withExtension("tenancyid", data.tenancyId())
                       .withData("application/json", serialize(data))
                       .build();
    }

    public CloudEvent nodeSuspended(io.casehub.desiredstate.api.NodeSuspendedData data) {
        return base(DesiredStateEventTypes.NODE_SUSPENDED)
                       .withSubject(data.nodeId())
                       .withExtension("tenancyid", data.tenancyId())
                       .withData("application/json", serialize(data))
                       .build();
    }

    public CloudEvent nodeResumed(io.casehub.desiredstate.api.NodeResumedData data) {
        return base(DesiredStateEventTypes.NODE_RESUMED)
                       .withSubject(data.nodeId())
                       .withExtension("tenancyid", data.tenancyId())
                       .withData("application/json", serialize(data))
                       .build();
    }


    public CloudEvent cbrOutcome(CbrOutcomeData data) {
        return base(CbrEventTypes.CBR_OUTCOME)
                       .withSubject(data.sourceId())
                       .withExtension("tenancyid", data.tenancyId())
                       .withExtension("cbrpath", data.path().name().toLowerCase())
                       .withExtension("successrate", String.valueOf(data.successRate()))
                       .withData("application/json", serialize(data))
                       .build();
    }

    public CloudEvent lifecycleStateEntered(LifecycleStateEnteredData data) {
        var builder = base(DesiredStateEventTypes.LIFECYCLE_STATE_ENTERED)
                              .withSubject(data.nodeId())
                              .withExtension("tenancyid", data.tenancyId())
                              .withExtension("nodetype", data.nodeType())
                              .withExtension("state", data.state())
                              .withData("application/json", serialize(data));
        if (data.customEventType() != null) {
            builder.withExtension("customeventtype", data.customEventType());
        }
        return builder.build();
    }

    public CloudEvent lifecycleStateExited(LifecycleStateExitedData data) {
        var builder = base(DesiredStateEventTypes.LIFECYCLE_STATE_EXITED)
                              .withSubject(data.nodeId())
                              .withExtension("tenancyid", data.tenancyId())
                              .withExtension("nodetype", data.nodeType())
                              .withExtension("state", data.state())
                              .withData("application/json", serialize(data));
        if (data.customEventType() != null) {
            builder.withExtension("customeventtype", data.customEventType());
        }
        return builder.build();
    }


    public CloudEvent planAwaitingApproval(io.casehub.desiredstate.api.PlanAwaitingApprovalData data) {
        return base(DesiredStateEventTypes.PLAN_AWAITING_APPROVAL)
                       .withExtension("tenancyid", data.tenancyId())
                       .withData("application/json", serialize(data))
                       .build();
    }

    public CloudEvent planApproved(io.casehub.desiredstate.api.PlanApprovedData data) {
        return base(DesiredStateEventTypes.PLAN_APPROVED)
                       .withExtension("tenancyid", data.tenancyId())
                       .withData("application/json", serialize(data))
                       .build();
    }


    public CloudEvent planRejected(io.casehub.desiredstate.api.PlanRejectedData data) {
        return base(DesiredStateEventTypes.PLAN_REJECTED)
                       .withExtension("tenancyid", data.tenancyId())
                       .withData("application/json", serialize(data))
                       .build();
    }

    public CloudEvent planInvalidated(io.casehub.desiredstate.api.PlanInvalidatedData data) {
        return base(DesiredStateEventTypes.PLAN_INVALIDATED)
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
