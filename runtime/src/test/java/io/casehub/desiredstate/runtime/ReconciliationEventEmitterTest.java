package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class ReconciliationEventEmitterTest {

    private final ReconciliationEventEmitter emitter = new ReconciliationEventEmitter();

    @Test
    void buildReconciliationCompleted_setsTypeAndSubject() {
        var data = new ReconciliationCompletedData(
            "tenant-1", 5L, 10, 2, 1, 0, Instant.now());

        CloudEvent event = emitter.reconciliationCompleted(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.RECONCILIATION_COMPLETED);
        assertThat(event.getSubject()).isEqualTo("tenant-1");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
        assertThat(event.getSource().toString()).isEqualTo("urn:io.casehub:desiredstate");
    }

    @Test
    void buildNodeFaulted_setsNodeIdAsSubject() {
        var data = new NodeFaultedData(
            "tenant-1", "unit-cell-4-0", "UNIT",
            "PROVISION_FAILED", "timeout", 3L, "zone-frontier");

        CloudEvent event = emitter.nodeFaulted(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.NODE_FAULTED);
        assertThat(event.getSubject()).isEqualTo("unit-cell-4-0");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
        assertThat(event.getExtension("faulttype")).isEqualTo("PROVISION_FAILED");
    }

    @Test
    void buildNodeFaulted_setsDeprovisionFailedFaultType() {
        var data = new NodeFaultedData(
            "tenant-1", "unit-cell-4-0", "UNIT",
            "DEPROVISION_FAILED", "resource in use", 3L, "zone-frontier");

        CloudEvent event = emitter.nodeFaulted(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.NODE_FAULTED);
        assertThat(event.getSubject()).isEqualTo("unit-cell-4-0");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
        assertThat(event.getExtension("faulttype")).isEqualTo("DEPROVISION_FAILED");
    }

    @Test
    void buildNodeFaulted_setsApprovalRejectedFaultType() {
        var data = new NodeFaultedData(
            "tenant-1", "unit-cell-4-0", "UNIT",
            "APPROVAL_REJECTED", "not approved", 3L, "zone-frontier");

        CloudEvent event = emitter.nodeFaulted(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.NODE_FAULTED);
        assertThat(event.getSubject()).isEqualTo("unit-cell-4-0");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
        assertThat(event.getExtension("faulttype")).isEqualTo("APPROVAL_REJECTED");
    }

    @Test
    void buildNodeDrifted_setsNodeIdAsSubject() {
        var data = new NodeDriftedData(
            "tenant-1", "unit-cell-4-0", "UNIT", 4L, "zone-frontier");

        CloudEvent event = emitter.nodeDrifted(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.NODE_DRIFTED);
        assertThat(event.getSubject()).isEqualTo("unit-cell-4-0");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
    }

    @Test
    void buildNodeRecovered_setsNodeIdAsSubject() {
        var data = new NodeRecoveredData(
            "tenant-1", "unit-cell-4-0", "UNIT", 4L, "zone-frontier");

        CloudEvent event = emitter.nodeRecovered(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.NODE_RECOVERED);
        assertThat(event.getSubject()).isEqualTo("unit-cell-4-0");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
    }
}
