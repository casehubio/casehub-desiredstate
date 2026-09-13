package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossDomainCompositionEngineFlattenedTest {

    static final DesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();
    static final NodeType NS = NodeType.of("k8s-namespace");
    static final NodeType AGENT = NodeType.of("agent");

    record NsSpec(String name) implements NodeSpec {
        @Override public NodeType nodeType() { return NS; }
    }
    record AgentSpec(String name) implements NodeSpec {
        @Override public NodeType nodeType() { return AGENT; }
    }

    CrossDomainCompositionEngine engine;

    @BeforeEach void setUp() {
        engine = new CrossDomainCompositionEngine(FACTORY);
    }

    @Test void compose_mergesGraphsViaOverlay() {
        var infraGraph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("infra:ns"), new NsSpec("prod"), HumanGating.NONE));
        var deployGraph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("deploy:agent"), new AgentSpec("main"), HumanGating.NONE));

        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(infraGraph)).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(deployGraph)).provides(Set.of(AGENT)).requires(Set.of(NS)).build());
        engine.validate();

        var tenantState = engine.initTenantState();
        var composed = engine.recompose("t1", tenantState);

        assertThat(composed.nodes()).hasSize(2);
        assertThat(composed.nodes()).containsKey(NodeId.of("infra:ns"));
        assertThat(composed.nodes()).containsKey(NodeId.of("deploy:agent"));
    }

    @Test void compose_addsCrossDomainEdges() {
        var infraGraph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("infra:ns"), new NsSpec("prod"), HumanGating.NONE));
        var deployGraph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("deploy:agent"), new AgentSpec("main"), HumanGating.NONE));

        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(infraGraph)).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(deployGraph)).provides(Set.of(AGENT)).requires(Set.of(NS)).build());
        engine.validate();

        var tenantState = engine.initTenantState();
        var composed = engine.recompose("t1", tenantState);

        assertThat(composed.dependenciesOf(NodeId.of("deploy:agent")))
            .contains(NodeId.of("infra:ns"));
    }

    @Test void compose_noRequires_noEdges() {
        var g1 = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("a:n1"), new NsSpec("x"), HumanGating.NONE));
        var g2 = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("b:n2"), new AgentSpec("y"), HumanGating.NONE));

        engine.registerDomain(DomainRegistration.builder(DomainId.of("a"),
                CompilationResult.single(g1)).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("b"),
                CompilationResult.single(g2)).provides(Set.of(AGENT)).build());
        engine.validate();

        var tenantState = engine.initTenantState();
        var composed = engine.recompose("t1", tenantState);

        assertThat(composed.dependencies()).isEmpty();
    }

    @Test void singleDomainPassthrough_noOverlay() {
        var graph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("n1"), new NsSpec("x"), HumanGating.NONE));
        engine.registerDomain(DomainRegistration.builder(DomainId.of("only"),
                CompilationResult.single(graph)).provides(Set.of(NS)).build());
        engine.validate();
        assertThat(engine.registrationCount()).isEqualTo(1);
        assertThat(engine.isActive()).isFalse();
    }

    @Test void multipleProviderNodes_allGetEdges() {
        var infraGraph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("infra:ns-prod"), new NsSpec("prod"), HumanGating.NONE))
            .withNode(new DesiredNode(NodeId.of("infra:ns-staging"), new NsSpec("staging"), HumanGating.NONE));
        var deployGraph = FACTORY.empty()
            .withNode(new DesiredNode(NodeId.of("deploy:agent"), new AgentSpec("main"), HumanGating.NONE));

        engine.registerDomain(DomainRegistration.builder(DomainId.of("infra"),
                CompilationResult.single(infraGraph)).provides(Set.of(NS)).build());
        engine.registerDomain(DomainRegistration.builder(DomainId.of("deploy"),
                CompilationResult.single(deployGraph)).provides(Set.of(AGENT)).requires(Set.of(NS)).build());
        engine.validate();

        var tenantState = engine.initTenantState();
        var composed = engine.recompose("t1", tenantState);

        assertThat(composed.dependenciesOf(NodeId.of("deploy:agent")))
            .containsExactlyInAnyOrder(NodeId.of("infra:ns-prod"), NodeId.of("infra:ns-staging"));
    }
}
