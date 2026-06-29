package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReconciliationTracingTest {

    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;

    private DesiredStateGraphFactory factory;
    private TestActualStateAdapter actualAdapter;
    private TestTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private FaultPolicyEngine faultEngine;
    private TestEventSource testEventSource;
    private ReconciliationLoop loop;

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(50);
    private static final Duration TEST_RESYNC = Duration.ofHours(1);

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        GlobalOpenTelemetry.resetForTest();
        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        factory = new DefaultDesiredStateGraphFactory();
        actualAdapter = new TestActualStateAdapter();
        testExecutor = new TestTransitionExecutor();
        planner = new TransitionPlanner();
        faultEngine = new FaultPolicyEngine(List.of());
        testEventSource = new TestEventSource();

        loop = new ReconciliationLoop(
                planner, testExecutor, actualAdapter, faultEngine, testEventSource,
                TEST_DEBOUNCE, TEST_RESYNC);
    }

    @AfterEach
    void tearDown() {
        loop.stop("test-tenant");
        GlobalOpenTelemetry.resetForTest();
        tracerProvider.close();
    }

    @Test
    void reconcile_createsRootSpanWithTenantId() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).contains("reconcile");

        SpanData reconcileSpan = spans.stream()
                .filter(s -> s.getName().equals("reconcile"))
                .findFirst().orElseThrow();
        assertThat(reconcileSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("desiredstate.tenant.id")))
                .isEqualTo("test-tenant");
    }

    @Test
    void reconcile_createsPhaseChildSpans() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        List<String> spanNames = spans.stream().map(SpanData::getName).toList();

        assertThat(spanNames).contains("reconcile", "readActual", "plan", "execute");

        SpanData reconcileSpan = spans.stream()
                .filter(s -> s.getName().equals("reconcile")).findFirst().orElseThrow();
        String reconcileSpanId = reconcileSpan.getSpanId();

        for (String phase : List.of("readActual", "plan", "execute")) {
            SpanData phaseSpan = spans.stream()
                    .filter(s -> s.getName().equals(phase)).findFirst().orElseThrow();
            assertThat(phaseSpan.getParentSpanId())
                    .as("Phase '%s' should be child of reconcile", phase)
                    .isEqualTo(reconcileSpanId);
        }
    }

    @Test
    void simpleExecutor_createsPerNodeProvisionSpans() {
        DesiredNode nodeA = node("a");
        DesiredNode nodeB = node("b");
        DesiredStateGraph desired = factory.of(List.of(nodeA, nodeB), List.of());
        actualAdapter.setStatuses(Map.of());

        SimpleTransitionExecutor simpleExecutor = new SimpleTransitionExecutor(
                new SucceedingProvisioner(), new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());
        ReconciliationLoop loopWithSimple = new ReconciliationLoop(
                planner, simpleExecutor, actualAdapter, faultEngine, testEventSource,
                TEST_DEBOUNCE, TEST_RESYNC);

        loopWithSimple.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).until(() ->
                spanExporter.getFinishedSpanItems().stream()
                        .anyMatch(s -> s.getName().equals("provision")));

        loopWithSimple.stop("test-tenant");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        List<SpanData> provisionSpans = spans.stream()
                .filter(s -> s.getName().equals("provision")).toList();

        assertThat(provisionSpans).hasSize(2);

        Set<String> nodeIds = new HashSet<>();
        for (SpanData span : provisionSpans) {
            nodeIds.add(span.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("desiredstate.node.id")));
        }
        assertThat(nodeIds).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void simpleExecutor_failedProvisionSetsSpanStatusError() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        SimpleTransitionExecutor simpleExecutor = new SimpleTransitionExecutor(
                new FailingProvisioner(), new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());
        ReconciliationLoop loopWithSimple = new ReconciliationLoop(
                planner, simpleExecutor, actualAdapter, faultEngine, testEventSource,
                TEST_DEBOUNCE, TEST_RESYNC);

        loopWithSimple.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).until(() ->
                spanExporter.getFinishedSpanItems().stream()
                        .anyMatch(s -> s.getName().equals("provision")));

        loopWithSimple.stop("test-tenant");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData provisionSpan = spans.stream()
                .filter(s -> s.getName().equals("provision")).findFirst().orElseThrow();

        assertThat(provisionSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void simpleExecutor_createsDeprovisionSpans() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(
                NodeId.of("a"), NodeStatus.PRESENT,
                NodeId.of("orphan"), NodeStatus.PRESENT));

        SimpleTransitionExecutor simpleExecutor = new SimpleTransitionExecutor(
                new SucceedingProvisioner(), new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());
        ReconciliationLoop loopWithSimple = new ReconciliationLoop(
                planner, simpleExecutor, actualAdapter, faultEngine, testEventSource,
                TEST_DEBOUNCE, TEST_RESYNC);

        loopWithSimple.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).until(() ->
                spanExporter.getFinishedSpanItems().stream()
                        .anyMatch(s -> s.getName().equals("deprovision")));

        loopWithSimple.stop("test-tenant");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData deprovisionSpan = spans.stream()
                .filter(s -> s.getName().equals("deprovision")).findFirst().orElseThrow();

        assertThat(deprovisionSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("desiredstate.node.id")))
                .isEqualTo("orphan");
        assertThat(deprovisionSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void faultFeedback_createsSpanOnlyWhenFailuresExist() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());
        testExecutor.failNodes.add(NodeId.of("a"));

        FaultPolicy noopPolicy = (event, current) -> List.of();
        faultEngine = new FaultPolicyEngine(List.of(noopPolicy));
        loop = new ReconciliationLoop(
                planner, testExecutor, actualAdapter, faultEngine, testEventSource,
                TEST_DEBOUNCE, TEST_RESYNC);

        loop.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).contains("faultFeedback");
    }

    @Test
    void noFailures_omitsFaultFeedbackSpan() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).doesNotContain("faultFeedback");
    }

    @Test
    void emptyPlan_returnsEarlyWithoutExecuteSpan() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

        loop.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).until(() ->
                spanExporter.getFinishedSpanItems().stream()
                        .anyMatch(s -> s.getName().equals("reconcile")));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).doesNotContain("execute");
    }

    @Test
    void driftDetection_createsDetectDriftSpan() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.DRIFTED));

        FaultPolicy noopPolicy = (event, current) -> List.of();
        faultEngine = new FaultPolicyEngine(List.of(noopPolicy));
        loop = new ReconciliationLoop(
                planner, testExecutor, actualAdapter, faultEngine, testEventSource,
                TEST_DEBOUNCE, TEST_RESYNC);

        loop.start("test-tenant", desired);

        await().atMost(Duration.ofSeconds(2)).pollDelay(Duration.ofMillis(200)).until(() ->
                spanExporter.getFinishedSpanItems().stream()
                        .anyMatch(s -> s.getName().equals("detectDrift")));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData driftSpan = spans.stream()
                .filter(s -> s.getName().equals("detectDrift")).findFirst().orElseThrow();
        assertThat(driftSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.longKey("desiredstate.drift.count")))
                .isGreaterThan(0);
    }

    // --- Test helpers (same pattern as ReconciliationLoopTest) ---

    private DesiredNode node(String id) {
        return new DesiredNode(NodeId.of(id), NodeType.of("test"), new TestSpec(id), false);
    }

    record TestSpec(String value) implements NodeSpec {}

    static class TestActualStateAdapter implements ActualStateAdapter {
        private volatile Map<NodeId, NodeStatus> statuses = Map.of();

        void setStatuses(Map<NodeId, NodeStatus> statuses) {
            this.statuses = Map.copyOf(statuses);
        }

        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return new ActualState(statuses);
        }
    }

    static class TestTransitionExecutor implements TransitionExecutor {
        final CopyOnWriteArrayList<TransitionPlan> executedPlans = new CopyOnWriteArrayList<>();
        final Set<NodeId> failNodes = ConcurrentHashMap.newKeySet();

        @Override
        public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
            return Uni.createFrom().item(() -> {
                executedPlans.add(plan);
                Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
                for (OrderedStep step : plan.removals()) {
                    outcomes.put(step.node().id(), new StepOutcome.Succeeded());
                }
                for (OrderedStep step : plan.additions()) {
                    if (failNodes.contains(step.node().id())) {
                        outcomes.put(step.node().id(), new StepOutcome.Failed("test failure"));
                    } else {
                        outcomes.put(step.node().id(), new StepOutcome.Succeeded());
                    }
                }
                return new TransitionResult(outcomes);
            });
        }
    }

    static class TestEventSource implements EventSource {
        private final AtomicReference<MultiEmitter<? super StateEvent>> emitterRef = new AtomicReference<>();
        private final Multi<StateEvent> multi;

        TestEventSource() {
            this.multi = Multi.createFrom().emitter(emitter -> emitterRef.set(emitter));
        }

        void emit(StateEvent event) {
            MultiEmitter<? super StateEvent> emitter = emitterRef.get();
            if (emitter != null) {
                emitter.emit(event);
            }
        }

        @Override
        public Multi<StateEvent> stream() {
            return multi;
        }
    }

    static class SucceedingProvisioner implements NodeProvisioner {
        @Override
        public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
            return new ProvisionResult.Success();
        }

        @Override
        public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
            return new DeprovisionResult.Success();
        }
    }

    static class FailingProvisioner implements NodeProvisioner {
        @Override
        public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
            return new ProvisionResult.Failed("simulated failure");
        }

        @Override
        public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
            return new DeprovisionResult.Failed("simulated failure");
        }
    }
}
