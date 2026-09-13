package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainRegistrationTest {
    static final DesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    @Test void builder_defaults() {
        var reg = DomainRegistration.builder(
                DomainId.of("infra"), CompilationResult.single(FACTORY.empty()))
            .build();
        assertThat(reg.domainId()).isEqualTo(DomainId.of("infra"));
        assertThat(reg.provides()).isEmpty();
        assertThat(reg.requires()).isEmpty();
        assertThat(reg.readinessCondition()).isNotNull();
        assertThat(reg.situationRecompilers()).isEmpty();
    }

    @Test void builder_withProvidesRequires() {
        var ns = NodeType.of("k8s-namespace");
        var agent = NodeType.of("agent");
        var reg = DomainRegistration.builder(
                DomainId.of("deploy"), CompilationResult.single(FACTORY.empty()))
            .provides(Set.of(agent))
            .requires(Set.of(ns))
            .build();
        assertThat(reg.provides()).containsExactly(agent);
        assertThat(reg.requires()).containsExactly(ns);
    }

    @Test void rejectsNullDomainId() {
        assertThatThrownBy(() -> DomainRegistration.builder(
                null, CompilationResult.single(FACTORY.empty())).build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test void rejectsNullCompilationResult() {
        assertThatThrownBy(() -> DomainRegistration.builder(
                DomainId.of("x"), null).build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test void provides_isImmutableCopy() {
        var mutable = new HashSet<>(Set.of(NodeType.of("a")));
        var reg = DomainRegistration.builder(
                DomainId.of("x"), CompilationResult.single(FACTORY.empty()))
            .provides(mutable).build();
        mutable.add(NodeType.of("b"));
        assertThat(reg.provides()).hasSize(1);
    }
}
