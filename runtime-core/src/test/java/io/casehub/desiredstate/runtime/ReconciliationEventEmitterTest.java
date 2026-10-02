package io.casehub.desiredstate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.casehub.desiredstate.api.NodeResumedData;
import io.casehub.desiredstate.api.NodeSuspendedData;
import io.casehub.desiredstate.api.PlanApproval;
import io.casehub.desiredstate.api.PlanApprovedData;
import io.casehub.desiredstate.api.PlanAwaitingApprovalData;
import io.casehub.desiredstate.api.PlanInvalidatedData;
import io.casehub.desiredstate.api.PlanRejectedData;
import io.casehub.desiredstate.api.ReconciliationCompletedData;
import io.casehub.neocortex.memory.cbr.CbrEventTypes;
import io.casehub.neocortex.memory.cbr.CbrOutcomeData;
import io.casehub.neocortex.memory.cbr.CbrPath;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReconciliationEventEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private ReconciliationEventEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new ReconciliationEventEmitter();
    }

    private JsonNode payload(CloudEvent event) throws Exception {
        return MAPPER.readTree(event.getData().toBytes());
    }

    private void assertCommonFields(CloudEvent event, String expectedType) {
        assertNotNull(event.getId());
        assertEquals(expectedType, event.getType());
        assertEquals("urn:io.casehub:desiredstate", event.getSource().toString());
        assertNotNull(event.getTime());
        assertNotNull(event.getData());
    }

    // ── reconciliationCompleted ────────────────────────────────────────

    @Test
    void reconciliationCompleted_setsTypeAndSubject() throws Exception {
        var data = new ReconciliationCompletedData("tenant-1", 5, 10, 3, 2, 1, 0, 0, 0, Map.of(), Instant.now());

        CloudEvent event = emitter.reconciliationCompleted(data);

        assertCommonFields(event, DesiredStateEventTypes.RECONCILIATION_COMPLETED);
        assertEquals("tenant-1", event.getSubject());
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("tenant-1", payload(event).get("tenancyId").asText());
    }

    // ── nodeFaulted ────────────────────────────────────────────────────

    @Test
    void nodeFaulted_setsTypeSubjectAndFaultExtension() throws Exception {
        var data = new NodeFaultedData("tenant-1", "node-a", "server", "DRIFT", "spec mismatch", 3, null);

        CloudEvent event = emitter.nodeFaulted(data);

        assertCommonFields(event, DesiredStateEventTypes.NODE_FAULTED);
        assertEquals("node-a", event.getSubject());
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("DRIFT", event.getExtension("faulttype"));
        assertEquals("node-a", payload(event).get("nodeId").asText());
    }

    // ── nodeDrifted ────────────────────────────────────────────────────

    @Test
    void nodeDrifted_setsTypeAndSubject() throws Exception {
        var data = new NodeDriftedData("tenant-1", "node-b", "database", 2, null);

        CloudEvent event = emitter.nodeDrifted(data);

        assertCommonFields(event, DesiredStateEventTypes.NODE_DRIFTED);
        assertEquals("node-b", event.getSubject());
        assertEquals("tenant-1", event.getExtension("tenancyid"));
    }

    // ── nodeDriftExempted ──────────────────────────────────────────────

    @Test
    void nodeDriftExempted_setsTypeAndSerializesData() throws Exception {
        var data = new NodeDriftExemptedData("tenant-1", "node-c", "cache", "DURATION", "PT1H", 4, null);

        CloudEvent event = emitter.nodeDriftExempted(data);

        assertCommonFields(event, DesiredStateEventTypes.NODE_DRIFT_EXEMPTED);
        assertEquals("DURATION", payload(event).get("revertMode").asText());
        assertEquals("node-c", payload(event).get("nodeId").asText());
    }

    // ── nodeRecovered ──────────────────────────────────────────────────

    @Test
    void nodeRecovered_setsTypeAndSubject() throws Exception {
        var data = new NodeRecoveredData("tenant-1", "node-d", "worker", 6, null);

        CloudEvent event = emitter.nodeRecovered(data);

        assertCommonFields(event, DesiredStateEventTypes.NODE_RECOVERED);
        assertEquals("node-d", event.getSubject());
        assertEquals("tenant-1", event.getExtension("tenancyid"));
    }

    // ── nodeAlreadyConverged ───────────────────────────────────────────

    @Test
    void nodeAlreadyConverged_setsTypeAndSubject() throws Exception {
        var data = new NodeAlreadyConvergedData("tenant-1", "node-e", "endpoint", 7, null);

        CloudEvent event = emitter.nodeAlreadyConverged(data);

        assertCommonFields(event, DesiredStateEventTypes.NODE_ALREADY_CONVERGED);
        assertEquals("node-e", event.getSubject());
        assertEquals("tenant-1", event.getExtension("tenancyid"));
    }

    // ── nodeSuspended (new) ────────────────────────────────────────────

    @Test
    void nodeSuspended_setsTypeAndSubject() throws Exception {
        var data = new NodeSuspendedData("tenant-1", "node-f", "service", 8, null);

        CloudEvent event = emitter.nodeSuspended(data);

        assertCommonFields(event, DesiredStateEventTypes.NODE_SUSPENDED);
        assertEquals("node-f", event.getSubject());
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("node-f", payload(event).get("nodeId").asText());
    }

    // ── nodeResumed (new) ──────────────────────────────────────────────

    @Test
    void nodeResumed_setsTypeAndSubject() throws Exception {
        var data = new NodeResumedData("tenant-1", "node-g", "service", 9, null);

        CloudEvent event = emitter.nodeResumed(data);

        assertCommonFields(event, DesiredStateEventTypes.NODE_RESUMED);
        assertEquals("node-g", event.getSubject());
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("node-g", payload(event).get("nodeId").asText());
    }

    // ── cbrOutcome ─────────────────────────────────────────────────────

    @Test
    void cbrOutcome_setsTypeSubjectAndExtensions() throws Exception {
        var data = new CbrOutcomeData("tenant-1", "src-42", CbrPath.FAULT,
                Map.of("n1", "SUCCEEDED"), 1, 0, 0, 1.0, Instant.now(), Instant.now());

        CloudEvent event = emitter.cbrOutcome(data);

        assertCommonFields(event, CbrEventTypes.CBR_OUTCOME);
        assertEquals("src-42", event.getSubject());
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("fault", event.getExtension("cbrpath"));
        assertEquals("1.0", event.getExtension("successrate"));
    }

    @Test
    void cbrOutcome_situationPath_setsLowercasePath() throws Exception {
        var data = new CbrOutcomeData("tenant-1", "src-99", CbrPath.SITUATION,
                Map.of(), 0, 1, 0, 0.0, Instant.now(), Instant.now());

        CloudEvent event = emitter.cbrOutcome(data);

        assertEquals("situation", event.getExtension("cbrpath"));
    }

    // ── lifecycleStateEntered ──────────────────────────────────────────

    @Nested
    class LifecycleStateEnteredTests {

        @Test
        void setsTypeSubjectAndStateExtensions() throws Exception {
            var data = new LifecycleStateEnteredData("tenant-1", "node-h", "pod", "PROVISIONING", "ABSENT", null);

            CloudEvent event = emitter.lifecycleStateEntered(data);

            assertCommonFields(event, DesiredStateEventTypes.LIFECYCLE_STATE_ENTERED);
            assertEquals("node-h", event.getSubject());
            assertEquals("tenant-1", event.getExtension("tenancyid"));
            assertEquals("pod", event.getExtension("nodetype"));
            assertEquals("PROVISIONING", event.getExtension("state"));
            assertNull(event.getExtension("customeventtype"));
        }

        @Test
        void withCustomEventType_setsExtension() throws Exception {
            var data = new LifecycleStateEnteredData("tenant-1", "node-h", "pod", "PRESENT", "PROVISIONING", "app.deployed");

            CloudEvent event = emitter.lifecycleStateEntered(data);

            assertEquals("app.deployed", event.getExtension("customeventtype"));
        }
    }

    // ── lifecycleStateExited ───────────────────────────────────────────

    @Nested
    class LifecycleStateExitedTests {

        @Test
        void setsTypeSubjectAndStateExtensions() throws Exception {
            var data = new LifecycleStateExitedData("tenant-1", "node-i", "pod", "PROVISIONING", "PRESENT", null);

            CloudEvent event = emitter.lifecycleStateExited(data);

            assertCommonFields(event, DesiredStateEventTypes.LIFECYCLE_STATE_EXITED);
            assertEquals("node-i", event.getSubject());
            assertEquals("tenant-1", event.getExtension("tenancyid"));
            assertEquals("pod", event.getExtension("nodetype"));
            assertEquals("PROVISIONING", event.getExtension("state"));
            assertNull(event.getExtension("customeventtype"));
        }

        @Test
        void withCustomEventType_setsExtension() throws Exception {
            var data = new LifecycleStateExitedData("tenant-1", "node-i", "pod", "PRESENT", "DEPROVISIONING", "app.teardown");

            CloudEvent event = emitter.lifecycleStateExited(data);

            assertEquals("app.teardown", event.getExtension("customeventtype"));
        }
    }

    // ── planAwaitingApproval ───────────────────────────────────────────

    @Test
    void planAwaitingApproval_setsTypeAndTenancy() throws Exception {
        var data = new PlanAwaitingApprovalData("tenant-1", "plan-ref-1", 3, 1, 0, 0, "significant changes");

        CloudEvent event = emitter.planAwaitingApproval(data);

        assertCommonFields(event, DesiredStateEventTypes.PLAN_AWAITING_APPROVAL);
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("plan-ref-1", payload(event).get("planReference").asText());
        assertEquals("significant changes", payload(event).get("reason").asText());
    }

    // ── planApproved (new) ─────────────────────────────────────────────

    @Test
    void planApproved_setsTypeAndTenancy() throws Exception {
        var approval = new PlanApproval("plan-ref-2", "admin", Instant.parse("2026-01-15T12:00:00Z"));
        var data = new PlanApprovedData("tenant-1", "plan-ref-2", approval);

        CloudEvent event = emitter.planApproved(data);

        assertCommonFields(event, DesiredStateEventTypes.PLAN_APPROVED);
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("plan-ref-2", payload(event).get("planReference").asText());
    }

    // ── planRejected ───────────────────────────────────────────────────

    @Test
    void planRejected_setsTypeAndTenancy() throws Exception {
        var data = new PlanRejectedData("tenant-1", "plan-ref-3", "too risky");

        CloudEvent event = emitter.planRejected(data);

        assertCommonFields(event, DesiredStateEventTypes.PLAN_REJECTED);
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("too risky", payload(event).get("reason").asText());
    }

    // ── planInvalidated ────────────────────────────────────────────────

    @Test
    void planInvalidated_setsTypeAndTenancy() throws Exception {
        var data = new PlanInvalidatedData("tenant-1", "plan-ref-4", "desired state changed");

        CloudEvent event = emitter.planInvalidated(data);

        assertCommonFields(event, DesiredStateEventTypes.PLAN_INVALIDATED);
        assertEquals("tenant-1", event.getExtension("tenancyid"));
        assertEquals("desired state changed", payload(event).get("reason").asText());
    }
}
