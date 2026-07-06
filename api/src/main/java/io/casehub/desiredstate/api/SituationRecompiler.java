package io.casehub.desiredstate.api;

import io.casehub.ras.api.ActiveSituation;

import java.util.Optional;

/**
 * SPI for situation-driven graph recompilation.
 *
 * <p>Triggered by RAS (Runtime Anomaly Service) when a situation reaches a threshold
 * or pattern that requires desired-state recalculation — for example, persistent drift
 * in a specific zone triggering fallback provisioning, or cascading failures triggering
 * circuit-breaker topology changes.
 *
 * <p>Unlike {@link GoalCompiler}, which translates high-level goals into a graph,
 * SituationRecompiler reacts to runtime anomalies by producing a mutated or replacement
 * graph. The recompiler may:
 * <ul>
 *   <li>Return {@code Optional.empty()} — no replan needed, current graph still valid</li>
 *   <li>Return a new graph — replaces the current desired graph, triggering reconciliation</li>
 * </ul>
 *
 * <p>The runtime calls {@link ReconciliationLoop#updateDesired(String, DesiredStateGraph)}
 * with the new graph when present, canceling interval-grouped timers for removed node types
 * and scheduling new ones for added types.
 *
 * <p>Invoked by {@code DesiredStateReplanDispatch} (engine-adapter) when RAS triggers
 * a {@code desiredstate:replan} workflow step.
 *
 * @see io.casehub.ras.api.ActiveSituation
 * @see GoalCompiler
 * @see ReconciliationLoop#updateDesired(String, DesiredStateGraph)
 */
public interface SituationRecompiler {

    /**
     * Recompile the desired state graph in response to a situation.
     *
     * @param current   the current desired state graph
     * @param situation the active situation triggering replan
     * @param factory   graph factory for creating new graphs
     * @return {@code Optional.empty()} if no replan needed, or a new graph to replace current
     */
    Optional<CompilationResult> recompile(
        DesiredStateGraph current,
        ActiveSituation situation,
        DesiredStateGraphFactory factory);
}
