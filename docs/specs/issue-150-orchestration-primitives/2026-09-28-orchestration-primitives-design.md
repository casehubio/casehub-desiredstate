# Design: Orchestration Primitives Integration

**Issue:** casehubio/casehub-desiredstate#150
**Date:** 2026-09-28
**Status:** Draft

## Overview

Integrates yaml-core orchestration primitives into three areas of the desired-state runtime:

1. **Plugin provisioner migration** — Migrate plugin module from archived `yaml-step-core` / `StepPipelineExecutor` to `yaml-step-runtime` / `StructuralStepEvaluator`. Plugin steps gain retry, loop, parallel, barrier, quorum, select, and try-catch-finally constructs.

2. **Parallel transition executor** — New `ParallelTransitionExecutor` in `runtime-core` executes independent nodes concurrently within topological layers. Virtual threads with optional JDK `Semaphore` rate limiting per `NodeType`.

3. **Declarative node lifecycle state machines** — Plugin YAML declares valid lifecycle transitions per `NodeType`. `StatefulNodeProvisioner` decorator enforces transitions via `OrcStateMachine<NodeLifecycleState>`. State reconstructed from `ActualState` on startup.

### Primitives Coverage

Issue #150 lists all orchestration primitives available in `yaml-core 0.2-SNAPSHOT`. This spec uses a subset directly; the remainder are available to plugin YAML authors through the `StructuralStepEvaluator` migration (Application #1):

| Primitive | Used in this spec | Available via StructuralStepEvaluator |
|-----------|-------------------|---------------------------------------|
| `OrcStateMachine` | Application #3 (lifecycle) | — |
| `OrcLatch` | — (Application #2 uses JDK `CountDownLatch` directly) | `BarrierStep`, `QuorumStep` |
| `OrcSemaphore` | — (Application #2 uses JDK `Semaphore` directly) | — |
| `RetryDirective` | — | `retry:` decorator |
| `LoopDirective` | — | `loop:` decorator |
| `ComputeBlock` | — | `ParallelStep` (virtual thread pooling internal to evaluator) |
| `OrcSignal` / `OrcChannel` | — | `SelectStep` |
| `OrcCounter` / `OrcAccumulator` / `OrcMap` / `OrcFlag` / `EventRouter` | Not used | Available for future plugin YAML extensions |

Application #2 uses virtual threads + JDK `CountDownLatch` + JDK `Semaphore` directly rather than `ComputeBlock` or `OrcLatch`/`OrcSemaphore` because the executor operates at the graph layer level (inter-node parallelism), not within a single provisioner's step pipeline. The `OrcPrimitive.releaseForClose()` lifecycle hook adds no value here since both primitives are created and consumed within a single `execute()` call — no external scope management is needed. This avoids coupling `runtime-core` to `yaml-core` for `ParallelTransitionExecutor`. (Note: `runtime-core` does depend on `yaml-core` for Application #3's `OrcStateMachine` usage — but `ParallelTransitionExecutor` itself has no yaml-core dependency.)

## Application #1: Plugin Provisioner Migration

### Current State

`YamlPluginProvisioner` depends on `StepPipelineExecutor` from `casehub-platform-yaml-step-core` (archived module, no longer in the active platform build). Steps execute sequentially — no retry, parallelism, or structured control flow at the plugin provisioner level.

The newer `StructuralStepEvaluator` in `yaml-step-runtime` already integrates orchestration primitives:

| Capability | Step construct | Primitive used |
|-----------|---------------|----------------|
| Parallel execution | `ParallelStep` | Virtual threads |
| Synchronization | `BarrierStep` | `OrcLatch` |
| Majority vote | `QuorumStep` | `OrcLatch` + `QuorumTracker` |
| First-wins race | `SelectStep` | `OrcSignal` / `OrcChannel` |
| Retry | Decorator | `RetryDirective` |
| Loop/poll | Decorator | `LoopDirective` |
| Deadline | Decorator | `DeadlineContext` |
| Error handling | `TryCatchFinallyStep` | — |
| Conditional | `IfElseStep`, `MatchStep` | — |
| Result access | `StepResultStore` | `${result.stepName.field}` |

### Migration Path

Replace the `yaml-step-core` dependency with `yaml-step-runtime`. Migrate from `StepPipelineExecutor` API to `StructuralStepEvaluator` API.

### Changes

| Component | Change |
|-----------|--------|
| `plugin/runtime/pom.xml` | Replace `casehub-platform-yaml-step-core` with `casehub-platform-yaml-step-runtime` |
| `PluginParser` | Existing `PluginParser` in `plugin/model/` updated: parse step YAML into `ResolvedStep` tree instead of flat `StepDef` list. Also parses `lifecycle:` section into `NodeLifecycleDefinition` |
| `PluginDescriptor` | All three step fields change from `List<StepDef>` to `List<ResolvedStep>`: `actualStateSteps`, `provisionSteps`, `deprovisionSteps`. Gains `NodeLifecycleDefinition lifecycle` field |
| `YamlPluginProvisioner` | Use `StructuralStepEvaluator` instead of `StepPipelineExecutor` |
| `ActualStateStepExecutor` | Same migration — use `StructuralStepEvaluator` |
| `YamlPluginProcessor` (deployment) | Update build-time validation for `ResolvedStep` model |
| Plugin YAML surface | Steps gain `retry:`, `loop:`, `parallel:`, `barrier:`, `try:`, `if:`, `match:`, `select:` |

### ScenarioScope Lifecycle

Each plugin provisioner execution creates a `ScenarioScope` (via `PrimitiveFactory`) that lives for the duration of the provision/deprovision call. This provides lifecycle management for any orchestration primitives created during step execution — latches, channels, result stores.

`ScenarioScope` extends `AutoCloseable`. `YamlPluginProvisioner` manages it via try-with-resources to guarantee cleanup even when the provisioner throws an unchecked exception. Without this, `DefaultOrcSemaphore` instances with rate-window replenisher threads would leak `ScheduledExecutorService` threads on provisioner failure.

`ActualStateStepExecutor` also wraps its `StructuralStepEvaluator` calls in a `ScenarioScope` — actualState steps can use decorators (`retry:`, `loop:`, `deadline:`) that create orchestration primitives requiring cleanup.

`YamlPluginProvisioner` scope usage:
```java
try (ScenarioScope scope = primitiveFactory.createScope("provision:" + node.id())) {
    var evaluator = new StructuralStepEvaluator(conditionEvaluator, scope);
    // ... evaluate provision/deprovision steps ...
}
```

`ActualStateStepExecutor` scope usage:
```java
try (ScenarioScope scope = primitiveFactory.createScope("actualState:" + node.id())) {
    var evaluator = new StructuralStepEvaluator(conditionEvaluator, scope);
    // ... evaluate actual-state steps ...
}
```

Both use try-with-resources to guarantee `ScenarioScope.close()` fires on all exit paths — this is critical because `DefaultOrcSemaphore` instances with rate-window replenisher threads leak `ScheduledExecutorService` threads if the scope is not closed.

### Plugin YAML Surface After Migration

```yaml
provisioner:
  provision:
    steps:
      - rest-call:
          method: PUT
          url: "${spec.endpoint}/resources/${spec.name}"
          body: "${spec.config}"
          result: response
        retry:
          max: 3
          backoff: exponential
          delay: 1s
      - assert:
          condition: "${response.status == 200}"
          message: "Provisioning failed: ${response.status}"

  deprovision:
    steps:
      - parallel:
          - rest-call:
              method: DELETE
              url: "${spec.endpoint}/resources/${spec.name}"
          - rest-call:
              method: DELETE
              url: "${spec.endpoint}/metadata/${spec.name}"

actualState:
  steps:
    - rest-call:
        method: GET
        url: "${spec.endpoint}/resources/${spec.name}"
        result: response
      retry:
        max: 2
        delay: 500ms
    - if:
        condition: "${response.status == 200}"
        then:
          - compare-state:
              result: { nodeStatus: PRESENT }
        else:
          - compare-state:
              result: { nodeStatus: ABSENT }
```

### Backward Compatibility

Existing plugin YAML with flat step lists (no decorators or control flow) continues to work — `PluginParser` produces `ResolvedStep.PluginStep` or `ResolvedStep.InvokeStep` nodes for simple steps, which `StructuralStepEvaluator` evaluates identically to the old sequential execution.

### StructuralStepEvaluator Result Mapping

`StructuralStepEvaluator` returns `StepResult` (success or failure). `YamlPluginProvisioner` translates these to `ProvisionResult`/`DeprovisionResult`:

| StructuralStepEvaluator outcome | ProvisionResult |
|---|---|
| `StepResult.isSuccess()` | `ProvisionResult.Success()` |
| `StepResult.Failure` (any cause) | `ProvisionResult.Failed(message)` |

Structured step constructs produce the following failure messages:

| Construct failure | StepResult.Failure message |
|---|---|
| Parallel branch partial failure | First failed branch's message (fail-fast — `evaluateParallel` returns on first failure) |
| Barrier timeout | `"Barrier timed out after [duration]"` |
| Quorum unreachable | `"Quorum unreachable — N of M steps failed, K successes required"` |
| Select no winner | `"No select branch completed"` |
| Retry exhaustion | Last attempt's failure message (decorator unwraps after max retries) |

All structural failures map to `ProvisionResult.Failed` — there is no partial success. `PendingApproval` can only originate from the provisioner SPI, not from step evaluation.

## Application #2: ParallelTransitionExecutor

### Current State

`SimpleTransitionExecutor` iterates over `OrderedStep` lists sequentially for each phase (removals → suspensions → resumptions → additions). Independent nodes — nodes at the same topological depth with no cross-dependencies — wait unnecessarily.

### Design

A new `ParallelTransitionExecutor` in `runtime-core` executes nodes within topological layers concurrently.

### Layer Computation

`TransitionPlanner` produces layer-structured data during its existing topological sort. Kahn's algorithm already processes nodes in BFS levels — those levels ARE the concurrent layers. The planner currently discards layer boundaries by flattening into `List<NodeId>`. This design retains them.

`TransitionPlan` changes from `List<OrderedStep>` to `List<List<OrderedStep>>` per phase. Each inner list is a concurrent layer:

```java
public record TransitionPlan(
    List<List<OrderedStep>> removals,
    List<List<OrderedStep>> suspensions,
    List<List<OrderedStep>> resumptions,
    List<List<OrderedStep>> additions,
    DesiredStateGraph before, DesiredStateGraph after
) {
    // Backward-compatible constructor: wraps flat lists as single-layer
    public TransitionPlan(List<OrderedStep> removals, List<OrderedStep> additions,
                          DesiredStateGraph before, DesiredStateGraph after) {
        this(List.of(removals), List.of(), List.of(), List.of(additions), before, after);
    }

    // Flat-view accessors for callers that don't need layer structure
    public List<OrderedStep> flatRemovals()    { return removals.stream().flatMap(List::stream).toList(); }
    public List<OrderedStep> flatSuspensions() { return suspensions.stream().flatMap(List::stream).toList(); }
    public List<OrderedStep> flatResumptions() { return resumptions.stream().flatMap(List::stream).toList(); }
    public List<OrderedStep> flatAdditions()   { return additions.stream().flatMap(List::stream).toList(); }
}
```

**Migration for existing callers:** `CaseTransitionExecutor` iterates `plan.removals()` and `plan.additions()` expecting `List<OrderedStep>`. After this change, it uses `plan.flatRemovals()` and `plan.flatAdditions()` — a mechanical migration. The 4-arg backward-compatible constructor preserves existing construction sites (e.g., `CaseTransitionExecutor.buildRunnablePlan()`).

For additions and resumptions (roots before leaves — resume dependencies before dependents):
- **Layer 0:** nodes with no in-set dependencies
- **Layer 1:** nodes whose dependencies are all in Layer 0
- **Layer N:** nodes whose dependencies are all in layers 0..N-1

For removals and suspensions (leaves before roots — suspend dependents before dependencies): reverse layer ordering.

`SimpleTransitionExecutor` flattens the layers and iterates sequentially (identical current behaviour). `ParallelTransitionExecutor` executes each layer concurrently, then moves to the next.

The planner is the single authority on execution ordering. Having the executor re-derive layer structure from a flat list + graph would duplicate the in-degree/dependency analysis the planner already performed.

### Execution Model

```
for each phase in [removals, suspensions, resumptions, additions]:
  layers = plan.phaseLayers(phase)
  for each layer (sequentially):
    layerSpan = tracer.spanBuilder("layer").setAttribute("layer.index", i)
                      .setAttribute("layer.size", layer.size()).startSpan()
    parentContext = Context.current().with(layerSpan)
    latch = new CountDownLatch(layer.size())
    for each node in layer:
      submit to virtual thread:
        try (Scope ignored = parentContext.makeCurrent()):
          if semaphore configured for node.type():
            semaphore.acquire()
            try:
              outcome = nodeStepExecutor.execute(node, phase.action(), graph, tenancyId)
            finally:
              semaphore.release()
          else:
            outcome = nodeStepExecutor.execute(node, phase.action(), graph, tenancyId)
        finally:
          latch.countDown()
    if !latch.await(layerTimeout, SECONDS):
      mark unfinished nodes as Failed("layer timeout after " + layerTimeout + "s")
    layerSpan.end()
    collect outcomes
```

**OTel context propagation:** Virtual threads have their own thread-local storage. Without explicit propagation, per-node spans created by `NodeStepExecutor` would be root spans with no parent — fragmenting the execution trace. The pseudocode captures `Context.current()` (with the layer span) before spawning virtual threads and sets it as current in each thread via `parentContext.makeCurrent()`. Per-node spans created by `NodeStepExecutor` automatically become children of the layer span, preserving the trace hierarchy: execution → phase → layer → node.

`CountDownLatch` and `Semaphore` are JDK types (`java.util.concurrent`) — no yaml-core dependency for `ParallelTransitionExecutor`. The `OrcPrimitive.releaseForClose()` lifecycle hook is unnecessary here since both primitives are created and consumed within a single `execute()` call.

The `layerTimeout` is configurable via `desiredstate.transition.layer-timeout` (default: 5 minutes). On timeout, the executor collects partial outcomes, marks nodes whose virtual threads have not completed as `StepOutcome.Failed("layer timeout")`, and proceeds to the next layer. Failed nodes propagate to dependent layers via the failure handling mechanism.

The semaphore `acquire()` is inside the outer `try` block to guarantee `latch.countDown()` fires even if `acquire()` throws `InterruptedException` (virtual threads are interruptible by design). The semaphore `release()` is in an inner `try/finally` so it only fires when `acquire()` succeeded.

### NodeStepExecutor — Shared Per-Node Execution

`SimpleTransitionExecutor` currently implements four cross-cutting concerns inline for all four action types (provision, deprovision, suspend, resume):

1. **Human gating** — `node.requiresHuman(StepAction.X)` → delegate to `HumanNodeHandler`
2. **Approval lifecycle** — `PendingApprovalHandler.check()` → Pending/Rejected/Approved/None pattern matching, `recordPending()` on PendingApproval result
3. **Lifecycle hooks** — `LifecycleStepExecutor.execute()` for pre/post hooks from `node.hooks()`
4. **OTel tracing** — per-node span creation with `desiredstate.node.*` attributes

A new `NodeStepExecutor` in `runtime-core/` extracts this shared per-node execution logic. Both `SimpleTransitionExecutor` and `ParallelTransitionExecutor` delegate to it, differing only in their iteration strategy (sequential vs layer-parallel).

```java
public class NodeStepExecutor {
    private final NodeProvisionerRouter router;
    private final HumanNodeHandler humanNodeHandler;
    private final PendingApprovalHandler pendingApprovalHandler;
    private final LifecycleStepExecutor lifecycleStepExecutor;

    public StepOutcome execute(DesiredNode node, StepAction action, DesiredStateGraph graph, String tenancyId) {
        return switch (action) {
            case PROVISION -> executeProvision(node, graph, tenancyId);
            case DEPROVISION -> executeDeprovision(node, graph, tenancyId);
            case SUSPEND -> executeSuspend(node, graph, tenancyId);
            case RESUME -> executeResume(node, graph, tenancyId);
        };
    }

    StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph, String tenancyId) { ... }
    StepOutcome executeDeprovision(DesiredNode node, DesiredStateGraph graph, String tenancyId) { ... }
    StepOutcome executeSuspend(DesiredNode node, DesiredStateGraph graph, String tenancyId) { ... }
    StepOutcome executeResume(DesiredNode node, DesiredStateGraph graph, String tenancyId) { ... }
}
```

`SimpleTransitionExecutor` becomes a thin loop over `NodeStepExecutor`. `ParallelTransitionExecutor` submits `NodeStepExecutor` calls to virtual threads. `CaseTransitionExecutor` is NOT refactored to use `NodeStepExecutor` — its execution model delegates to the engine via `DesiredStateDispatch`, which has its own approval handling. However, it does require a mechanical migration to use `TransitionPlan`'s flat-view accessors (`flatRemovals()`, `flatAdditions()`) since the underlying fields change to `List<List<OrderedStep>>`.

This ensures new cross-cutting concerns (e.g., `StatefulNodeProvisioner` from Application #3, future audit logging) are applied consistently regardless of execution strategy.

### Semaphore Rate Limiting

A new default method on `NodeProvisioner`:

```java
default OptionalInt maxConcurrency() {
    return OptionalInt.empty();  // unbounded
}
```

`ParallelTransitionExecutor` creates a JDK `Semaphore` per `NodeType` that declares a concurrency limit. Before executing a node step, it acquires a permit; after completion, it releases. Unbounded types skip the semaphore entirely (no acquire/release overhead).

**Semaphore lifecycle:** per-execution. Each `execute(TransitionPlan, tenancyId)` call creates fresh `Semaphore` instances from the provisioners' `maxConcurrency()` values. Semaphores are not shared across reconciliation cycles or tenants. This design limits concurrent provisioner calls within a single layer execution — it does not enforce cross-cycle rate limiting (which is not needed since reconciliation cycles are already serialized per tenant).

This protects provisioners that call rate-limited external APIs (e.g., cloud provider APIs with request quotas).

### Failure Handling

If a node in layer N fails, nodes in layer N+1 that depend on it are skipped with `StepOutcome.Failed("dependency [nodeId] failed: [reason]")`. Independent nodes in N+1 still execute.

The executor tracks failed node IDs per execution. Before executing each node, it checks whether any of that node's dependencies are in the failed set.

**Approval lifecycle:** If a node returns `PendingApproval`, it is treated as a non-failure skip — `PendingApprovalHandler.recordPending()` returns `StepOutcome.Skipped`. The latch counts down normally. Dependent nodes in layer N+1 are **not** skipped for pending approvals — they execute independently. On the next reconciliation cycle, the planner re-evaluates and the approval lifecycle progresses.

**Thread safety:** `PendingApprovalHandler.check()` and `recordPending()` will be called concurrently for different nodes in the same layer. Implementations **must** be thread-safe. The existing `WorkItemPendingApprovalHandler` delegates to the work-item API (HTTP or JPA — externally synchronized). The `NoOpPendingApprovalHandler` is stateless. This thread-safety requirement will be documented in the `PendingApprovalHandler` SPI javadoc.

### Module Placement

- `ParallelTransitionExecutor` — `runtime-core/` (framework-neutral, constructor-injected)
- `CdiParallelTransitionExecutor` — `runtime/` (CDI bridge, injects `Instance<NodeProvisioner>` for max-concurrency discovery)
- `SpringParallelTransitionExecutor` — `runtime-spring/` (Spring auto-configuration)

### CDI Activation

`ParallelTransitionExecutor` is not a `@DefaultBean` — it's activated when explicitly configured. `SimpleTransitionExecutor` remains the default. Users opt in via a preference key:

```
desiredstate.transition.executor=parallel
```

`RuntimeBeans` checks this preference and produces either `SimpleTransitionExecutor` or `ParallelTransitionExecutor`. The `@Produces` method in `RuntimeBeans` is the single decision point — no `@Alternative` needed.

This is consistent with the platform's CDI displacement pattern (ARC42STORIES §4, `alternative-extension-patterns.md` Pattern B): the `@Produces @DefaultBean` method in `RuntimeBeans` is the default tier. `CaseTransitionExecutor` (`@ApplicationScoped` in engine-adapter) displaces the entire default tier by CDI type precedence. The preference key only takes effect when `CaseTransitionExecutor` is **not** on the classpath — when it is, the `@DefaultBean` producer method never fires and the preference is ignored.

**Precedence rules:**
1. `CaseTransitionExecutor` on classpath → always active (`@ApplicationScoped` displaces `@DefaultBean`)
2. `desiredstate.transition.executor=parallel` → `ParallelTransitionExecutor` (only when engine-adapter absent)
3. Default → `SimpleTransitionExecutor`

### Relationship to CaseTransitionExecutor

`CaseTransitionExecutor` (engine-adapter) orchestrates via casehub-engine-flow with Worker(Workflow) phases — a fundamentally different execution model. `ParallelTransitionExecutor` occupies the middle ground: more concurrent than `SimpleTransitionExecutor`, lighter than `CaseTransitionExecutor`. The three are mutually exclusive per deployment — exactly one `TransitionExecutor` is active.

## Application #3: Declarative Node Lifecycle State Machines

### Current State

Node lifecycle transitions are implicit. Each provisioner manages state internally. No validation that transitions are legal. No auditability of state changes. The `NodeStatus` enum (PRESENT/ABSENT/DRIFTED/UNKNOWN/SUSPENDED) provides the external view but not the transition history.

`NodeLifecycleState` uses `DRIFTED` (not `DEGRADED`) for failed provisioning: when provisioning fails, the node is either ABSENT (nothing created) or DRIFTED (partially created, diverged from spec). This is the correct semantic match — provisioning failure IS a form of drift from desired state. Operational health monitoring (crash-looping, failing health checks) is a separate concern outside the scope of provisioning lifecycle.

### Design

Plugin YAML declares a lifecycle state machine per `NodeType`. A `StatefulNodeProvisioner` decorator wraps existing provisioners, enforcing declared transitions via `OrcStateMachine<NodeLifecycleState>`.

### NodeLifecycleState Enum

New enum in `api/`:

```java
public enum NodeLifecycleState {
    ABSENT,
    PROVISIONING,
    PRESENT,
    DRIFTED,
    DEPROVISIONING,
    SUSPENDING,
    SUSPENDED,
    RESUMING
}
```

Transient states (PROVISIONING, DEPROVISIONING, SUSPENDING, RESUMING) exist only during active execution. They are never persisted or visible in `ActualState`.

Mapping to `NodeStatus`:

| NodeLifecycleState | NodeStatus |
|-------------------|------------|
| ABSENT | ABSENT |
| PROVISIONING | — (transient) |
| PRESENT | PRESENT |
| DRIFTED | DRIFTED |
| DEPROVISIONING | — (transient) |
| SUSPENDING | — (transient) |
| SUSPENDED | SUSPENDED |
| RESUMING | — (transient) |

### Plugin YAML Lifecycle Declaration

```yaml
lifecycle:
  transitions:
    - from: ABSENT, to: PROVISIONING
    - from: PROVISIONING, to: PRESENT
    - from: PROVISIONING, to: DRIFTED
    - from: PRESENT, to: DRIFTED
    - from: PRESENT, to: DEPROVISIONING
    - from: DRIFTED, to: DEPROVISIONING
    - from: DEPROVISIONING, to: ABSENT
    - from: PRESENT, to: SUSPENDING
    - from: SUSPENDING, to: SUSPENDED
    - from: SUSPENDED, to: RESUMING
    - from: RESUMING, to: PRESENT
  on-enter:
    DRIFTED:
      emit: "node.drifted"
    PRESENT:
      emit: "node.ready"
```

The `states` set is inferred from transitions — all states referenced in `from` or `to` are included. No separate declaration needed.

**No DRIFTED → PRESENT recovery path (by design):** The example lifecycle has no `DRIFTED → PRESENT` transition. Recovery from DRIFTED requires explicit deprovision/reprovision (`DRIFTED → DEPROVISIONING → ABSENT → PROVISIONING → PRESENT`). This is intentionally conservative — automatic recovery would mean the state machine transitions without any provisioner action, based solely on the actual state adapter reporting PRESENT. For plugins that want auto-recovery (e.g., transient cloud API failures that self-resolve), add `- from: DRIFTED, to: PRESENT` to the lifecycle declaration. The state machine will then allow a "recover" action to transition directly to PRESENT.

If the actual state adapter reports PRESENT while the lifecycle state machine is in DRIFTED, the reconciliation loop sees desired=PRESENT, actual=PRESENT — no diff, no plan. The state machine remains DRIFTED until: (a) a JVM restart reconstructs it from ActualState (resetting to PRESENT), or (b) the operator explicitly deprovisions and reprovisions. This divergence between state machine state and actual state is acceptable for the conservative case — the state machine is an audit log of lifecycle events, not a mirror of current status. Plugins that find this unacceptable should declare the DRIFTED → PRESENT transition.

Plugins that do not support suspend/resume omit the suspend/resume transitions. If the `TransitionPlanner` selects a suspend action for a node whose lifecycle state machine lacks a `PRESENT → SUSPENDING` transition, `StatefulNodeProvisioner` returns `SuspendResult.Failed("illegal lifecycle transition")` — the planner's fallback already handles this by demoting to deprovision (see `TransitionPlanner.decideAction()` fallback for non-stateful provisioners).

### NodeLifecycleDefinition

```java
public record NodeLifecycleDefinition(
    NodeType nodeType,
    Set<Transition> transitions,
    Map<NodeLifecycleState, List<TransitionAction>> onEnter,
    Map<NodeLifecycleState, List<TransitionAction>> onExit
) {
    public record Transition(NodeLifecycleState from, NodeLifecycleState to) {}
}
```

Placed in `api/`. Plugin YAML produces these via `PluginParser`. Non-plugin provisioners can provide them programmatically by producing `NodeLifecycleDefinition` CDI beans (Quarkus) or Spring `@Bean`s. Placement in `api/` follows the SPI pattern: domain projects depend on `api/` to implement SPIs and produce configuration types — requiring a `runtime-core/` dependency would break the module tier structure.

`TransitionAction` is a sealed interface (named to avoid confusion with the existing `LifecycleStep` sealed interface, which handles pre/post provisioning hooks like `Verify`, `Notify`, `Wait`):

```java
public sealed interface TransitionAction {
    record EmitEvent(String eventType) implements TransitionAction {}
}
```

Extensible — future actions (logging, metrics) add new variants.

`LifecycleStep` (existing) and `TransitionAction` (new) are distinct concerns:
- `LifecycleStep` — workflow hooks around provisioning operations (`HookDescriptor.provisionPre/Post`, `deprovisionPre/Post`). Executed by `LifecycleStepExecutor` within `SimpleTransitionExecutor`.
- `TransitionAction` — state machine events on lifecycle state transitions (`on-enter`, `on-exit`). Executed by `StatefulNodeProvisioner` when the `OrcStateMachine` enters/exits a state.

### Integration with Existing `supportsStatefulLifecycle()` SPI

`NodeProvisioner` already has a `supportsStatefulLifecycle()` default method (returns `false`). `TransitionPlanner.plan()` accepts a `Predicate<NodeType> supportsStateful` parameter and downgrades `SUSPEND → DEPROVISION` and `RESUME → PROVISION` when unsupported. `NodeProvisionerRouter` exposes `boolean supportsStatefulLifecycle(NodeType type)`.

`StatefulNodeProvisioner` overrides `supportsStatefulLifecycle()` to return `true` **only when the `NodeLifecycleDefinition` declares suspend/resume transitions** (i.e., the transition set includes at least one transition with `SUSPENDING` or `RESUMING` as either `from` or `to`). Lifecycle definitions that only declare provision/deprovision transitions return `false` — the planner demotes suspend/resume to deprovision/provision as before.

This derivation prevents a critical incompatibility: when `CaseTransitionExecutor` is the active executor (engine-adapter on classpath), it throws `UnsupportedOperationException` for suspend/resume plans. If `supportsStatefulLifecycle()` were unconditionally `true`, any lifecycle-enabled provisioner would cause runtime crashes in engine-adapter deployments.

**Startup validation:** `StatefulNodeProvisionerRouter` validates at construction time that no `NodeLifecycleDefinition` declares suspend/resume transitions when `CaseTransitionExecutor` is the active `TransitionExecutor`. This is checked by injecting `Instance<TransitionExecutor>` and testing for `CaseTransitionExecutor`. Violation fails startup with: `"NodeType [X] declares suspend/resume lifecycle transitions but CaseTransitionExecutor does not support suspend/resume. Remove suspend/resume transitions from the lifecycle definition or use SimpleTransitionExecutor/ParallelTransitionExecutor."`

`ReconciliationLoop` passes the router's `supportsStatefulLifecycle()` to the planner. When `StatefulNodeProvisionerRouter` wraps provisioners at construction time, the routing table contains `StatefulNodeProvisioner` instances for lifecycle-enabled types. The router's `supportsStatefulLifecycle(NodeType)` delegates to the provisioner in the routing table — returning `true` for wrapped provisioners, `false` for unwrapped.

### Interaction with Graph Phases (`LifecycleManager`)

The codebase has two "lifecycle" concepts at different granularities:

- **Graph phases** (`LifecycleManager`, `CompilationResult.Lifecycle`) — graph-level multi-phase transitions (build→defend, bronze→silver→gold). Introduced in Chapter 9. Operates on entire desired-state graphs.
- **Node lifecycle** (`NodeLifecycleState`, `StatefulNodeProvisioner`) — per-node provisioning state machines. Introduced by this spec. Operates on individual nodes within a graph.

These systems interact through the reconciliation cycle:

1. `LifecycleManager` advances to a new phase by CAS-swapping the desired graph
2. The next reconciliation cycle uses the new graph to plan transitions
3. Nodes removed between phases get deprovisioned — their state machines transition through `DEPROVISIONING → ABSENT`
4. New nodes get provisioned — new state machines start at `ABSENT → PROVISIONING`
5. Nodes present in both phases retain their state machines (state carries over)

**State machine cleanup:** When a node's lifecycle state transitions to `ABSENT` (after successful deprovisioning), its `OrcStateMachine` is removed from the `ConcurrentHashMap`. This prevents memory leaks from stale state machines for nodes that no longer exist in the desired graph.

**Transient states during phase transitions:** If a node is mid-provisioning (state `PROVISIONING`) when a phase transition fires, the in-flight operation completes normally. The state machine reflects the result (e.g., `PROVISIONING → PRESENT`). If the node is not in the new phase graph, the next reconciliation cycle deprovisiones it (`PRESENT → DEPROVISIONING → ABSENT`). No special handling is needed — the existing reconciliation cycle handles this naturally.

### StatefulNodeProvisioner

Decorator in `runtime-core/` wrapping `NodeProvisioner`:

```java
public class StatefulNodeProvisioner implements NodeProvisioner {
    private final NodeProvisioner delegate;
    private final NodeLifecycleDefinition lifecycle;
    private final ConcurrentHashMap<NodeId, OrcStateMachine<NodeLifecycleState>> machines;
    private final TransitionActionHandler actionHandler;
}
```

State machines are created directly via `DefaultOrcStateMachine.builder()` — NOT through `PrimitiveFactory`. Three reasons:

1. **Transitions must be configured.** `PrimitiveFactory.createStateMachine()` creates empty machines with no transitions (`DefaultOrcStateMachine.builder(name, stateType, initialState).build()`). `StatefulNodeProvisioner` needs machines configured with transitions from `NodeLifecycleDefinition`.
2. **No blocking wrapper needed.** `PrimitiveFactory` returns `BlockingOrcStateMachine` (adds `ReentrantLock` + `Condition` for `awaitState()`/`awaitTransition()`). `StatefulNodeProvisioner` uses synchronous check-then-act — `OrcStateMachine`'s `transition()` with CAS is sufficient.
3. **No scope management.** `ScenarioScope.close()` calls `releaseForClose()` on all registered primitives, killing scope-managed machines. `StatefulNodeProvisioner`'s machines are long-lived (persist in `ConcurrentHashMap` across reconciliation cycles) and must not be scope-managed.

`TransitionActionHandler` is a functional interface in `runtime-core/`:

```java
@FunctionalInterface
public interface TransitionActionHandler {
    void execute(TransitionAction action, NodeId nodeId, NodeLifecycleState state, String tenancyId);
}
```

When creating an `OrcStateMachine`, `StatefulNodeProvisioner` registers `onEnter`/`onExit` handlers from the `NodeLifecycleDefinition`. Each handler closure captures the node's `NodeId` and `tenancyId` and delegates to `TransitionActionHandler.execute()`. This decouples the state machine (runtime-core) from the event emission mechanism (CDI/Spring).

**Handler exception safety:** `StatefulNodeProvisioner` wraps all registered `onEnter`/`onExit` handler closures in try-catch-log guards. `DefaultOrcStateMachine.transition()` fires handlers AFTER the CAS succeeds — if a handler threw unchecked, the state would have already advanced but the provisioning operation would never execute, leaving the state machine stuck in a transient state. Fire-and-forget semantics prevent this: handler failures (e.g., `ReconciliationEventEmitter` serialization errors, event bus unavailability) are logged at WARNING and do not abort the provisioning operation. The handler's purpose (event emission, auditing) is secondary to the provisioning action — failing the provisioning because an audit event couldn't be emitted is the wrong trade-off.

The CDI implementation (`CdiTransitionActionHandler` in `runtime/`) injects `ReconciliationEventEmitter` and `Consumer<CloudEvent>` to emit lifecycle CloudEvents. The Spring implementation follows the same pattern.

**State machine cleanup:** When a node's lifecycle state transitions to `ABSENT` (after successful deprovisioning), `StatefulNodeProvisioner` removes the `OrcStateMachine` entry from the `ConcurrentHashMap`. Additionally, at the start of each `provision()` call, if the node ID has a state machine in a persistent state that doesn't match the expected pre-provisioning state, the entry is removed and recreated. This prevents stale entries from accumulating for ephemeral nodes.

### Relationship to TransitionPlanner

The `TransitionPlanner` and `StatefulNodeProvisioner` operate at different levels:

- **TransitionPlanner** decides WHAT action to take — it diffs desired vs actual and selects provision/deprovision/suspend/resume per node. It already has fallback logic: when `supportsStatefulLifecycle()` returns false, suspend demotes to deprovision and resume demotes to provision.
- **StatefulNodeProvisioner** validates WHETHER a specific transition is legal for this node type according to its declared state machine. It wraps the provisioner and checks transitions before delegating.

These layers are complementary, not competing. The planner selects the action; the decorator validates the transition is legal for this node's current lifecycle state.

### Provisioning Flow

1. Get or create state machine for this `NodeId`
2. Transition `ABSENT → PROVISIONING` (validates via declared transitions)
3. Delegate to `wrapped.provision(node, context)`
4. On `Success`: transition `PROVISIONING → PRESENT`
5. On `Failed`: try `PROVISIONING → DRIFTED`, then `PROVISIONING → ABSENT`. If both throw `IllegalTransitionException`, force-reset to `ABSENT` with a warning log and return `Failed("provisioning failed, state machine force-reset to ABSENT: [reason]")`
6. On `PendingApproval`: revert transient transition (`PROVISIONING → ABSENT` via CAS), return `PendingApproval` unchanged to the caller. The approval lifecycle is managed by `PendingApprovalHandler` in `NodeStepExecutor` — on the next reconciliation cycle, the handler checks approval state before re-entering the provisioner.
7. On `IllegalTransitionException` from step 2: return `Failed("illegal lifecycle transition: ...")`

Deprovision flow mirrors with DEPROVISIONING transient state. Suspend flow: `PRESENT → SUSPENDING → SUSPENDED`. Resume flow: `SUSPENDED → RESUMING → PRESENT`. All four flows handle `PendingApproval` identically: revert the transient transition and return the result unchanged.

**Transient state safety:** The force-reset in step 5 prevents the state machine from getting stuck in a transient state (`PROVISIONING`, `DEPROVISIONING`, `SUSPENDING`, `RESUMING`) when the lifecycle YAML omits exit transitions for failure cases. This is a runtime safety net — the authoritative fix is deployment-time validation (see below).

**Deployment-time validation:** `YamlPluginProcessor` (build-time) and `StatefulNodeProvisionerRouter` (construction-time) apply the same validation rules to `NodeLifecycleDefinition`. The shared validation rules are:

1. **Transient state exit:** Every transient state (PROVISIONING, DEPROVISIONING, SUSPENDING, RESUMING) referenced in the transition set must have at least one declared transition to a persistent state (ABSENT, PRESENT, DRIFTED, or SUSPENDED).
2. **Initial state reachable:** ABSENT must appear as a `from` state in at least one transition (otherwise the state machine can never leave its initial state).
3. **No orphan states:** Every state referenced in `onEnter`/`onExit` action maps must appear in the transition set.

For YAML plugins, `YamlPluginProcessor` applies these rules at build time. For non-plugin provisioners that produce `NodeLifecycleDefinition` programmatically, only `StatefulNodeProvisionerRouter`'s construction-time validation is available. Both validation points use the same `NodeLifecycleDefinition.validate()` method (added to the record) to ensure rule consistency. Deployments with invalid lifecycle definitions fail at startup with a clear error message, not at provisioning time with a stuck state machine.

**Delegated methods:** `StatefulNodeProvisioner` forwards all non-lifecycle `NodeProvisioner` methods to the delegate:
- `handledTypes()` → delegate (routing table correctness depends on this)
- `resyncInterval()` → delegate (interval-grouped scheduling depends on this)
- `maxConcurrency()` → delegate (rate limiting depends on this)
- `supportsStatefulLifecycle()` → returns `true` only when lifecycle definition includes suspend/resume transitions (override, not delegation — see §Integration above)

### State Reconstruction

On reconciliation loop startup (or when a new node appears in ActualState), derive `NodeLifecycleState` from `NodeStatus`:

| NodeStatus | NodeLifecycleState |
|-----------|-------------------|
| PRESENT | PRESENT |
| ABSENT | ABSENT |
| DRIFTED | DRIFTED |
| SUSPENDED | SUSPENDED |
| UNKNOWN | — (skip) |

`UNKNOWN` nodes are skipped during state machine reconstruction — no `OrcStateMachine` entry is created. UNKNOWN means the actual state adapter could not determine the node's status (transient error, API timeout, probe failure). Mapping it to any definitive lifecycle state would be a guess. When `StatefulNodeProvisioner` encounters a node with no state machine entry, it creates one via `computeIfAbsent` with initial state `ABSENT` — this is consistent with the `TransitionPlanner`'s existing treatment of UNKNOWN as "needs provisioning".

Create `OrcStateMachine` instances initialized to the derived state. No persistence needed — `ActualState` is the source of truth for reconstruction.

### Event Emission

`on-enter` / `on-exit` actions emit CloudEvents via `ReconciliationEventEmitter`. Event types follow the `io.casehub.desiredstate.lifecycle.*` namespace:

- `io.casehub.desiredstate.lifecycle.state-entered`
- `io.casehub.desiredstate.lifecycle.state-exited`

New event type constants are added to `DesiredStateEventTypes`:

```java
public static final String LIFECYCLE_STATE_ENTERED =
    "io.casehub.desiredstate.lifecycle.state-entered";
public static final String LIFECYCLE_STATE_EXITED =
    "io.casehub.desiredstate.lifecycle.state-exited";
```

Payload data records:

```java
public record LifecycleStateEnteredData(
    String tenancyId, String nodeId, String nodeType,
    String state, String previousState, String customEventType
) {}

public record LifecycleStateExitedData(
    String tenancyId, String nodeId, String nodeType,
    String state, String nextState, String customEventType
) {}
```

The CloudEvent `type` field is always the fixed `LIFECYCLE_STATE_ENTERED` or `LIFECYCLE_STATE_EXITED` constant. The YAML `emit:` string (e.g., `"node.drifted"`) populates `customEventType` in the payload — it does NOT become the CloudEvent type. Consumers subscribe to stable event types and filter by payload, consistent with how `ReconciliationEventEmitter` handles `NODE_FAULTED`, `NODE_DRIFTED`, `NODE_RECOVERED` (all with typed `DesiredStateEventTypes` constants and structured data payloads).

`ReconciliationEventEmitter` gains two new methods: `lifecycleStateEntered(LifecycleStateEnteredData)` and `lifecycleStateExited(LifecycleStateExitedData)`. These follow the same pure-function pattern as existing methods — they build `CloudEvent` instances with the event type, subject (nodeId), and tenancyId extension.

**Relationship to `NODE_SUSPENDED` / `NODE_RESUMED` constants:** The `DesiredStateEventTypes` constants `NODE_SUSPENDED` and `NODE_RESUMED` exist but are not currently emitted — `ReconciliationEventEmitter` has no suspend/resume methods. The lifecycle event system (`LIFECYCLE_STATE_ENTERED` / `LIFECYCLE_STATE_EXITED`) supersedes these constants for lifecycle-enabled provisioners. The existing constants are retained for backward compatibility but are NOT emitted by this spec's implementation. Consumers should subscribe to `LIFECYCLE_STATE_ENTERED` and filter by `state` field (e.g., `state="SUSPENDED"`) for suspend/resume observability. RAS adapter updates for consuming lifecycle events are deferred — the existing fault/drift/recovery event consumption is unaffected.

This makes lifecycle transitions observable without code changes in consumers.

### CDI Wiring

`StatefulNodeProvisionerRouter` in `runtime/` extends `DefaultNodeProvisionerRouter` directly and is `@ApplicationScoped`. `CdiNodeProvisionerRouter` becomes `@DefaultBean` (currently `@ApplicationScoped`). When `StatefulNodeProvisionerRouter` is on the classpath, it displaces `CdiNodeProvisionerRouter` via CDI type precedence — the standard platform displacement pattern.

Architecture: `StatefulNodeProvisionerRouter` injects `Instance<NodeProvisioner>` and `Instance<NodeLifecycleDefinition>`. At construction time, it wraps each provisioner whose `NodeType` has a matching `NodeLifecycleDefinition` with a `StatefulNodeProvisioner` decorator. The wrapped collection (mixed: decorated + plain) is passed to `DefaultNodeProvisionerRouter`'s constructor. The routing table is transparent — callers see `NodeProvisionerRouter` methods and the state machine validation is internal to the wrapped provisioners.

Provisioners without lifecycle definitions pass through unwrapped — identical behavior to `CdiNodeProvisionerRouter`. Non-plugin provisioners that want lifecycle enforcement can produce `NodeLifecycleDefinition` beans programmatically.

## Testing Strategy

### Application #1: Plugin Migration

- Unit tests for `PluginParser` producing `ResolvedStep` trees from YAML with decorators
- Integration tests verifying retry, parallel, barrier, select constructs in plugin provisioning
- Backward compatibility tests — existing flat step lists produce identical outcomes
- Update `PluginIntegrationTest` and `YamlPluginProvisionerTest`

### Application #2: ParallelTransitionExecutor

- Unit tests for layer computation from ordered steps + graph dependencies
- Parallel execution tests — verify independent nodes run concurrently (timing assertions)
- Failure propagation tests — failed dependency skips dependent nodes
- Semaphore rate-limiting tests — verify bounded concurrency via JDK `Semaphore`
- Comparison tests — same graph, same inputs → same outcomes as SimpleTransitionExecutor (different ordering allowed, same final state)

### Application #3: Lifecycle State Machines

- Unit tests for `StatefulNodeProvisioner` — valid transitions succeed, invalid throw
- State reconstruction tests — derive lifecycle state from ActualState
- Plugin YAML parsing tests — lifecycle declaration → `NodeLifecycleDefinition`
- Event emission tests — on-enter/on-exit actions fire CloudEvents
- Integration tests — full provisioning cycle with state machine enforcement

## Module Impact

| Module | Changes |
|--------|---------|
| `api/` | `NodeProvisioner.maxConcurrency()` default method, `TransitionAction` sealed interface, `NodeLifecycleState` enum, `NodeLifecycleDefinition` record, `DesiredStateEventTypes` lifecycle constants, `PendingApprovalHandler` javadoc (thread-safety contract), `TransitionPlan` fields change to `List<List<OrderedStep>>` + flat-view accessors + backward-compatible constructor, `LifecycleStateEnteredData` / `LifecycleStateExitedData` payload records |
| `runtime-core/` | `NodeStepExecutor` (extracted from `SimpleTransitionExecutor`), `ParallelTransitionExecutor`, `StatefulNodeProvisioner`, `ReconciliationEventEmitter` lifecycle methods, `TransitionPlanner` layer-structured output, `SimpleTransitionExecutor` refactored to delegate to `NodeStepExecutor`, `ReconciliationLoop` OTel span attributes migrated from `plan.additions().size()` to `plan.flatAdditions().size()` (and same for removals, suspensions, resumptions) — without this, span attributes report layer count, not node count |
| `runtime/` | CDI bridges: `RuntimeBeans` conditional executor production, `StatefulNodeProvisionerRouter`, `CdiNodeProvisionerRouter` → `@DefaultBean` |
| `engine-adapter/` | `CaseTransitionExecutor`: mechanical migration — `plan.removals()` → `plan.flatRemovals()`, `plan.additions()` → `plan.flatAdditions()`, 4-arg constructor call unchanged (backward-compatible) |
| `runtime-spring/` | Spring auto-config for parallel executor |
| `plugin/runtime/` | Migrate to yaml-step-runtime, `PluginParser` updated for `ResolvedStep` + `lifecycle:` parsing, `PluginDescriptor` fields updated |
| `plugin/deployment/` | Update validation for ResolvedStep model, lifecycle validation |
| `plugin/spring/` | Spring auto-config for lifecycle definitions |
| `plugin/api/` | Possible — if StepDef import changes |
| `testing/` | Mock stateful provisioners, lifecycle test fixtures |

**Note:** ARC42STORIES §5 module structure table lists `runtime/` as L2 but does not show `runtime-core/` as a separate module. This is a pre-existing gap — `runtime-core/` already contains `SimpleTransitionExecutor`, `TransitionPlanner`, `ReconciliationEventEmitter`, `DefaultNodeProvisionerRouter`. ARC42STORIES should be updated to reflect the actual module split as part of this work.

## Dependencies

- `casehub-platform-yaml-step-runtime` (0.2-SNAPSHOT) — replaces `yaml-step-core`
- `casehub-platform-yaml-core` (0.2-SNAPSHOT) — already a transitive dependency via yaml-step-runtime, provides orchestration primitives

## References

- `plugin/runtime/src/main/java/io/casehub/desiredstate/plugin/runtime/YamlPluginProvisioner.java` — current plugin provisioner
- `platform/yaml-step-runtime/src/main/java/io/casehub/yaml/step/eval/StructuralStepEvaluator.java` — target step evaluator
- `runtime-core/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java` — current sequential executor
- `runtime-core/src/main/java/io/casehub/desiredstate/runtime/TransitionPlanner.java` — topological sort for layer computation
- `platform/yaml-core/src/main/java/io/casehub/yaml/core/orchestration/` — orchestration primitives library
- GitHub issue #150
