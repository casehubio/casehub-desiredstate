package io.casehub.desiredstate.annotations.runtime;

import java.util.List;
import java.util.stream.Collectors;

public class GraphRuleNonConvergenceException extends RuntimeException {
    private final List<String> activeRuleNames;
    private final int maxIterations;

    public GraphRuleNonConvergenceException(List<ResolvedGraphRule> activeRules, int maxIterations) {
        super("Graph rules did not converge after " + maxIterations + " iterations. "
              + "Rules still producing mutations: "
              + activeRules.stream().map(ResolvedGraphRule::name).collect(Collectors.joining(", "))
              + ". Non-converging rules are usually caused by non-idempotent mutations. "
              + "Check that parameterized rules use @NotExists guards and imperative rules "
              + "check graph state before producing mutations.");
        this.activeRuleNames = activeRules.stream().map(ResolvedGraphRule::name).toList();
        this.maxIterations = maxIterations;
    }

    public List<String> getActiveRuleNames() { return activeRuleNames; }
    public int getMaxIterations() { return maxIterations; }
}
