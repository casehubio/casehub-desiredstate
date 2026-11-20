# Decisions — #243 Stabilization Window After Recompilation

## D1: Placement — generic in runtime-core, not eidos-specific

**Choice:** Implement stabilization window in `SituationRecompilerDispatchCore` (runtime-core), per-tenant, in-memory, configurable via `DesiredStatePreferenceKeys`.
**Alternatives:**
- Eidos-specific wrapper — only societies get stabilization; more faithful to spec's SocietyLifecycleManager naming but misplaces responsibility
- SPI-based opt-in (StabilizationPolicy) — most flexible but over-engineered for a simple timer
**Rationale:** The cascade storm is a property of the reactive feedback loop (situation → recompile → reconcile → state change → new situation), which exists in any domain using SituationRecompiler. The damper belongs where the feedback loop closes — the dispatch core. The spec's SocietyLifecycleManager ownership was a design-time assumption that didn't survive the actual architecture. No persistence needed — on restart, the desired graph already reflects the last recompilation, so early-firing situations produce no-op transition plans.
**Trade-offs:** Eidos loses explicit ownership of its stabilization semantics. All domains get the same window behavior — no domain-specific customization of queue evaluation strategy.
**Sources:** Spec §5.4 (engine wksp), SituationRecompilerDispatchCore.java, SituationRecompilerDispatch.java, DesiredStateReplanDispatch.java
**Exploration:** quick
**Status:** captured

## D2: Queue evaluation strategy — drain all on window expiry

**Choice:** When the stabilization window expires, drain the queue and evaluate each queued situation against current state. Most will produce empty diffs (already handled by the recompilation that triggered the window).
**Alternatives:**
- Evaluate only the latest — simpler but risks dropping genuine situations that arrived early in the window
- Re-trigger single reconciliation without queuing — simplest but loses situation-specific context (correlation keys, which recompiler to invoke)
**Rationale:** Faithful to spec: "Situations that fire during the window are queued and evaluated after stabilization completes." Evaluating all preserves information. The cost is low — most will no-op via empty diffs.
**Trade-offs:** Queue can grow during the window, but bounded by the 5-minute default and typical situation rates. Memory overhead is negligible (a few ActiveSituation records per tenant).
**Sources:** Spec §5.4
**Exploration:** quick
**Status:** captured

## D3: Gate both entry points — dispatch core and engine-adapter replan

**Choice:** Both `SituationRecompilerDispatchCore` and `DesiredStateReplanDispatch` check the stabilization window. Move the gating logic into `SituationRecompilerEngine` (or a shared `StabilizationGuard`) so both callers share the same per-tenant window state.
**Alternatives:**
- Gate only dispatch core — engine-adapter's replan path considered pre-authorized (workflow-initiated). Simpler but leaves a bypass for cascade storms triggered via engine workflows.
**Rationale:** Cascade storms don't care which entry point triggered the recompilation. A workflow-initiated replan produces the same state changes and can trigger the same feedback loop. The window must be consistent across all paths.
**Trade-offs:** Engine-adapter's replan now has latency — a situation arriving via workflow during a cooldown is queued rather than immediately evaluated. This is the correct behavior but means workflow-initiated replans are no longer instant.
**Depends on:** D1 (placement in runtime-core)
**Sources:** DesiredStateReplanDispatch.java, SituationRecompilerDispatchCore.java
**Exploration:** quick
**Status:** captured
