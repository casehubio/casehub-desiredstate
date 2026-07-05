package io.casehub.desiredstate.work;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.work.api.*;
import io.casehub.work.api.spi.WorkItemCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemHumanNodeHandlerTest {

    record TestSpec(String value) implements NodeSpec {}

    private MockWorkItemCreator mockCreator;
    private WorkItemHumanNodeHandler handler;
    private DesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        mockCreator = new MockWorkItemCreator();
        handler = new WorkItemHumanNodeHandler(mockCreator);
        graphFactory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void firstCall_createsWorkItem_returnsSkippedWithId() {
        DesiredNode node = new DesiredNode(
            NodeId.of("thermo-1"), NodeType.of("iot-device"), new TestSpec("install"), true
        );
        DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
        ProvisionContext context = new ProvisionContext("tenant1", graph);

        StepOutcome outcome = handler.onProvision(node, context);

        assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) outcome).reason())
            .startsWith("pending human action: WorkItem ");

        assertThat(mockCreator.lastCreateRequest).isNotNull();
        assertThat(mockCreator.lastCreateRequest.title).isEqualTo("Provision: thermo-1");
        assertThat(mockCreator.lastCreateRequest.description)
            .isEqualTo("Human provisioning required for node thermo-1 (type: iot-device)");
        assertThat(mockCreator.lastCreateRequest.category).isEqualTo("desiredstate-provision");
        assertThat(mockCreator.lastCreateRequest.callerRef)
            .isEqualTo("desiredstate:tenant1:thermo-1");
        assertThat(mockCreator.lastCreateRequest.priority).isEqualTo(WorkItemPriority.MEDIUM);
        assertThat(mockCreator.lastCreateRequest.createdBy).isEqualTo("desiredstate");
    }

    @Test
    void subsequentCall_findsActiveWorkItem_doesNotCreateDuplicate() {
        UUID existingId = UUID.randomUUID();
        mockCreator.activeRef = new WorkItemRef(
            existingId, WorkItemStatus.PENDING, "desiredstate:tenant1:thermo-1",
            null, null, null, null, "tenant1", null
        );

        DesiredNode node = new DesiredNode(
            NodeId.of("thermo-1"), NodeType.of("iot-device"), new TestSpec("install"), true
        );
        DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
        ProvisionContext context = new ProvisionContext("tenant1", graph);

        StepOutcome outcome = handler.onProvision(node, context);

        assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) outcome).reason())
            .isEqualTo("pending human action: WorkItem " + existingId);

        assertThat(mockCreator.lastCreateRequest).isNull();
    }

    @Test
    void reProvisionAfterCompletion_noActiveWorkItem_createsNew() {
        // activeRef is null — simulates all prior WorkItems being terminal
        mockCreator.activeRef = null;

        DesiredNode node = new DesiredNode(
            NodeId.of("thermo-1"), NodeType.of("iot-device"), new TestSpec("install"), true
        );
        DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
        ProvisionContext context = new ProvisionContext("tenant1", graph);

        StepOutcome outcome = handler.onProvision(node, context);

        assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) outcome).reason())
            .startsWith("pending human action: WorkItem ");

        assertThat(mockCreator.lastCreateRequest).isNotNull();
        assertThat(mockCreator.lastCreateRequest.callerRef)
            .isEqualTo("desiredstate:tenant1:thermo-1");
    }

    @Test
    void differentTenancy_differentCallerRef() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v"), true
        );
        DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());

        handler.onProvision(node, new ProvisionContext("tenantA", graph));
        String refA = mockCreator.lastCreateRequest.callerRef;

        mockCreator.lastCreateRequest = null;
        handler.onProvision(node, new ProvisionContext("tenantB", graph));
        String refB = mockCreator.lastCreateRequest.callerRef;

        assertThat(refA).isEqualTo("desiredstate:tenantA:n1");
        assertThat(refB).isEqualTo("desiredstate:tenantB:n1");
        assertThat(refA).isNotEqualTo(refB);
    }

    @Test
    void differentNodeId_differentCallerRef() {
        DesiredStateGraph graph = graphFactory.empty();
        ProvisionContext context = new ProvisionContext("tenant1", graph);

        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("v"), true
        );
        DesiredNode nodeB = new DesiredNode(
            NodeId.of("b"), NodeType.of("test"), new TestSpec("v"), true
        );

        handler.onProvision(nodeA, context);
        String refA = mockCreator.lastCreateRequest.callerRef;

        mockCreator.lastCreateRequest = null;
        handler.onProvision(nodeB, context);
        String refB = mockCreator.lastCreateRequest.callerRef;

        assertThat(refA).isEqualTo("desiredstate:tenant1:a");
        assertThat(refB).isEqualTo("desiredstate:tenant1:b");
        assertThat(refA).isNotEqualTo(refB);
    }

    static class MockWorkItemCreator implements WorkItemCreator {
        WorkItemCreateRequest lastCreateRequest;
        WorkItemRef activeRef;

        @Override
        public WorkItemRef create(WorkItemCreateRequest request) {
            lastCreateRequest = request;
            UUID id = UUID.randomUUID();
            return new WorkItemRef(
                id, WorkItemStatus.PENDING, request.callerRef,
                null, null, null, null, request.tenancyId, null
            );
        }

        @Override
        public Optional<WorkItemRef> findByCallerRef(String callerRef) {
            return Optional.ofNullable(activeRef);
        }

        @Override
        public Optional<WorkItemRef> findActiveByCallerRef(String callerRef) {
            return Optional.ofNullable(activeRef);
        }

        @Override
        public void obsoleteByCallerRef(String callerRef) {
            // No-op for mock
        }
    }
}
