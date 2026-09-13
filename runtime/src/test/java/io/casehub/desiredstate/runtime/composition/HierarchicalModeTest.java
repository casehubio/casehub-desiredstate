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
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchicalModeTest {

    static final DesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();
    static final NodeType NS = NodeType.of("k8s-namespace");
    static final NodeType AGENT = NodeType.of("agent");

    record NsSpec(String n) implements NodeSpec {
        @Override public NodeType nodeType() { return NS; }
    }

    record AgentSpec(String n) implements NodeSpec {
        @Override public NodeType nodeType() { return AGENT; }
    }

    @Test
    void buildMetaGraph_createsDomainNodes() {
        var engine = new CrossDomainCompositionEngine(FACTORY);
        var infraGraph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("infra:ns"), new NsSpec("prod"), HumanGating.NONE));
        var deployGraph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("deploy:a"), new AgentSpec("a"), HumanGating.NONE));

        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(infraGraph)).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(deployGraph)).provides(Set.of(AGENT))
            .requires(Set.of(NS)).build());
        engine.validate();

        DesiredStateGraph metaGraph = engine.buildMetaGraph();
        assertThat(metaGraph.nodes()).hasSize(2);
        assertThat(metaGraph.nodes().get(NodeId.of("domain:infra")).spec())
            .isInstanceOf(DomainNodeSpec.class);
        assertThat(metaGraph.nodes().get(NodeId.of("domain:deploy")).spec())
            .isInstanceOf(DomainNodeSpec.class);
    }

    @Test
    void buildMetaGraph_addsDomainDependencyEdges() {
        var engine = new CrossDomainCompositionEngine(FACTORY);
        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(AGENT))
            .requires(Set.of(NS)).build());
        engine.validate();

        DesiredStateGraph metaGraph = engine.buildMetaGraph();
        assertThat(metaGraph.dependenciesOf(NodeId.of("domain:deploy")))
            .contains(NodeId.of("domain:infra"));
    }

    @Test
    void buildMetaGraph_noDependencies_noEdges() {
        var engine = new CrossDomainCompositionEngine(FACTORY);
        engine.registerDomain(DomainRegistration.builder(DomainId.of("a"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("b"),
                CompilationResult.single(FACTORY.empty())).provides(Set.of(AGENT)).build());
        engine.validate();

        DesiredStateGraph metaGraph = engine.buildMetaGraph();
        assertThat(metaGraph.dependencies()).isEmpty();
    }

    @Test
    void domainNodeProvisioner_reportsNotReadyWhenInnerLoopIncomplete() {
        var graph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new NsSpec("x"), HumanGating.NONE));
        var reg = DomainRegistration.builder(DomainId.of("test"),
                CompilationResult.single(graph)).provides(Set.of(NS)).build();

        var provisioner = new DomainNodeProvisioner(FACTORY);
        var result = provisioner.checkReadiness(reg, new ActualState(Map.of()));
        assertThat(result).isFalse();
    }

    @Test
    void domainNodeProvisioner_reportsReadyWhenAllPresent() {
        var graph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new NsSpec("x"), HumanGating.NONE));
        var reg = DomainRegistration.builder(DomainId.of("test"),
                CompilationResult.single(graph)).provides(Set.of(NS)).build();

        var provisioner = new DomainNodeProvisioner(FACTORY);
        var result = provisioner.checkReadiness(reg,
            new ActualState(Map.of(NodeId.of("n1"), NodeStatus.PRESENT)));
        assertThat(result).isTrue();
    }

    @Test
    void domainActualStateAdapter_handlesOnlyDomainType() {
        var adapter = new DomainActualStateAdapter(
            new DomainNodeProvisioner(FACTORY), Map.of());
        assertThat(adapter.handledTypes()).containsExactly(DomainNodeSpec.DOMAIN_NODE_TYPE);
    }

    @Test
    void domainActualStateAdapter_reportsAbsentForUnstartedDomains() {
        var graph = FACTORY.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new NsSpec("x"), HumanGating.NONE));
        var reg = DomainRegistration.builder(DomainId.of("test"),
                CompilationResult.single(graph)).provides(Set.of(NS)).build();
        var spec = new DomainNodeSpec(DomainId.of("test"), reg);
        var domainNode = new DesiredNode(NodeId.of("domain:test"), spec, HumanGating.NONE);

        var metaGraph = FACTORY.empty().withNode(domainNode);
        var adapter = new DomainActualStateAdapter(
            new DomainNodeProvisioner(FACTORY), Map.of(DomainId.of("test"), reg));

        ActualState actual = adapter.readActual(metaGraph, "t1");
        assertThat(actual.statuses().get(NodeId.of("domain:test"))).isEqualTo(NodeStatus.ABSENT);
    }
}
