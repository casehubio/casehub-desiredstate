package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DesiredStateHistoryStore;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.InMemoryDesiredStateHistoryStore;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.testing.CannedEventSource;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationLoopRollbackTest {

    private DesiredStateGraphFactory factory;
    private MockActualStateAdapter actualAdapter;
    private MockTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private FaultPolicyEngine faultEngine;
    private CannedEventSource testEventSource;
    private InMemoryDesiredStateHistoryStore historyStore;
    private ReconciliationLoop loop;
    private final CopyOnWriteArrayList<CloudEvent> emittedEvents = new CopyOnWriteArrayList<>();

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(50);
    private static final Duration TEST_RESYNC = Duration.ofHours(1);

    record SimpleSpec() implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("test"); }
    }

    @BeforeEach
    void setUp() {
        factory       = new DefaultDesiredStateGraphFactory();
        actualAdapter = new MockActualStateAdapter();
        actualAdapter.setHandledTypes(Set.of(NodeType.of("test")));
        testExecutor    = new MockTransitionExecutor();
        planner         = new TransitionPlanner();
        faultEngine     = new FaultPolicyEngine(List.of());
        testEventSource = new CannedEventSource();
        historyStore    = new InMemoryDesiredStateHistoryStore();

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
                                 .debounceWindow(TEST_DEBOUNCE)
                                 .resyncInterval(TEST_RESYNC)
                                 .historyStore(historyStore)
                                 .cloudEventSink(emittedEvents::add)
                                 .build();
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    private DesiredStateGraph graphWith(String nodeId) {
        return factory.of(
            List.of(new DesiredNode(NodeId.of(nodeId), new SimpleSpec(), HumanGating.NONE)),
            List.of());
    }

    @Test
    void rollbackSwapsDesiredToHistoricalGraph() {
        DesiredStateGraph g1 = graphWith("node-1");
        DesiredStateGraph g2 = graphWith("node-2");

        loop.start("t1", g1);
        loop.updateDesired("t1", g2);

        loop.rollback("t1", 1, "admin");

        DesiredStateGraph current = loop.getDesired("t1");
        assertThat(current.nodes()).containsKey(NodeId.of("node-1"));
        assertThat(current.nodes()).doesNotContainKey(NodeId.of("node-2"));
    }

    @Test
    void rollbackThrowsOnUnknownVersion() {
        loop.start("t1", graphWith("n1"));

        assertThatThrownBy(() -> loop.rollback("t1", 99, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Version 99 not found");
    }

    @Test
    void rollbackThrowsOnUnknownTenant() {
        assertThatThrownBy(() -> loop.rollback("unknown", 1, "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No reconciliation loop running");
    }

    @Test
    void rollbackThrowsWhenHistoryStoreNotConfigured() {
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        var loopNoHistory = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
                                              .debounceWindow(TEST_DEBOUNCE)
                                              .resyncInterval(TEST_RESYNC)
                                              .build();
        loopNoHistory.start("t1", graphWith("n1"));

        assertThatThrownBy(() -> loopNoHistory.rollback("t1", 1, "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("History store not configured");

        loopNoHistory.shutdown();
    }

    @Test
    void rollbackEmitsCloudEvent() {
        loop.start("t1", graphWith("n1"));
        loop.updateDesired("t1", graphWith("n2"));

        loop.rollback("t1", 1, "admin");

        assertThat(emittedEvents).anySatisfy(event ->
            assertThat(event.getType()).isEqualTo(
                io.casehub.desiredstate.api.DesiredStateEventTypes.ROLLBACK_INITIATED));
    }

    @Test
    void rollbackCancelsLifecycle() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        var loopWithCanceller = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
                                                   .debounceWindow(TEST_DEBOUNCE)
                                                   .resyncInterval(TEST_RESYNC)
                                                   .historyStore(historyStore)
                                                   .cloudEventSink(emittedEvents::add)
                                                   .lifecycleCanceller(tid -> cancelled.set(true))
                                                   .build();
        loopWithCanceller.start("t1", graphWith("n1"));
        loopWithCanceller.updateDesired("t1", graphWith("n2"));

        loopWithCanceller.rollback("t1", 1, "admin");

        assertThat(cancelled).isTrue();
        loopWithCanceller.shutdown();
    }

    @Test
    void rollbackRecordsItselfInHistory() {
        loop.start("t1", graphWith("n1"));
        loop.updateDesired("t1", graphWith("n2"));

        loop.rollback("t1", 1, "admin");

        var versions = historyStore.listVersions("t1");
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).source()).isEqualTo("rollback");
        assertThat(versions.get(0).initiatedBy()).isEqualTo("admin");
    }

    @Test
    void rollbackSkipsPruning() {
        loop.start("t1", graphWith("n1"));
        for (int i = 2; i <= 12; i++) {
            loop.updateDesired("t1", graphWith("n" + i));
        }
        var preRollbackVersions = historyStore.listVersions("t1");
        assertThat(preRollbackVersions).hasSize(10);

        loop.rollback("t1", preRollbackVersions.get(preRollbackVersions.size() - 1).version(), "admin");

        var postRollbackVersions = historyStore.listVersions("t1");
        assertThat(postRollbackVersions).hasSizeGreaterThan(10);
    }

    @Test
    void updateDesiredRecordsHistory() {
        loop.start("t1", graphWith("n1"));
        loop.updateDesired("t1", graphWith("n2"));

        var versions = historyStore.listVersions("t1");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).source()).isEqualTo("updateDesired");
    }

    @Test
    void startRecordsHistory() {
        loop.start("t1", graphWith("n1"));

        var versions = historyStore.listVersions("t1");
        assertThat(versions).hasSize(1);
    }
}
