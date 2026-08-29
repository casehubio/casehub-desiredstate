package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.testing.CannedEventSource;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.casehub.desiredstate.testing.TestTimeouts.AWAIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReconciliationTracingTest {

    private static final LifecycleStepExecutor noOpStepExecutor = (step, tenancyId) -> new StepOutcome.Succeeded();

    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;

    private DesiredStateGraphFactory factory;
    private MockActualStateAdapter actualAdapter;
    private MockTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private FaultPolicyEngine faultEngine;
    private CannedEventSource testEventSource;
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
        actualAdapter = new MockActualStateAdapter();
        actualAdapter.setHandledTypes(Set.of(NodeType.of("test")));
        testExecutor = new MockTransitionExecutor();
        planner = new TransitionPlanner();
        faultEngine = new FaultPolicyEngine(List.of());
        testEventSource = new CannedEventSource();

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();
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

        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

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

        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

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

        var router = new DefaultNodeProvisionerRouter(List.of(new SucceedingProvisioner()));
        SimpleTransitionExecutor simpleExecutor = new SimpleTransitionExecutor(router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler(), noOpStepExecutor);
        var adapterRouterLocal = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        ReconciliationLoop loopWithSimple = ReconciliationLoop.builder(planner, simpleExecutor, adapterRouterLocal, faultEngine, testEventSource::stream)
                .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loopWithSimple.start("test-tenant", desired);

        await().atMost(AWAIT).until(() ->
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

        var router = new DefaultNodeProvisionerRouter(List.of(new FailingProvisioner()));
        SimpleTransitionExecutor simpleExecutor = new SimpleTransitionExecutor(router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler(), noOpStepExecutor);
        var adapterRouterLocal = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        ReconciliationLoop loopWithSimple = ReconciliationLoop.builder(planner, simpleExecutor, adapterRouterLocal, faultEngine, testEventSource::stream)
                .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loopWithSimple.start("test-tenant", desired);

        await().atMost(AWAIT).until(() ->
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

        var router = new DefaultNodeProvisionerRouter(List.of(new SucceedingProvisioner()));
        SimpleTransitionExecutor simpleExecutor = new SimpleTransitionExecutor(router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler(), noOpStepExecutor);
        var adapterRouterLocal = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        ReconciliationLoop loopWithSimple = ReconciliationLoop.builder(planner, simpleExecutor, adapterRouterLocal, faultEngine, testEventSource::stream)
                .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loopWithSimple.start("test-tenant", desired);

        await().atMost(AWAIT).until(() ->
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

        FaultPolicy noopPolicy = (tid, event, current, actual) -> List.of();
        faultEngine = new FaultPolicyEngine(List.of(noopPolicy));
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loop.start("test-tenant", desired);

        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).contains("faultFeedback");
    }

    @Test
    void noFailures_omitsFaultFeedbackSpan() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).doesNotContain("faultFeedback");
    }

    @Test
    void emptyPlan_returnsEarlyWithoutExecuteSpan() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

        loop.start("test-tenant", desired);

        await().atMost(AWAIT).until(() ->
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

        FaultPolicy noopPolicy = (tid, event, current, actual) -> List.of();
        faultEngine = new FaultPolicyEngine(List.of(noopPolicy));
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loop.start("test-tenant", desired);

        await().atMost(AWAIT).pollDelay(Duration.ofMillis(200)).until(() ->
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
        return new DesiredNode(NodeId.of(id), new TestSpec(id), HumanGating.NONE);
    }

    record TestSpec(String value) implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }

    static class SucceedingProvisioner implements NodeProvisioner {
        @Override
        public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
            return new ProvisionResult.Success();
        }

        @Override
        public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
            return new DeprovisionResult.Success();
        }

        @Override
        public Set<NodeType> handledTypes() {
            return Set.of(NodeType.of("test"), NodeType.of("unknown"));
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

        @Override
        public Set<NodeType> handledTypes() {
            return Set.of(NodeType.of("test"));
        }
    }
}
