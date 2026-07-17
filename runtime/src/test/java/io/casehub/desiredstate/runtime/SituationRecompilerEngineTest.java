package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SituationRecompilerEngineTest {

    private final DesiredStateGraph graph = ImmutableDesiredStateGraph.empty();
    private final ActualState actual = new ActualState(Map.of());
    private final ActiveSituation situation = new ActiveSituation(
        "sit-1", "zone-A", "tenant-1", 0.95,
        Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);

    private record TestSpec(String value) implements NodeSpec {}

    @Test
    void emptyRecompilerList_shouldReturnEmpty() {
        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of());
        Optional<CompilationResult> result = engine.recompile("tenant-1", graph, actual, situation, null);
        assertThat(result).isEmpty();
    }

    @Test
    void singleRecompiler_returnsNonEmpty_shouldReturnResult() {
        DesiredStateGraph newGraph = graph.withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE));
        SituationRecompiler recompiler = (tid, c, a, s, f) -> Optional.of(CompilationResult.single(newGraph));

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(recompiler));
        Optional<CompilationResult> result = engine.recompile("tenant-1", graph, actual, situation, null);

        assertThat(result).isPresent();
    }

    @Test
    void multipleRecompilers_firstReturnsEmpty_secondReturnsResult() {
        SituationRecompiler empty = (tid, c, a, s, f) -> Optional.empty();
        SituationRecompiler hasResult = (tid, c, a, s, f) -> Optional.of(CompilationResult.single(graph));

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(empty, hasResult));
        Optional<CompilationResult> result = engine.recompile("tenant-1", graph, actual, situation, null);

        assertThat(result).isPresent();
    }

    @Test
    void allRecompilersReturnEmpty_shouldReturnEmpty() {
        SituationRecompiler e1 = (tid, c, a, s, f) -> Optional.empty();
        SituationRecompiler e2 = (tid, c, a, s, f) -> Optional.empty();

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(e1, e2));
        Optional<CompilationResult> result = engine.recompile("tenant-1", graph, actual, situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldRespectPriorityOrdering() {
        DesiredStateGraph lowGraph = graph.withNode(
            new DesiredNode(NodeId.of("low"), NodeType.of("test"), new TestSpec("low"), HumanGating.NONE));
        DesiredStateGraph highGraph = graph.withNode(
            new DesiredNode(NodeId.of("high"), NodeType.of("test"), new TestSpec("high"), HumanGating.NONE));

        SituationRecompiler lowPriority = new SituationRecompiler() {
            @Override
            public Optional<CompilationResult> recompile(String tenancyId, DesiredStateGraph c, ActualState a,
                    ActiveSituation s, DesiredStateGraphFactory f) {
                return Optional.of(CompilationResult.single(lowGraph));
            }
            @Override
            public int priority() { return 0; }
        };

        SituationRecompiler highPriority = new SituationRecompiler() {
            @Override
            public Optional<CompilationResult> recompile(String tenancyId, DesiredStateGraph c, ActualState a,
                    ActiveSituation s, DesiredStateGraphFactory f) {
                return Optional.of(CompilationResult.single(highGraph));
            }
            @Override
            public int priority() { return Integer.MAX_VALUE; }
        };

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(highPriority, lowPriority));
        Optional<CompilationResult> result = engine.recompile("tenant-1", graph, actual, situation, null);

        assertThat(result).isPresent();
        CompilationResult.SingleGraph sg = (CompilationResult.SingleGraph) result.get();
        assertThat(sg.graph().nodes()).containsKey(NodeId.of("low"));
    }

    @Test
    void firstMatchWins_shouldNotCallSubsequentRecompilers() {
        List<String> callOrder = new ArrayList<>();

        SituationRecompiler first = (tid, c, a, s, f) -> {
            callOrder.add("first");
            return Optional.of(CompilationResult.single(graph));
        };
        SituationRecompiler second = (tid, c, a, s, f) -> {
            callOrder.add("second");
            return Optional.of(CompilationResult.single(graph));
        };

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(first, second));
        engine.recompile("tenant-1", graph, actual, situation, null);

        assertThat(callOrder).containsExactly("first");
    }
}
