package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static io.casehub.desiredstate.testing.TestTimeouts.AWAIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LifecycleManagerTest {

    private ReconciliationLoop loop;
    private LifecycleManager manager;
    private MockActualStateAdapter adapter;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        adapter = new MockActualStateAdapter();
        adapter.setHandledTypes(Set.of(NodeType.of("t")));
        factory = new DefaultDesiredStateGraphFactory();
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        loop = new ReconciliationLoop(
            new TransitionPlanner(), new MockTransitionExecutor(), adapterRouter,
            new FaultPolicyEngine(List.of()),
            () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofMillis(200));
        manager = new LifecycleManager(loop);
    }

    @AfterEach
    void tearDown() {
        manager.stop("t1");
    }

    @Test
    void singleGraph_startsReconciliationDirectly() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.makePresent(NodeId.of("a"));

        manager.start("t1", CompilationResult.single(graph));

        await().atMost(AWAIT).until(() -> loop.getDesired("t1") != null);
        assertThat(loop.getDesired("t1")).isSameAs(graph);
    }

    @Test
    void lifecycle_transitionsOnCompletion() throws Exception {
        DesiredNode buildNode = new DesiredNode(NodeId.of("build"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredNode defendNode = new DesiredNode(NodeId.of("defend"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);

        DesiredStateGraph buildGraph = ImmutableDesiredStateGraph.empty().withNode(buildNode);
        DesiredStateGraph defendGraph = ImmutableDesiredStateGraph.empty().withNode(defendNode);

        adapter.makePresent(NodeId.of("build"));
        adapter.makePresent(NodeId.of("defend"));

        CompilationResult lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));

        manager.start("t1", lifecycle);

        await().atMost(AWAIT).until(() -> {
            DesiredStateGraph g = loop.getDesired("t1");
            return g != null && g.nodes().containsKey(NodeId.of("defend"));
        });
    }

    @Test
    void lifecycle_staysOnPhaseUntilComplete() throws Exception {
        DesiredNode buildNode = new DesiredNode(NodeId.of("build"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredNode defendNode = new DesiredNode(NodeId.of("defend"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);

        DesiredStateGraph buildGraph = ImmutableDesiredStateGraph.empty().withNode(buildNode);
        DesiredStateGraph defendGraph = ImmutableDesiredStateGraph.empty().withNode(defendNode);

        // build node is ABSENT — phase should not complete
        adapter.makeAbsent(NodeId.of("build"));

        CompilationResult lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));

        manager.start("t1", lifecycle);

        await().atMost(AWAIT).until(() -> loop.getDesired("t1") != null);
        DesiredStateGraph current = loop.getDesired("t1");
        assertThat(current.nodes()).containsKey(NodeId.of("build"));
        assertThat(current.nodes()).doesNotContainKey(NodeId.of("defend"));
    }

    @Test
    void stop_cleansUpLifecycleState() {
        DesiredNode node = new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.makePresent(NodeId.of("a"));

        manager.start("t1", CompilationResult.single(graph));
        manager.stop("t1");

        assertThat(loop.activeTenantCount()).isZero();
    }

    private record TestSpec() implements NodeSpec {}


}
