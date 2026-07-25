package io.casehub.desiredstate.example.pipeline;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.casehub.desiredstate.engine.CaseTransitionExecutor;
import io.casehub.desiredstate.engine.DesiredStateExecutionRegistry;
import io.casehub.desiredstate.engine.TransitionWorkflowGenerator;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates CaseTransitionExecutor wiring with the pipeline domain.
 * When engine-adapter is on the classpath, CDI activates CaseTransitionExecutor
 * over SimpleTransitionExecutor — transition plans produce casehub-engine cases
 * with Worker(Workflow) phases for prune/grow.
 */
class PipelineCaseTransitionTest {

    private PipelineGoalCompiler compiler;
    private TransitionPlanner planner;
    private DesiredStateGraphFactory factory;
    private CaseTransitionExecutor executor;
    private CapturingCaseHubRuntime runtime;

    @BeforeEach
    void setUp() {
        compiler = new PipelineGoalCompiler();
        planner = new TransitionPlanner();
        factory = new DefaultDesiredStateGraphFactory();
        runtime = new CapturingCaseHubRuntime();
        PendingApprovalHandler approvalHandler = new PendingApprovalHandler() {
            @Override
            public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
                return new ApprovalCheckResult.None();
            }
            @Override
            public StepOutcome recordPending(DesiredNode node, StepAction action, String tenancyId, String planReference) {
                return new StepOutcome.Skipped("test: pending");
            }
            @Override
            public void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId) {
            }
        };
        DesiredStateExecutionRegistry registry = new DesiredStateExecutionRegistry();
        executor = new CaseTransitionExecutor(
            new TransitionWorkflowGenerator(), runtime, approvalHandler, registry);
    }

    @Test
    void fullPipeline_startsCaseWithOptimisticResult() {
        PipelineBlueprint blueprint = PipelineBlueprint.builder()
            .source("clickstream", "json", "kafka://clicks")
            .schema("click-schema", List.of("userId", "pageUrl", "timestamp"), 1)
            .ingestion("click-ingest", "clickstream", 1000, "json")
            .cleanser("click-clean", List.of("deduplicate"), true, "DROP")
            .transformer("session-agg", List.of("sessionize"), List.of("group-by-session"), "parquet")
            .sink("warehouse", "s3://analytics/sessions", "parquet", List.of("date"))
            .build();

        CompilationResult result = compiler.compile(blueprint, factory);


        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        ActualState empty = new ActualState(Map.of());
        TransitionPlan plan = planner.plan(graph, empty);

        TransitionResult transitionResult = executor.execute(plan, "pipeline-tenant");

        assertThat(runtime.lastDefinition).isNotNull();
        assertThat(transitionResult.outcomes()).hasSize(plan.additions().size());
        transitionResult.outcomes().forEach((id, outcome) ->
            assertThat(outcome)
                .as("Node %s should succeed optimistically", id.value())
                .isInstanceOf(StepOutcome.Succeeded.class));
    }

    @Test
    void pruneAndGrow_producesResultForBothPhases() {
        DesiredNode oldNode = new DesiredNode(
            NodeId.of("old-stage"), PipelineNodeTypes.TRANSFORMER,
            new TransformerSpec(List.of("old-agg"), List.of("old-rule"), "parquet"),
            HumanGating.NONE);
        DesiredNode newNode = new DesiredNode(
            NodeId.of("new-stage"), PipelineNodeTypes.TRANSFORMER,
            new TransformerSpec(List.of("new-agg"), List.of("new-rule"), "parquet"),
            HumanGating.NONE);

        DesiredStateGraph graph = factory.of(List.of(newNode), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(oldNode, StepAction.DEPROVISION)),
            List.of(new OrderedStep(newNode, StepAction.PROVISION)),
            graph, graph
        );

        TransitionResult result = executor.execute(plan, "pipeline-tenant");

        assertThat(result.outcomes()).hasSize(2);
        assertThat(result.outcomes().get(NodeId.of("old-stage")))
            .isInstanceOf(StepOutcome.Succeeded.class);
        assertThat(result.outcomes().get(NodeId.of("new-stage")))
            .isInstanceOf(StepOutcome.Succeeded.class);
    }

    @Test
    void humanReviewNode_skippedInResult() {
        DesiredNode humanNode = new DesiredNode(
            NodeId.of("human-review"), PipelineNodeTypes.HUMAN_REVIEW,
            new HumanReviewSpec(NodeId.of("failing-stage"), "schema mismatch", "auto-fix exhausted"),
            HumanGating.ALL);

        DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
            graph, graph
        );

        TransitionResult result = executor.execute(plan, "pipeline-tenant");

        assertThat(result.outcomes().get(NodeId.of("human-review")))
            .isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) result.outcomes().get(NodeId.of("human-review"))).reason())
            .contains("human task binding");
    }

    @Test
    void emptyPlan_noCase() {
        DesiredStateGraph graph = factory.empty();
        TransitionPlan plan = new TransitionPlan(List.of(), List.of(), graph, graph);

        TransitionResult result = executor.execute(plan, "pipeline-tenant");

        assertThat(result.outcomes()).isEmpty();
        assertThat(runtime.lastDefinition).isNull();
    }

    static class CapturingCaseHubRuntime implements CaseHubRuntime {
        CaseDefinition lastDefinition;

        @Override
        public UUID startCase(CaseDefinition definition) {
            lastDefinition = definition;
            return UUID.randomUUID();
        }

        @Override
        public UUID startCase(CaseDefinition definition, Object inputData) {
            lastDefinition = definition;
            return UUID.randomUUID();
        }

        @Override
        public UUID startCase(CaseDefinition definition, Object inputData, UUID parentCaseId, PropagationContext ctx) {
            return UUID.randomUUID();
        }

        @Override
        public UUID startCase(CaseDefinition definition, Object inputData, Map<String, Object> semanticData) {
            return UUID.randomUUID();
        }

        @Override
        public UUID startCase(CaseDefinition definition, Object inputData, Map<String, Object> semanticData, UUID parentCaseId, PropagationContext ctx) {
            return UUID.randomUUID();
        }

        @Override
        public void signal(UUID caseId, String path, Object value) {}

        @Override
        public void cancelCase(UUID caseId)                        {}

        @Override
        public void suspendCase(UUID caseId)                       {}

        @Override
        public void resumeCase(UUID caseId)                        {}

        @Override
        public Object query(UUID caseId, String path) {
            return null;
        }

        @Override
        public <T> T query(UUID caseId, String path, Class<T> clazz) {
            return null;
        }

        @Override
        public List<CaseEventLogRecord> eventLog(UUID caseId) {
            return List.of();
        }

        @Override
        public List<CaseEventLogRecord> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes) {
            return List.of();
        }

        @Override
        public List<CaseEventLogRecord> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes, Set<EventStreamType> streamTypes) {
            return List.of();
        }
    }
}
