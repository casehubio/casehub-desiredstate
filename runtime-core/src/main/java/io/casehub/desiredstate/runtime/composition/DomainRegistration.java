package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.SituationRecompiler;

import java.util.List;
import java.util.Set;

public record DomainRegistration(
    DomainId domainId,
    CompilationResult compilationResult,
    Set<NodeType> provides,
    Set<NodeType> requires,
    CompletionCondition readinessCondition,
    List<SituationRecompiler> situationRecompilers
) {
    public DomainRegistration {
        java.util.Objects.requireNonNull(domainId);
        java.util.Objects.requireNonNull(compilationResult);
        java.util.Objects.requireNonNull(readinessCondition);
        provides = Set.copyOf(provides);
        requires = Set.copyOf(requires);
        situationRecompilers = situationRecompilers != null
            ? List.copyOf(situationRecompilers) : List.of();
    }

    public static Builder builder(DomainId domainId, CompilationResult result) {
        return new Builder(domainId, result);
    }

    public static class Builder {
        private final DomainId domainId;
        private final CompilationResult compilationResult;
        private Set<NodeType> provides = Set.of();
        private Set<NodeType> requires = Set.of();
        private CompletionCondition readinessCondition = CompletionCondition.allPresent();
        private List<SituationRecompiler> situationRecompilers = List.of();

        private Builder(DomainId domainId, CompilationResult compilationResult) {
            this.domainId = domainId;
            this.compilationResult = compilationResult;
        }

        public Builder provides(Set<NodeType> provides) {
            this.provides = provides; return this;
        }

        public Builder requires(Set<NodeType> requires) {
            this.requires = requires; return this;
        }

        public Builder readinessCondition(CompletionCondition condition) {
            this.readinessCondition = condition; return this;
        }

        public Builder situationRecompilers(List<SituationRecompiler> recompilers) {
            this.situationRecompilers = recompilers; return this;
        }

        public DomainRegistration build() {
            return new DomainRegistration(domainId, compilationResult,
                provides, requires, readinessCondition, situationRecompilers);
        }
    }
}
