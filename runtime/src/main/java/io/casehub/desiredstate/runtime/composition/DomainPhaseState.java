package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;

record DomainPhaseState(CompilationResult currentResult, int phaseIndex) {
    DesiredStateGraph currentGraph() {
        return switch (currentResult) {
            case CompilationResult.SingleGraph sg -> sg.graph();
            case CompilationResult.Lifecycle lc -> lc.phases().get(phaseIndex).graph();
        };
    }
    boolean hasLifecycle() { return currentResult instanceof CompilationResult.Lifecycle; }
    boolean isAtFinalPhase() {
        return !(currentResult instanceof CompilationResult.Lifecycle lc)
            || phaseIndex >= lc.phases().size() - 1;
    }
    DomainPhaseState withAdvancedPhase() { return new DomainPhaseState(currentResult, phaseIndex + 1); }
    DomainPhaseState withResult(CompilationResult r) { return new DomainPhaseState(r, 0); }
}
