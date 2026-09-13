package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossDomainCompositionEngineLifecycleTest {

    static final DesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();
    static final NodeType NS = NodeType.of("k8s-namespace");
    static final NodeType AGENT = NodeType.of("agent");

    record NsSpec(String n) implements NodeSpec { @Override public NodeType nodeType() { return NS; } }
    record AgentSpec(String n) implements NodeSpec { @Override public NodeType nodeType() { return AGENT; } }

    CrossDomainCompositionEngine engine;

    @BeforeEach void setUp() {
        engine = new CrossDomainCompositionEngine(FACTORY);
    }

    @Test void phaseAdvancement_recomposes() {
        var g1 = FACTORY.empty().withNode(new DesiredNode(NodeId.of("infra:ns"), new NsSpec("p1"), HumanGating.NONE));
        var g2 = FACTORY.empty().withNode(new DesiredNode(NodeId.of("infra:ns-v2"), new NsSpec("p2"), HumanGating.NONE));
        var lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("setup", g1, CompletionCondition.allPresent()),
            new Phase("ready", g2, CompletionCondition.allPresent())));

        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"), lifecycle)
            .provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(FACTORY.empty().withNode(
                    new DesiredNode(NodeId.of("deploy:a"), new AgentSpec("a"), HumanGating.NONE))))
            .provides(Set.of(AGENT)).requires(Set.of(NS)).build());
        engine.compose();

        var tenantState = engine.initTenantState();
        engine.setTenantState("t1", tenantState);

        ActualState actual = new ActualState(Map.of(
            NodeId.of("infra:ns"), NodeStatus.PRESENT,
            NodeId.of("deploy:a"), NodeStatus.PRESENT));

        var composed = engine.recompose("t1", tenantState);
        engine.onReconciliationCycleCompleted("t1", composed, actual);

        var newState = engine.getTenantState("t1");
        var infraPhase = newState.phases().get(DomainId.of("infra"));
        assertThat(infraPhase.phaseIndex()).isEqualTo(1);
        assertThat(infraPhase.currentGraph().nodes()).containsKey(NodeId.of("infra:ns-v2"));
    }

    @Test void noLifecycle_noAdvancement() {
        var graph = FACTORY.empty().withNode(new DesiredNode(
            NodeId.of("n1"), new NsSpec("x"), HumanGating.NONE));
        engine.registerDomain(DomainRegistration.builder(DomainId.of("d1"),
                CompilationResult.single(graph)).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("d2"),
                CompilationResult.single(FACTORY.empty().withNode(
                    new DesiredNode(NodeId.of("n2"), new AgentSpec("a"), HumanGating.NONE))))
            .provides(Set.of(AGENT)).build());
        engine.compose();
        var ts = engine.initTenantState();
        engine.setTenantState("t1", ts);

        ActualState actual = new ActualState(Map.of(
            NodeId.of("n1"), NodeStatus.PRESENT,
            NodeId.of("n2"), NodeStatus.PRESENT));
        var composed = engine.recompose("t1", ts);
        engine.onReconciliationCycleCompleted("t1", composed, actual);

        assertThat(engine.getTenantState("t1").phases().get(DomainId.of("d1")).phaseIndex()).isEqualTo(0);
    }

    @Test void onTenantStopped_cleansUp() {
        engine.registerDomain(DomainRegistration.builder(DomainId.of("d"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(NS)).build());
        engine.compose();
        engine.setTenantState("t1", engine.initTenantState());
        assertThat(engine.getTenantState("t1")).isNotNull();
        engine.onTenantStopped("t1");
        assertThat(engine.getTenantState("t1")).isNull();
    }

    @Test void phaseNotComplete_noAdvancement() {
        var g1 = FACTORY.empty().withNode(new DesiredNode(NodeId.of("infra:ns"), new NsSpec("p1"), HumanGating.NONE));
        var g2 = FACTORY.empty().withNode(new DesiredNode(NodeId.of("infra:ns-v2"), new NsSpec("p2"), HumanGating.NONE));
        var lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("setup", g1, CompletionCondition.allPresent()),
            new Phase("ready", g2, CompletionCondition.allPresent())));

        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"), lifecycle)
            .provides(Set.of(NS)).build());
        engine.compose();

        var tenantState = engine.initTenantState();
        engine.setTenantState("t1", tenantState);

        ActualState actual = new ActualState(Map.of(
            NodeId.of("infra:ns"), NodeStatus.ABSENT));

        var composed = engine.recompose("t1", tenantState);
        engine.onReconciliationCycleCompleted("t1", composed, actual);

        assertThat(engine.getTenantState("t1").phases().get(DomainId.of("infra")).phaseIndex()).isEqualTo(0);
    }
}
