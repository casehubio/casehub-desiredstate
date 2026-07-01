package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimpleTransitionExecutorTest {

    private DesiredStateGraphFactory factory;
    private MockNodeProvisioner mockProvisioner;
    private SimpleTransitionExecutor executor;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        mockProvisioner = new MockNodeProvisioner();
        var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
        executor = new SimpleTransitionExecutor(router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());
    }

    @Test
    void executesRemovalsThenAdditionsInOrder() {
        DesiredNode nodeToRemove = new DesiredNode(
            NodeId.of("old"), NodeType.of("test"), new TestSpec("old"), false
        );
        DesiredNode nodeToAdd = new DesiredNode(
            NodeId.of("new"), NodeType.of("test"), new TestSpec("new"), false
        );

        DesiredStateGraph graph = factory.of(List.of(nodeToAdd), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(nodeToRemove, StepAction.DEPROVISION)),
            List.of(new OrderedStep(nodeToAdd, StepAction.PROVISION)),
            graph,
            graph
        );

        TransitionResult result = executor.execute(plan, "default")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        // Both should succeed
        assertEquals(2, result.outcomes().size());
        assertTrue(result.outcomes().get(NodeId.of("old")) instanceof StepOutcome.Succeeded);
        assertTrue(result.outcomes().get(NodeId.of("new")) instanceof StepOutcome.Succeeded);

        // Verify order: deprovision called first, then provision
        assertEquals(2, mockProvisioner.callOrder.size());
        assertEquals("deprovision:old", mockProvisioner.callOrder.get(0));
        assertEquals("provision:new", mockProvisioner.callOrder.get(1));
    }

    @Test
    void skipsHumanNodesWithNoOpHandler() {
        DesiredNode humanNode = new DesiredNode(
            NodeId.of("h1"), NodeType.of("test"), new TestSpec("human"), true
        );
        DesiredNode normalNode = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("normal"), false
        );

        DesiredStateGraph graph = factory.of(List.of(humanNode, normalNode), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(
                new OrderedStep(humanNode, StepAction.PROVISION),
                new OrderedStep(normalNode, StepAction.PROVISION)
            ),
            graph,
            graph
        );

        TransitionResult result = executor.execute(plan, "default")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        StepOutcome humanOutcome = result.outcomes().get(NodeId.of("h1"));
        assertTrue(humanOutcome instanceof StepOutcome.Skipped);
        assertEquals("requires human — no HumanNodeHandler configured",
            ((StepOutcome.Skipped) humanOutcome).reason());

        assertTrue(result.outcomes().get(NodeId.of("n1")) instanceof StepOutcome.Succeeded);

        assertEquals(1, mockProvisioner.callOrder.size());
        assertEquals("provision:n1", mockProvisioner.callOrder.get(0));
    }

    @Test
    void provisionFailure_recordsFailedOutcome() {
        DesiredNode node = new DesiredNode(
            NodeId.of("failing"), NodeType.of("test"), new TestSpec("fail"), false
        );

        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        // Configure mock to fail
        mockProvisioner.shouldFail = true;

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph,
            graph
        );

        TransitionResult result = executor.execute(plan, "default")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        StepOutcome outcome = result.outcomes().get(NodeId.of("failing"));
        assertTrue(outcome instanceof StepOutcome.Failed);
        assertEquals("mock failure", ((StepOutcome.Failed) outcome).reason());
    }

    @Test
    void deprovisionFailure_recordsFailedOutcome() {
        DesiredNode node = new DesiredNode(
            NodeId.of("failing"), NodeType.of("test"), new TestSpec("fail"), false
        );

        DesiredStateGraph graph = factory.empty();

        // Configure mock to fail
        mockProvisioner.shouldFail = true;

        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(node, StepAction.DEPROVISION)),
            List.of(),
            graph,
            graph
        );

        TransitionResult result = executor.execute(plan, "default")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        StepOutcome outcome = result.outcomes().get(NodeId.of("failing"));
        assertTrue(outcome instanceof StepOutcome.Failed);
        assertEquals("mock failure", ((StepOutcome.Failed) outcome).reason());
    }

    @Test
    void delegatesHumanNodesToHandler() {
        HumanNodeHandler handler = (node, context) ->
            new StepOutcome.Skipped("test handler: " + node.id().value());

        var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
        SimpleTransitionExecutor handlerExecutor =
            new SimpleTransitionExecutor(router, handler, new NoOpPendingApprovalHandler());

        DesiredNode humanNode = new DesiredNode(
            NodeId.of("h1"), NodeType.of("test"), new TestSpec("human"), true
        );
        DesiredNode normalNode = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("normal"), false
        );

        DesiredStateGraph graph = factory.of(List.of(humanNode, normalNode), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(
                new OrderedStep(humanNode, StepAction.PROVISION),
                new OrderedStep(normalNode, StepAction.PROVISION)
            ),
            graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        StepOutcome humanOutcome = result.outcomes().get(NodeId.of("h1"));
        assertInstanceOf(StepOutcome.Skipped.class, humanOutcome);
        assertEquals("test handler: h1", ((StepOutcome.Skipped) humanOutcome).reason());

        assertTrue(result.outcomes().get(NodeId.of("n1")) instanceof StepOutcome.Succeeded);

        assertEquals(1, mockProvisioner.callOrder.size());
        assertEquals("provision:n1", mockProvisioner.callOrder.get(0));
    }

    @Test
    void handlerReceivesCorrectProvisionContext() {
        String[] capturedTenancyId = {null};
        DesiredStateGraph[] capturedGraph = {null};

        HumanNodeHandler capturingHandler = (node, context) -> {
            capturedTenancyId[0] = context.tenancyId();
            capturedGraph[0] = context.graph();
            return new StepOutcome.Skipped("captured");
        };

        var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
        SimpleTransitionExecutor capturingExecutor =
            new SimpleTransitionExecutor(router, capturingHandler, new NoOpPendingApprovalHandler());

        DesiredNode humanNode = new DesiredNode(
            NodeId.of("h1"), NodeType.of("test"), new TestSpec("human"), true
        );

        DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
            graph, graph
        );

        capturingExecutor.execute(plan, "my-tenant")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        assertEquals("my-tenant", capturedTenancyId[0]);
        assertNotNull(capturedGraph[0]);
        assertTrue(capturedGraph[0].nodes().containsKey(NodeId.of("h1")));
    }

    // Helper test spec
    record TestSpec(String value) implements NodeSpec {}

    // Mock provisioner for testing
    static class MockNodeProvisioner implements NodeProvisioner {
        List<String> callOrder = new java.util.ArrayList<>();
        boolean shouldFail = false;
        boolean shouldReturnPendingApproval = false;

        @Override
        public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
            callOrder.add("provision:" + node.id().value());
            if (shouldFail) return new ProvisionResult.Failed("mock failure");
            if (shouldReturnPendingApproval) return new ProvisionResult.PendingApproval(node.id(), "mock-plan");
            return new ProvisionResult.Success();
        }

        @Override
        public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
            callOrder.add("deprovision:" + node.id().value());
            if (shouldFail) return new DeprovisionResult.Failed("mock failure");
            return new DeprovisionResult.Success();
        }

        @Override
        public Set<NodeType> handledTypes() {
            return Set.of(NodeType.of("test"), NodeType.of("database"));
        }
    }

    // --- PendingApprovalHandler tests ---

    @Test
    void pendingApproval_noHandler_returnsFailed() {
        DesiredNode node = new DesiredNode(
            NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
        );
        mockProvisioner.shouldReturnPendingApproval = true;

        DesiredStateGraph graph = factory.of(List.of(node), List.of());
        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph
        );

        TransitionResult result = executor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        StepOutcome outcome = result.outcomes().get(NodeId.of("db-prod"));
        assertInstanceOf(StepOutcome.Failed.class, outcome);
        assertTrue(((StepOutcome.Failed) outcome).reason().contains("no PendingApprovalHandler configured"));
    }

    @Test
    void pendingApproval_handlerCheckReturnsPending_skipsProvisioner() {
        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.Pending("plan-42");
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
                return new StepOutcome.Skipped("pending");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
        };

        var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode node = new DesiredNode(
            NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node), List.of());
        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("db-prod")));
        assertTrue(mockProvisioner.callOrder.isEmpty(), "Provisioner should NOT be called when pending");
    }

    @Test
    void pendingApproval_handlerCheckReturnsApproved_callsProvisionerWithApproval() {
        var approval = new PlanApproval("plan-42", "jane", Instant.parse("2026-06-28T14:30:00Z"));
        ProvisionContext[] capturedContext = {null};

        NodeProvisioner capturingProvisioner = new NodeProvisioner() {
            public ProvisionResult provision(DesiredNode n, ProvisionContext ctx) {
                capturedContext[0] = ctx;
                return new ProvisionResult.Success();
            }
            public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext ctx) {
                return new DeprovisionResult.Success();
            }
            public Set<NodeType> handledTypes() {
                return Set.of(NodeType.of("database"));
            }
        };

        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.Approved(approval);
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
                return new StepOutcome.Skipped("pending");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
        };

        var router = new DefaultNodeProvisionerRouter(List.of(capturingProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode node = new DesiredNode(
            NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node), List.of());
        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Succeeded.class, result.outcomes().get(NodeId.of("db-prod")));
        assertNotNull(capturedContext[0].approval());
        assertEquals("plan-42", capturedContext[0].approval().planReference());
        assertEquals("jane", capturedContext[0].approval().approvedBy());
    }

    @Test
    void pendingApproval_handlerCheckReturnsRejected_returnsRejectedAndAcknowledges() {
        boolean[] acknowledged = {false};

        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.Rejected("plan-42", "risk too high");
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
                return new StepOutcome.Skipped("pending");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {
                acknowledged[0] = true;
            }
        };

        var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode node = new DesiredNode(
            NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node), List.of());
        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Rejected.class, result.outcomes().get(NodeId.of("db-prod")));
        assertEquals("approval rejected: risk too high",
            ((StepOutcome.Rejected) result.outcomes().get(NodeId.of("db-prod"))).reason());
        assertTrue(acknowledged[0], "acknowledgeRejection should be called");
        assertTrue(mockProvisioner.callOrder.isEmpty(), "Provisioner should NOT be called on rejection");
    }

    @Test
    void pendingApproval_provisionerReturnsPendingApproval_callsRecordPending() {
        String[] recordedPlanRef = {null};

        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.None();
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String planRef) {
                recordedPlanRef[0] = planRef;
                return new StepOutcome.Skipped("pending approval: WorkItem xyz");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
        };

        NodeProvisioner pendingProvisioner = new NodeProvisioner() {
            public ProvisionResult provision(DesiredNode n, ProvisionContext ctx) {
                return new ProvisionResult.PendingApproval(n.id(), "plan-42");
            }
            public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext ctx) {
                return new DeprovisionResult.Success();
            }
            public Set<NodeType> handledTypes() {
                return Set.of(NodeType.of("database"));
            }
        };

        var router = new DefaultNodeProvisionerRouter(List.of(pendingProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode node = new DesiredNode(
            NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node), List.of());
        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("db-prod")));
        assertEquals("plan-42", recordedPlanRef[0]);
    }

    @Test
    void deprovision_pendingApproval_handlerCheckReturnsPending_skipsProvisioner() {
        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.Pending("depro-plan");
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
                return new StepOutcome.Skipped("pending");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
        };

        var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode node = new DesiredNode(
            NodeId.of("old-db"), NodeType.of("database"), new TestSpec("pg"), false
        );
        DesiredStateGraph graph = factory.empty();
        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(node, StepAction.DEPROVISION)),
            List.of(), graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("old-db")));
        assertTrue(mockProvisioner.callOrder.isEmpty());
    }

    @Test
    void deprovision_pendingApproval_handlerCheckReturnsRejected_returnsRejectedAndAcknowledges() {
        boolean[] acknowledged = {false};

        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.Rejected("depro-plan", "resource still in use");
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
                return new StepOutcome.Skipped("pending");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {
                acknowledged[0] = true;
            }
        };

        var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode node = new DesiredNode(
            NodeId.of("old-db"), NodeType.of("database"), new TestSpec("pg"), false
        );
        DesiredStateGraph graph = factory.empty();
        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(node, StepAction.DEPROVISION)),
            List.of(), graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Rejected.class, result.outcomes().get(NodeId.of("old-db")));
        assertEquals("approval rejected: resource still in use",
            ((StepOutcome.Rejected) result.outcomes().get(NodeId.of("old-db"))).reason());
        assertTrue(acknowledged[0], "acknowledgeRejection should be called");
        assertTrue(mockProvisioner.callOrder.isEmpty(), "Provisioner should NOT be called on rejection");
    }

    @Test
    void deprovision_pendingApproval_handlerCheckReturnsApproved_callsProvisionerWithApproval() {
        var approval = new PlanApproval("depro-plan", "alice", Instant.parse("2026-06-29T10:00:00Z"));
        DeprovisionContext[] capturedContext = {null};

        NodeProvisioner capturingProvisioner = new NodeProvisioner() {
            public ProvisionResult provision(DesiredNode n, ProvisionContext ctx) {
                return new ProvisionResult.Success();
            }
            public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext ctx) {
                capturedContext[0] = ctx;
                return new DeprovisionResult.Success();
            }
            public Set<NodeType> handledTypes() {
                return Set.of(NodeType.of("database"));
            }
        };

        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.Approved(approval);
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
                return new StepOutcome.Skipped("pending");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
        };

        var router = new DefaultNodeProvisionerRouter(List.of(capturingProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode node = new DesiredNode(
            NodeId.of("old-db"), NodeType.of("database"), new TestSpec("pg"), false
        );
        DesiredStateGraph graph = factory.empty();
        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(node, StepAction.DEPROVISION)),
            List.of(), graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Succeeded.class, result.outcomes().get(NodeId.of("old-db")));
        assertNotNull(capturedContext[0].approval());
        assertEquals("depro-plan", capturedContext[0].approval().planReference());
        assertEquals("alice", capturedContext[0].approval().approvedBy());
    }

    @Test
    void deprovision_pendingApproval_provisionerReturnsPA_callsRecordPending() {
        String[] recordedPlanRef = {null};

        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.None();
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String planRef) {
                recordedPlanRef[0] = planRef;
                return new StepOutcome.Skipped("pending approval: WorkItem abc");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
        };

        NodeProvisioner pendingProvisioner = new NodeProvisioner() {
            public ProvisionResult provision(DesiredNode n, ProvisionContext ctx) {
                return new ProvisionResult.Success();
            }
            public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext ctx) {
                return new DeprovisionResult.PendingApproval(n.id(), "depro-plan-42");
            }
            public Set<NodeType> handledTypes() {
                return Set.of(NodeType.of("database"));
            }
        };

        var router = new DefaultNodeProvisionerRouter(List.of(pendingProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode node = new DesiredNode(
            NodeId.of("old-db"), NodeType.of("database"), new TestSpec("pg"), false
        );
        DesiredStateGraph graph = factory.empty();
        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(node, StepAction.DEPROVISION)),
            List.of(), graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("old-db")));
        assertEquals("depro-plan-42", recordedPlanRef[0]);
    }

    @Test
    void requiresHuman_takesPrecedence_overPendingApprovalHandler() {
        PendingApprovalHandler handler = new PendingApprovalHandler() {
            public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
                return new ApprovalCheckResult.Approved(
                    new PlanApproval("plan", "jane", Instant.now()));
            }
            public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
                return new StepOutcome.Skipped("pending");
            }
            public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
        };

        var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
        SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), handler);

        DesiredNode humanNode = new DesiredNode(
            NodeId.of("h1"), NodeType.of("test"), new TestSpec("human"), true
        );
        DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());
        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
            graph, graph
        );

        TransitionResult result = handlerExecutor.execute(plan, "tenant1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem().getItem();

        assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("h1")));
        assertTrue(((StepOutcome.Skipped) result.outcomes().get(NodeId.of("h1"))).reason()
            .contains("requires human"));
    }
}
