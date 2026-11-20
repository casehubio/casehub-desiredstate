# Stabilization Window After Recompilation — Design Spec

**Issue:** #243
**Epic:** #230 (Society lifecycle hardening)
**Spec reference:** §5.4 Adaptive Reorganisation (engine wksp)

## 1. Problem

The system has a reactive feedback loop: state change → RAS detects situation →
`SituationRecompilerDispatchCore` triggers recompilation → new graph applied →
reconciliation runs → state changes → RAS detects new situation → loop repeats.

Without damping, a single recompilation can cascade — the restructuring produces
transient states that look like anomalies to the situation detector, which
triggers more restructuring. This is the "cascade storm" problem.

## 2. Solution

Add a per-tenant stabilization window to `SituationRecompilerEngine`. After a
successful recompilation, the engine starts a cooldown timer for that tenant.
Situations arriving during the window are queued in memory. When the window
expires, all queued situations are drained and evaluated against current state.

### 2.1 Placement

The stabilization window lives in `SituationRecompilerEngine` (runtime-core).
This gates both entry points:

- `SituationRecompilerDispatchCore` — CDI/event-driven path
- `DesiredStateReplanDispatch` — engine-adapter workflow path

Both callers already go through `SituationRecompilerEngine.recompile()`, so the
gate is in the right place without requiring callers to change.

### 2.2 State

Per-tenant, in-memory:

```java
// Inside SituationRecompilerEngine
private final ConcurrentHashMap<String, StabilizationState> stabilization = new ConcurrentHashMap<>();

static final class StabilizationState {
    final Instant windowStart;
    final List<QueuedSituation> queue = new ArrayList<>();  // guarded by synchronized(this)
    ScheduledFuture<?> drainFuture;

    StabilizationState(Instant windowStart) { this.windowStart = windowStart; }
}

sealed interface QueuedSituation {
    String tenancyId();

    record Recompile(String tenancyId, ActiveSituation situation,
                     DesiredStateGraphFactory factory) implements QueuedSituation {}
    record Resolved(String tenancyId, String situationId,
                    DesiredStateGraphFactory factory) implements QueuedSituation {}
}
```

The queued situation records carry only the situation identity and factory — not
graph or actual state snapshots. The drain reads fresh state from
`ReconciliationLoop` and `ActualStateAdapterRouter` at evaluation time.

No persistence needed. On restart, the window is lost, which is safe: the
desired-state graph already reflects the last recompilation. A situation that
fires early after restart produces a diff against the already-recompiled graph,
yielding a no-op transition plan.

### 2.3 Configuration

New preference key in `DesiredStatePreferenceKeys`:

```java
public static final PreferenceKey<DurationPreference> STABILIZATION_WINDOW =
    new PreferenceKey<>("desiredstate", "stabilization-window",
        new DurationPreference(Duration.ofMinutes(5)),
        s -> new DurationPreference(Duration.parse(s)));
```

Default: 5 minutes. Set to `Duration.ZERO` to disable. Per-tenant override
via the existing `PreferenceProvider` mechanism.

### 2.4 Flow

**On `recompile()` call:**

1. Check if tenant has an active stabilization window
2. If window active → queue the situation, return `Optional.empty()`
3. If no window → delegate to recompiler chain as today
4. If recompiler returns a non-empty result → start the stabilization window
   for that tenant
5. Return the result to the caller (caller applies it as today)

**On window expiry:**

1. Remove the `StabilizationState` for the tenant
2. For each queued situation (in arrival order):
   a. Read current desired graph and actual state (fresh — not the stale
      snapshots from arrival time)
   b. Delegate to the recompiler chain
   c. If result is non-empty, apply it and restart the stabilization window
      (re-queue remaining situations)
3. If no queued situation produces a result, the tenant is fully stabilized

**On `situationResolved()` call:**

Same gating — if window active, queue the resolution event. Resolved situations
must also be evaluated after stabilization because a resolution during the
window may trigger its own recompilation (e.g., reverting to pre-situation graph).

### 2.5 Window Restart on Drain

If a queued situation produces a new recompilation during the drain phase, the
stabilization window restarts. Remaining queued situations are re-queued into
the new window. This prevents the drain itself from producing a cascade.

To prevent infinite loops, cap the restart count at 3 per drain cycle. After 3
restarts, log a warning and stop draining — the remaining situations stay queued
for the next window expiry.

### 2.6 Cold Start

`SituationRecompilerDispatchCore.handleColdStart()` processes active situations
from `SituationSource`. These bypass the stabilization window — cold start runs
once at startup and processes all active situations sequentially. No cascade risk
because the system hasn't started producing new situations yet.

### 2.7 Drain Callback

The engine gates and queues, but it cannot drain by itself — it doesn't have
access to `ReconciliationLoop` or `ActualStateAdapterRouter` for reading fresh
state. The drain callback is provided by the caller via a `DrainHandler`
functional interface:

```java
@FunctionalInterface
public interface DrainHandler {
    void drain(String tenancyId, List<QueuedSituation> queued);
}
```

`SituationRecompilerDispatchCore` implements this — it reads fresh
graph/actual state per situation and calls back into the engine's
`recompile()`/`situationResolved()` methods. The engine detects it's in a
drain cycle and processes inline rather than re-queuing.

### 2.8 Timer Mechanism

Constructor-inject a `ScheduledExecutorService` (same pattern as
`ReconciliationLoop`'s scheduler). The CDI wiring in `RuntimeBeans` provides it.

```java
public SituationRecompilerEngine(
    List<SituationRecompiler> recompilers,
    Duration stabilizationWindow,
    ScheduledExecutorService scheduler,
    DrainHandler drainHandler
) { ... }
```

Backward-compatible: retain the existing `(List<SituationRecompiler>)` constructor
with `Duration.ZERO` (disabled) and `null` scheduler/handler for tests that
don't need stabilization.

### 2.9 Thread Safety

- `ConcurrentHashMap` for per-tenant state
- Queue access synchronized on the `StabilizationState` instance
- The drain callback runs on the scheduler thread — must acquire the per-tenant
  lock before draining
- `recompile()` may be called concurrently from CDI async observers and engine
  workflow threads — the per-tenant lock serializes these

## 3. API Changes

### 3.1 SituationRecompilerEngine (runtime-core)

New constructor parameters: `Duration stabilizationWindow`,
`ScheduledExecutorService scheduler`, `DrainHandler drainHandler`.
New `recompile()` and `situationResolved()` behavior with gating.

New types:
```java
sealed interface QueuedSituation { ... }
@FunctionalInterface interface DrainHandler { ... }
```

New method:
```java
public void clearStabilization(String tenancyId)
```
For tests and lifecycle cleanup (tenant stop).

### 3.1a SituationRecompilerDispatchCore (runtime-core)

Implements `DrainHandler`. On drain: reads fresh graph/actual state per
situation, calls back into the engine.

### 3.2 DesiredStatePreferenceKeys (runtime-core)

New key: `STABILIZATION_WINDOW` (`DurationPreference`, default 5 minutes).

### 3.3 RuntimeBeans (runtime, CDI)

Update `situationRecompilerEngine()` producer to pass stabilization window
duration (from `PreferenceProvider`) and `ScheduledExecutorService`.

### 3.4 DesiredStateRuntimeAutoConfiguration (runtime-spring)

Same update for Spring auto-config.

## 4. Test Plan

1. **No recompilation → no window:** Situation that produces empty result does
   not start a stabilization window.
2. **Recompilation starts window:** Situation that produces a result starts the
   window. Subsequent situation for same tenant is queued.
3. **Different tenant unaffected:** Tenant B's situation is not blocked by
   tenant A's window.
4. **Drain on expiry:** Queued situations are evaluated with fresh state after
   window expires.
5. **Queue drain produces no-op:** Queued situations that duplicate the
   already-applied recompilation return empty results.
6. **Window restart on drain:** A queued situation that produces a new
   recompilation restarts the window and re-queues remaining situations.
7. **Restart cap:** After 3 restarts during a single drain, remaining
   situations stay queued.
8. **Cold start bypass:** `handleColdStart()` path is not gated.
9. **Disabled via Duration.ZERO:** Setting stabilization window to zero
   disables the feature entirely.
10. **Thread safety:** Concurrent recompile calls for the same tenant are
    serialized.

## 5. Files Changed

| File | Change |
|------|--------|
| `runtime-core/.../SituationRecompilerEngine.java` | Add stabilization state, gating logic, drain scheduling, `QueuedSituation`, `DrainHandler` |
| `runtime-core/.../SituationRecompilerDispatchCore.java` | Implement `DrainHandler` — drain reads fresh state, calls back into engine |
| `runtime-core/.../DesiredStatePreferenceKeys.java` | Add `STABILIZATION_WINDOW` key |
| `runtime/.../RuntimeBeans.java` | Wire stabilization window + scheduler + drain handler |
| `runtime-spring/.../DesiredStateRuntimeAutoConfiguration.java` | Wire stabilization window + scheduler + drain handler |
| `runtime/test/.../SituationRecompilerEngineTest.java` | New tests for stabilization behavior |
| `runtime/test/.../SituationRecompilerDispatchFullTest.java` | Update for new constructor |

## References

- Spec §5.4 Adaptive Reorganisation — engine wksp `specs/2026-11-02-self-organising-agent-society-design.md:476-499`
- `runtime-core/src/main/java/io/casehub/desiredstate/runtime/SituationRecompilerEngine.java` — current recompiler engine
- `runtime-core/src/main/java/io/casehub/desiredstate/runtime/SituationRecompilerDispatchCore.java` — CDI dispatch path
- `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatch.java` — engine workflow path
- `runtime-core/src/main/java/io/casehub/desiredstate/runtime/DesiredStatePreferenceKeys.java` — existing preference keys
- `runtime-core/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java` — scheduler pattern reference
