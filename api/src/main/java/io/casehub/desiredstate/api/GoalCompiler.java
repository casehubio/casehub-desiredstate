package io.casehub.desiredstate.api;

public interface GoalCompiler<G> {
    CompilationResult compile(G goals, DesiredStateGraphFactory factory);
}
