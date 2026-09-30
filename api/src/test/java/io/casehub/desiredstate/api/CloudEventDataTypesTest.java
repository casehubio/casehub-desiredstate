package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudEventDataTypesTest {

    @Test
    void reconciliationCompletedData_nullChecks() {
        assertThatThrownBy(() -> new ReconciliationCompletedData(
                null, 1L, 10, 2, 1, 0, Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reconciliationCompletedData_validConstruction() {
        var data = new ReconciliationCompletedData(
            "tenant-1", 5L, 10, 2, 1, 0, Instant.now());
        assertThat(data.tenancyId()).isEqualTo("tenant-1");
        assertThat(data.graphVersion()).isEqualTo(5L);
    }

    @Test
    void reconciliationCompletedData_carriesNodeOutcomes() {
        var outcomes = Map.of("node-1", "SUCCEEDED", "node-2", "FAILED");
        var data = new ReconciliationCompletedData(
                "tenant-1", 5L, 10, 2, 1, 0, 0, 0, outcomes, Instant.now());
        assertThat(data.nodeOutcomes()).isEqualTo(outcomes);
    }

    @Test
    void reconciliationCompletedData_nodeOutcomesDefensiveCopy() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("node-1", "SUCCEEDED");
        var data = new ReconciliationCompletedData(
                "tenant-1", 5L, 10, 1, 0, 0, 0, 0, mutable, Instant.now());
        assertThatThrownBy(() -> data.nodeOutcomes().put("node-2", "FAILED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reconciliationCompletedData_backwardCompatConstructorsDefaultToEmptyOutcomes() {
        var data7 = new ReconciliationCompletedData(
                "tenant-1", 5L, 10, 2, 1, 0, Instant.now());
        assertThat(data7.nodeOutcomes()).isEmpty();

        var data9 = new ReconciliationCompletedData(
                "tenant-1", 5L, 10, 2, 1, 0, 0, 0, Instant.now());
        assertThat(data9.nodeOutcomes()).isEmpty();
    }

    @Test
    void reconciliationCompletedData_nodeOutcomesThresholdConstant() {
        assertThat(ReconciliationCompletedData.NODE_OUTCOMES_THRESHOLD).isGreaterThan(0);
    }


    @Test
    void nodeFaultedData_carriesParentNodeId() {
        var data = new NodeFaultedData(
            "tenant-1", "unit-cell-4-0", "UNIT",
            "PROVISION_FAILED", "timeout", 3L, "zone-frontier");
        assertThat(data.parentNodeId()).isEqualTo("zone-frontier");
    }

    @Test
    void nodeFaultedData_nullParentForRootNodes() {
        var data = new NodeFaultedData(
            "tenant-1", "zone-frontier", "ZONE",
            "NODE_DEGRADED", "member missing", 3L, null);
        assertThat(data.parentNodeId()).isNull();
    }

    @Test
    void nodeRecoveredData_validConstruction() {
        var data = new NodeRecoveredData(
            "tenant-1", "unit-cell-4-0", "UNIT", 4L, "zone-frontier");
        assertThat(data.nodeId()).isEqualTo("unit-cell-4-0");
    }

    @Test
    void eventTypeConstants() {
        assertThat(DesiredStateEventTypes.RECONCILIATION_COMPLETED)
            .isEqualTo("io.casehub.desiredstate.reconciliation.completed");
        assertThat(DesiredStateEventTypes.NODE_FAULTED)
            .isEqualTo("io.casehub.desiredstate.node.faulted");
        assertThat(DesiredStateEventTypes.NODE_DRIFTED)
            .isEqualTo("io.casehub.desiredstate.node.drifted");
        assertThat(DesiredStateEventTypes.NODE_RECOVERED)
            .isEqualTo("io.casehub.desiredstate.node.recovered");
    }
}
