package io.casehub.desiredstate.engine;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.testing.MockPendingApprovalHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class CaseTransitionExecutorTest {

    private TransitionWorkflowGenerator workflowGenerator;
    private MockPendingApprovalHandler approvalHandler;
    private DesiredStateExecutionRegistry executionRegistry;
    private CaseTransitionExecutor executor;

    @BeforeEach
    void setUp() {
        workflowGenerator = new TransitionWorkflowGenerator();
        approvalHandler = new MockPendingApprovalHandler();
        executionRegistry = new DesiredStateExecutionRegistry();
        executor = new CaseTransitionExecutor(
            workflowGenerator, new StubCaseHubRuntime(),
            approvalHandler, executionRegistry);
    }

    static class StubCaseHubRuntime implements CaseHubRuntime {
        int casesStarted = 0;

        @Override public CompletionStage<UUID> startCase(CaseDefinition definition) {
            casesStarted++;
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData) {
            casesStarted++;
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData, UUID parentCaseId, PropagationContext ctx) {
            casesStarted++;
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData, Map<String, Object> semanticData) {
            casesStarted++;
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData, Map<String, Object> semanticData, UUID parentCaseId, PropagationContext ctx) {
            casesStarted++;
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<Void> signal(UUID caseId, String path, Object value) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void cancelCase(UUID caseId) {}
        @Override public void suspendCase(UUID caseId) {}
        @Override public void resumeCase(UUID caseId) {}
        @Override public CompletionStage<Object> query(UUID caseId, String path) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public <T> CompletionStage<T> query(UUID caseId, String path, Class<T> clazz) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes, Set<EventStreamType> streamTypes) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    @Test
    void humanNodes_getHumanTaskBindings() {
        DesiredNode automatedNode = new DesiredNode(
                NodeId.of("app"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("human-review-app"), NodeType.of("human-review"), new TestSpec(), HumanGating.ALL);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(automatedNode, humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(
                        new OrderedStep(automatedNode, StepAction.PROVISION),
                        new OrderedStep(humanNode, StepAction.PROVISION)
                ),
                graph, graph
        );

        CaseDefinition caseDefinition = executor.buildCaseDefinition(plan, "test-exec-id");

        long humanTaskBindings = caseDefinition.getBindings().stream()
                .filter(b -> b.target() instanceof HumanTaskTarget)
                .count();

        assertThat(humanTaskBindings)
                .as("Human nodes should produce humanTask bindings")
                .isEqualTo(1);

        HumanTaskTarget target = (HumanTaskTarget) caseDefinition.getBindings().stream()
                .filter(b -> b.target() instanceof HumanTaskTarget)
                .findFirst().orElseThrow()
                .target();

        assertThat(target.title()).contains("human-review-app");
    }

    @Test
    void humanNodes_excludedFromGrowWorkflow() {
        DesiredNode automatedNode = new DesiredNode(
                NodeId.of("app"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("human-review-app"), NodeType.of("human-review"), new TestSpec(), HumanGating.ALL);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(automatedNode, humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(
                        new OrderedStep(automatedNode, StepAction.PROVISION),
                        new OrderedStep(humanNode, StepAction.PROVISION)
                ),
                graph, graph
        );

        CaseDefinition caseDefinition = executor.buildCaseDefinition(plan, "test-exec-id");

        assertThat(caseDefinition.getWorkers())
                .as("Grow worker should only contain automated nodes")
                .hasSize(1);
    }

    @Test
    void humanNodes_markedAsSkippedInResult() {
        DesiredNode automatedNode = new DesiredNode(
                NodeId.of("app"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("human-review-app"), NodeType.of("human-review"), new TestSpec(), HumanGating.ALL);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(automatedNode, humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(
                        new OrderedStep(automatedNode, StepAction.PROVISION),
                        new OrderedStep(humanNode, StepAction.PROVISION)
                ),
                graph, graph
        );

        TransitionResult result = executor.execute(plan, "default")
                .await().indefinitely();

        assertThat(result.outcomes().get(NodeId.of("human-review-app")))
                .isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) result.outcomes().get(NodeId.of("human-review-app"))).reason())
                .contains("human task binding");
    }

    @Test
    void onlyAutomatedNodes_noHumanTaskBindings() {
        DesiredNode automatedNode = new DesiredNode(
                NodeId.of("app"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(automatedNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(new OrderedStep(automatedNode, StepAction.PROVISION)),
                graph, graph
        );

        CaseDefinition caseDefinition = executor.buildCaseDefinition(plan, "test-exec-id");

        long humanTaskBindings = caseDefinition.getBindings().stream()
                .filter(b -> b.target() instanceof HumanTaskTarget)
                .count();

        assertThat(humanTaskBindings).isZero();
    }

    @Test
    void humanOnlyPlan_noWorkersOnlyHumanBindings() {
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("human-review-app"), NodeType.of("human-review"), new TestSpec(), HumanGating.ALL);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
                graph, graph
        );

        CaseDefinition caseDefinition = executor.buildCaseDefinition(plan, "test-exec-id");

        assertThat(caseDefinition.getWorkers()).isEmpty();
        assertThat(caseDefinition.getBindings()).hasSize(1);
        assertThat(caseDefinition.getBindings().get(0).target()).isInstanceOf(HumanTaskTarget.class);
    }

    @Test
    void pendingApproval_nodeSkipped() {
        DesiredNode node = new DesiredNode(
            NodeId.of("gated"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);

        approvalHandler.programCheck(
            NodeId.of("gated"), StepAction.PROVISION,
            new ApprovalCheckResult.Pending("plan-ref-1"));

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph);

        TransitionResult result = executor.execute(plan, "tenant-a")
            .await().indefinitely();

        assertThat(result.outcomes().get(NodeId.of("gated")))
            .isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) result.outcomes().get(NodeId.of("gated"))).reason())
            .contains("pending approval");
    }

    @Test
    void rejectedApproval_nodeRejectedAndAcknowledged() {
        DesiredNode node = new DesiredNode(
            NodeId.of("rejected"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);

        approvalHandler.programCheck(
            NodeId.of("rejected"), StepAction.PROVISION,
            new ApprovalCheckResult.Rejected("plan-ref-1", "policy violation"));

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph);

        TransitionResult result = executor.execute(plan, "tenant-a")
            .await().indefinitely();

        assertThat(result.outcomes().get(NodeId.of("rejected")))
            .isInstanceOf(StepOutcome.Rejected.class);
        assertThat(approvalHandler.acknowledgedRejections).hasSize(1);
    }

    @Test
    void allNodesFiltered_noCaseStarted() {
        DesiredNode node = new DesiredNode(
            NodeId.of("pending"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);

        approvalHandler.programCheck(
            NodeId.of("pending"), StepAction.PROVISION,
            new ApprovalCheckResult.Pending("plan-ref-1"));

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph);

        StubCaseHubRuntime runtime = new StubCaseHubRuntime();
        CaseTransitionExecutor exec = new CaseTransitionExecutor(
            workflowGenerator, runtime, approvalHandler, executionRegistry);

        TransitionResult result = exec.execute(plan, "tenant-a")
            .await().indefinitely();

        assertThat(result.outcomes()).containsKey(NodeId.of("pending"));
        assertThat(runtime.casesStarted).isZero();
    }

    @Test
    void mixedPlan_filteredAndAutomated() {
        DesiredNode pendingNode = new DesiredNode(
            NodeId.of("gated"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);
        DesiredNode autoNode = new DesiredNode(
            NodeId.of("auto"), NodeType.of("service"), new TestSpec(), HumanGating.NONE);

        approvalHandler.programCheck(
            NodeId.of("gated"), StepAction.PROVISION,
            new ApprovalCheckResult.Pending("plan-ref-1"));

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(pendingNode, autoNode), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(
                new OrderedStep(pendingNode, StepAction.PROVISION),
                new OrderedStep(autoNode, StepAction.PROVISION)),
            graph, graph);

        TransitionResult result = executor.execute(plan, "tenant-a")
            .await().indefinitely();

        assertThat(result.outcomes().get(NodeId.of("gated")))
            .isInstanceOf(StepOutcome.Skipped.class);
        assertThat(result.outcomes().get(NodeId.of("auto")))
            .isInstanceOf(StepOutcome.Succeeded.class);
    }

    @Test
    void humanRemovals_getHumanTaskBindings() {
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("h1"), NodeType.of("test"), new TestSpec(), HumanGating.ALL
        );
        DesiredNode normalNode = new DesiredNode(
                NodeId.of("n1"), NodeType.of("test"), new TestSpec(), HumanGating.NONE
        );

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph        graph   = factory.of(List.of(humanNode, normalNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(
                        new OrderedStep(humanNode, StepAction.DEPROVISION),
                        new OrderedStep(normalNode, StepAction.DEPROVISION)
                       ),
                List.of(),
                graph, graph
        );

        CaseDefinition caseDef = executor.buildCaseDefinition(plan, "exec-1");

        boolean hasHumanBinding = caseDef.getBindings().stream()
                                         .anyMatch(b -> b.getName().equals("human-deprovision-h1")
                                                        && b.target() instanceof HumanTaskTarget);
        assertThat(hasHumanBinding)
                .as("Should have humanTask binding for human removal")
                .isTrue();
    }

    @Test
    void humanRemovals_excludedFromPruneWorkflow() {
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("h1"), NodeType.of("test"), new TestSpec(), HumanGating.ALL
        );
        DesiredNode normalNode = new DesiredNode(
                NodeId.of("n1"), NodeType.of("test"), new TestSpec(), HumanGating.NONE
        );

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph        graph   = factory.of(List.of(humanNode, normalNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(
                        new OrderedStep(humanNode, StepAction.DEPROVISION),
                        new OrderedStep(normalNode, StepAction.DEPROVISION)
                       ),
                List.of(),
                graph, graph
        );

        CaseDefinition caseDef = executor.buildCaseDefinition(plan, "exec-1");

        assertThat(caseDef.getWorkers())
                .as("Prune worker should exist for normal removals only")
                .hasSize(1);
        assertThat(caseDef.getWorkers().get(0).name()).isEqualTo("prune");
    }

    @Test
    void humanRemovals_markedAsSkippedInResult() {
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("h1"), NodeType.of("test"), new TestSpec(), HumanGating.ALL
        );

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph        graph   = factory.of(List.of(humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(new OrderedStep(humanNode, StepAction.DEPROVISION)),
                List.of(),
                graph, graph
        );

        TransitionResult result = executor.execute(plan, "tenant1")
                                          .await().indefinitely();

        StepOutcome outcome = result.outcomes().get(NodeId.of("h1"));
        assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) outcome).reason()).isEqualTo("routed to human task binding");
    }

    @Test
    void humanAdditions_useActionNamespacedBindingNames() {
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("h1"), NodeType.of("test"), new TestSpec(), HumanGating.ALL
        );

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph        graph   = factory.of(List.of(humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
                graph, graph
        );

        CaseDefinition caseDef = executor.buildCaseDefinition(plan, "exec-1");

        boolean hasNamespacedBinding = caseDef.getBindings().stream()
                                              .anyMatch(b -> b.getName().equals("human-provision-h1")
                                                             && b.target() instanceof HumanTaskTarget);
        assertThat(hasNamespacedBinding)
                .as("Human addition binding should use 'human-provision-<nodeId>' format")
                .isTrue();
    }


    private record TestSpec() implements NodeSpec {}
}
