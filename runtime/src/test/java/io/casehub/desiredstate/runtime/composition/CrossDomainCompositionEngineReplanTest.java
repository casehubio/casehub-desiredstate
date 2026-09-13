package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossDomainCompositionEngineReplanTest {

    static final DesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();
    static final NodeType NS = NodeType.of("k8s-namespace");
    static final NodeType AGENT = NodeType.of("agent");

    record NsSpec(String n) implements NodeSpec { @Override public NodeType nodeType() { return NS; } }
    record AgentSpec(String n) implements NodeSpec { @Override public NodeType nodeType() { return AGENT; } }

    CrossDomainCompositionEngine engine;
    ActiveSituation testSituation;

    @BeforeEach void setUp() {
        engine = new CrossDomainCompositionEngine(FACTORY);
        testSituation = new ActiveSituation("sit-1", "corr-1", "t1", 0.9,
            Map.of(), Instant.now(), Instant.now(), 1);
    }

    @Test void handleReplan_domainRecompiler_receivesDomainGraph() {
        var infraGraph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("infra:ns"), new NsSpec("prod"), HumanGating.NONE));
        var deployGraph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("deploy:a"), new AgentSpec("a"), HumanGating.NONE));

        var capturedGraph = new DesiredStateGraph[1];
        SituationRecompiler infraRecompiler = new SituationRecompiler() {
            @Override public Optional<CompilationResult> recompile(String t, DesiredStateGraph current,
                    ActualState actual, ActiveSituation sit, DesiredStateGraphFactory f) {
                capturedGraph[0] = current;
                return Optional.of(CompilationResult.single(current));
            }
            @Override public int priority() { return 0; }
        };

        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(infraGraph)).provides(Set.of(NS))
            .situationRecompilers(List.of(infraRecompiler)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(deployGraph)).provides(Set.of(AGENT))
            .requires(Set.of(NS)).build());
        engine.compose();
        engine.setTenantState("t1", engine.initTenantState());

        ActualState actual = new ActualState(Map.of());
        engine.handleReplan("t1", actual, testSituation, FACTORY);

        assertThat(capturedGraph[0].nodes()).containsKey(NodeId.of("infra:ns"));
        assertThat(capturedGraph[0].nodes()).doesNotContainKey(NodeId.of("deploy:a"));
    }

    @Test void handleReplan_noTenantState_returnsEmpty() {
        engine.registerDomain(DomainRegistration.builder(DomainId.of("d"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(NS)).build());
        engine.compose();
        var result = engine.handleReplan("unknown", new ActualState(Map.of()),
            testSituation, FACTORY);
        assertThat(result).isEmpty();
    }

    @Test void handleReplan_noRecompilersMatch_returnsEmpty() {
        var graph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new NsSpec("x"), HumanGating.NONE));
        engine.registerDomain(DomainRegistration.builder(DomainId.of("d"),
                CompilationResult.single(graph)).provides(Set.of(NS)).build());
        engine.compose();
        engine.setTenantState("t1", engine.initTenantState());

        var result = engine.handleReplan("t1", new ActualState(Map.of()),
            testSituation, FACTORY);
        assertThat(result).isEmpty();
    }

    @Test void handleReplan_updatesdomainState() {
        var originalGraph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("infra:ns"), new NsSpec("v1"), HumanGating.NONE));
        var recompiledGraph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("infra:ns-v2"), new NsSpec("v2"), HumanGating.NONE));

        SituationRecompiler recompiler = new SituationRecompiler() {
            @Override public Optional<CompilationResult> recompile(String t, DesiredStateGraph current,
                    ActualState actual, ActiveSituation sit, DesiredStateGraphFactory f) {
                return Optional.of(CompilationResult.single(recompiledGraph));
            }
            @Override public int priority() { return 0; }
        };

        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(originalGraph)).provides(Set.of(NS))
            .situationRecompilers(List.of(recompiler)).build());
        engine.compose();
        engine.setTenantState("t1", engine.initTenantState());

        engine.handleReplan("t1", new ActualState(Map.of()), testSituation, FACTORY);

        var state = engine.getTenantState("t1");
        assertThat(state.phases().get(DomainId.of("infra")).currentGraph().nodes())
            .containsKey(NodeId.of("infra:ns-v2"));
    }
}
