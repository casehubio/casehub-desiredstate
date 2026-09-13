package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossDomainCompositionEngineValidationTest {

    static final DesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();
    static final NodeType NS = NodeType.of("k8s-namespace");
    static final NodeType AGENT = NodeType.of("agent");
    static final NodeType DB = NodeType.of("database");

    record Spec(String name) implements NodeSpec {
        @Override public NodeType nodeType() { return NS; }
    }

    CrossDomainCompositionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new CrossDomainCompositionEngine(FACTORY);
    }

    @Test void registerDomain_acceptsValidRegistration() {
        engine.registerDomain(reg("infra", Set.of(NS), Set.of()));
        assertThat(engine.registrationCount()).isEqualTo(1);
    }

    @Test void validate_duplicateProvides_failsFast() {
        engine.registerDomain(reg("infra", Set.of(NS), Set.of()));
        engine.registerDomain(reg("platform", Set.of(NS), Set.of()));
        assertThatThrownBy(() -> engine.validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("k8s-namespace")
            .hasMessageContaining("infra")
            .hasMessageContaining("platform");
    }

    @Test void validate_unsatisfiedRequires_failsFast() {
        engine.registerDomain(reg("deploy", Set.of(AGENT), Set.of(NS)));
        assertThatThrownBy(() -> engine.validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deploy")
            .hasMessageContaining("k8s-namespace");
    }

    @Test void validate_circularRequires_failsFast() {
        engine.registerDomain(reg("a", Set.of(NS), Set.of(AGENT)));
        engine.registerDomain(reg("b", Set.of(AGENT), Set.of(NS)));
        assertThatThrownBy(() -> engine.validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Circular");
    }

    @Test void validate_nodeIdConflict_failsFast() {
        var g1 = FACTORY.empty().withNode(new DesiredNode(
            NodeId.of("shared"), new Spec("v1"), HumanGating.NONE));
        var g2 = FACTORY.empty().withNode(new DesiredNode(
            NodeId.of("shared"), new Spec("v2"), HumanGating.NONE));
        engine.registerDomain(regWith("d1", g1, Set.of(NS), Set.of()));
        engine.registerDomain(regWith("d2", g2, Set.of(AGENT), Set.of()));
        assertThatThrownBy(() -> engine.validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shared")
            .hasMessageContaining("d1")
            .hasMessageContaining("d2");
    }

    @Test void validate_nodeIdSharing_identicalSpecs_allowed() {
        var spec = new Spec("shared-val");
        var g1 = FACTORY.empty().withNode(new DesiredNode(
            NodeId.of("shared"), spec, HumanGating.NONE));
        var g2 = FACTORY.empty().withNode(new DesiredNode(
            NodeId.of("shared"), spec, HumanGating.NONE));
        engine.registerDomain(regWith("d1", g1, Set.of(NS), Set.of()));
        engine.registerDomain(regWith("d2", g2, Set.of(AGENT), Set.of()));
        assertThatCode(() -> engine.validate()).doesNotThrowAnyException();
    }

    @Test void validate_topologicalOrder_respectsRequires() {
        engine.registerDomain(reg("deploy", Set.of(AGENT), Set.of(NS)));
        engine.registerDomain(reg("infra", Set.of(NS), Set.of()));
        engine.validate();
        assertThat(engine.topologicalOrder())
            .containsExactly(DomainId.of("infra"), DomainId.of("deploy"));
    }

    @Test void lateRegistration_rejected() {
        engine.registerDomain(reg("infra", Set.of(NS), Set.of()));
        engine.validate();
        engine.markComposed();
        assertThatThrownBy(() -> engine.registerDomain(reg("late", Set.of(DB), Set.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("composition already completed");
    }

    private DomainRegistration reg(String name, Set<NodeType> provides, Set<NodeType> requires) {
        return regWith(name, FACTORY.empty(), provides, requires);
    }

    private DomainRegistration regWith(String name, DesiredStateGraph graph,
            Set<NodeType> provides, Set<NodeType> requires) {
        return DomainRegistration.builder(DomainId.of(name), CompilationResult.single(graph))
            .provides(provides).requires(requires).build();
    }
}
