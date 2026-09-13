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
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossDomainIntegrationTest {

    static final DesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();
    static final NodeType NS = NodeType.of("k8s-namespace");
    static final NodeType AGENT = NodeType.of("agent");
    static final NodeType TYPE_A = NodeType.of("type-a");
    static final NodeType TYPE_B = NodeType.of("type-b");
    static final NodeType TYPE_C = NodeType.of("type-c");
    static final NodeType TYPE_DB = NodeType.of("database");

    record NsSpec(String n) implements NodeSpec {
        @Override public NodeType nodeType() { return NS; }
    }

    record AgentSpec(String n) implements NodeSpec {
        @Override public NodeType nodeType() { return AGENT; }
    }

    record SpecA(String n) implements NodeSpec {
        @Override public NodeType nodeType() { return TYPE_A; }
    }

    record SpecB(String n) implements NodeSpec {
        @Override public NodeType nodeType() { return TYPE_B; }
    }

    record SpecC(String n) implements NodeSpec {
        @Override public NodeType nodeType() { return TYPE_C; }
    }

    record DbSpec(String n) implements NodeSpec {
        @Override public NodeType nodeType() { return TYPE_DB; }
    }

    @Test
    void flattened_infraBeforeDeployment() {
        var infraGraph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("infra:ns"), new NsSpec("prod"), HumanGating.NONE));
        var deployGraph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("deploy:agent"), new AgentSpec("main"), HumanGating.NONE));

        var engine = new CrossDomainCompositionEngine(FACTORY);
        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(infraGraph)).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(deployGraph)).provides(Set.of(AGENT))
            .requires(Set.of(NS)).build());
        engine.compose();

        var tenantState = engine.initTenantState();
        var composed = engine.recompose("t1", tenantState);

        var planner = new TransitionPlanner();
        var plan = planner.plan(composed, new ActualState(Map.of()));

        List<NodeId> additionOrder = plan.additions().stream()
            .map(step -> step.node().id()).toList();
        assertThat(additionOrder.indexOf(NodeId.of("infra:ns")))
            .isLessThan(additionOrder.indexOf(NodeId.of("deploy:agent")));
    }

    @Test
    void singleDomain_backwardCompatible() {
        var engine = new CrossDomainCompositionEngine(FACTORY);
        var graph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("n1"), new NsSpec("x"), HumanGating.NONE));
        engine.registerDomain(DomainRegistration.builder(DomainId.of("only"),
                CompilationResult.single(graph)).provides(Set.of(NS)).build());
        engine.compose();

        assertThat(engine.isActive()).isFalse();
        assertThat(engine.registrationCount()).isEqualTo(1);
    }

    @Test
    void threeDomainChain_orderedCorrectly() {
        var gA = FACTORY.empty().withNode(new DesiredNode(NodeId.of("a:1"), new SpecA("a"), HumanGating.NONE));
        var gB = FACTORY.empty().withNode(new DesiredNode(NodeId.of("b:1"), new SpecB("b"), HumanGating.NONE));
        var gC = FACTORY.empty().withNode(new DesiredNode(NodeId.of("c:1"), new SpecC("c"), HumanGating.NONE));

        var engine = new CrossDomainCompositionEngine(FACTORY);
        engine.registerDomain(DomainRegistration.builder(DomainId.of("domC"),
                CompilationResult.single(gC)).provides(Set.of(TYPE_C)).requires(Set.of(TYPE_B)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("domA"),
                CompilationResult.single(gA)).provides(Set.of(TYPE_A)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("domB"),
                CompilationResult.single(gB)).provides(Set.of(TYPE_B)).requires(Set.of(TYPE_A)).build());
        engine.compose();

        assertThat(engine.topologicalOrder()).containsExactly(
            DomainId.of("domA"), DomainId.of("domB"), DomainId.of("domC"));

        var ts = engine.initTenantState();
        var composed = engine.recompose("t1", ts);
        assertThat(composed.nodes()).hasSize(3);

        var planner = new TransitionPlanner();
        var plan = planner.plan(composed, new ActualState(Map.of()));
        List<NodeId> order = plan.additions().stream()
            .map(step -> step.node().id()).toList();
        assertThat(order.indexOf(NodeId.of("a:1")))
            .isLessThan(order.indexOf(NodeId.of("b:1")));
        assertThat(order.indexOf(NodeId.of("b:1")))
            .isLessThan(order.indexOf(NodeId.of("c:1")));
    }

    @Test
    void flattened_lifecyclePhaseAdvancement_updatesComposedGraph() {
        var p1Graph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("infra:ns-v1"), new NsSpec("v1"), HumanGating.NONE));
        var p2Graph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("infra:ns-v2"), new NsSpec("v2"), HumanGating.NONE));
        var lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("setup", p1Graph, CompletionCondition.allPresent()),
            new Phase("ready", p2Graph, CompletionCondition.allPresent())));

        var deployGraph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("deploy:a"), new AgentSpec("a"), HumanGating.NONE));

        var engine = new CrossDomainCompositionEngine(FACTORY);
        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"), lifecycle)
            .provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(deployGraph)).provides(Set.of(AGENT))
            .requires(Set.of(NS)).build());
        engine.compose();

        var tenantState = engine.initTenantState();
        engine.setTenantState("t1", tenantState);

        // Phase 1 composed graph has infra:ns-v1
        var composed1 = engine.recompose("t1", tenantState);
        assertThat(composed1.nodes()).containsKey(NodeId.of("infra:ns-v1"));
        assertThat(composed1.nodes()).doesNotContainKey(NodeId.of("infra:ns-v2"));

        // Simulate all phase 1 nodes present
        ActualState actual = new ActualState(Map.of(
            NodeId.of("infra:ns-v1"), NodeStatus.PRESENT,
            NodeId.of("deploy:a"), NodeStatus.PRESENT));
        engine.onReconciliationCycleCompleted("t1", composed1, actual);

        // After advancement, phase 2 composed graph has infra:ns-v2
        var newState = engine.getTenantState("t1");
        var composed2 = engine.recompose("t1", newState);
        assertThat(composed2.nodes()).containsKey(NodeId.of("infra:ns-v2"));
    }

    @Test
    void hierarchical_metaGraphPreservesTopologicalOrder() {
        var engine = new CrossDomainCompositionEngine(FACTORY);
        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("data"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(TYPE_DB))
            .requires(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(AGENT))
            .requires(Set.of(TYPE_DB)).build());
        engine.validate();

        var metaGraph = engine.buildMetaGraph();
        var planner = new TransitionPlanner();
        var plan = planner.plan(metaGraph, new ActualState(Map.of()));

        List<NodeId> order = plan.additions().stream()
            .map(step -> step.node().id()).toList();
        assertThat(order.indexOf(NodeId.of("domain:infra")))
            .isLessThan(order.indexOf(NodeId.of("domain:data")));
        assertThat(order.indexOf(NodeId.of("domain:data")))
            .isLessThan(order.indexOf(NodeId.of("domain:deploy")));
    }

    @Test
    void zeroDomains_engineIsInert() {
        var engine = new CrossDomainCompositionEngine(FACTORY);
        engine.compose();
        assertThat(engine.isActive()).isFalse();
        assertThat(engine.registrationCount()).isEqualTo(0);
    }
}
