# casehub-desiredstate -- Contributor Guide

> Internals, architecture, and extension points for platform builders working on the desired-state runtime itself.

**GitHub:** [casehubio/casehub-desiredstate](https://github.com/casehubio/casehub-desiredstate)

---

## Full Module Structure

| Module | artifactId | Root package | Contents |
|--------|-----------|-------------|----------|
| `api/` | `casehub-desiredstate-api` | `io.casehub.desiredstate.api` | Core SPIs and domain types. Pure Java + Mutiny `provided`. No CDI, no framework. Contains: `DesiredStateGraph`, `DesiredNode`, `GoalCompiler`, `NodeProvisioner`, `ActualStateAdapter`, `FaultPolicy`, `EventSource`, `TransitionExecutor`, `HumanNodeHandler`, `PendingApprovalHandler`, `SituationRecompiler`, `GlobalReconciliationListener`, `ReconciliationListener`, `FaultCountStore`, `ConfigurationRetriever`, `ConfigurationAdapter`, `CompletionCondition`, `ThresholdFaultPolicy`, `InMemoryFaultCountStore`, sealed types (`GraphMutation`, `CompilationResult`, `StepOutcome`, `ProvisionResult`, `DeprovisionResult`, `ApprovalCheckResult`), `GraphMutations` utility, value types (`NodeId`, `NodeType`, `Dependency`, `HumanGating`, `StepAction`, `FaultType`, `NodeStatus`), CloudEvent data records. |
| `runtime/` | `casehub-desiredstate` | `io.casehub.desiredstate.runtime` | CDI runtime: `ImmutableDesiredStateGraph`, `DefaultDesiredStateGraphFactory`, `TransitionPlanner`, `ReconciliationLoop` (with `Builder`), `SimpleTransitionExecutor`, `FaultPolicyEngine`, `LifecycleManager`, `DefaultNodeProvisionerRouter`, `CdiNodeProvisionerRouter`, `DefaultActualStateAdapterRouter`, `CdiActualStateAdapterRouter`, `DefaultMergedEventSource`, `CdiMergedEventSource`, `DefaultFaultCountStore`, `FaultCountEvictionListener`, `ReconciliationEventEmitter`, `SituationRecompilerEngine`, `CbrFaultPolicy`, `CbrSituationRecompiler`, `CbrProposalTracker`, `GraphDiff`, `DesiredStatePreferenceKeys`. NoOp defaults: `NoOpHumanNodeHandler`, `NoOpPendingApprovalHandler`, `NoOpConfigurationRetriever`, `NoOpConfigurationAdapter`. `@ApplicationScoped` beans. OpenTelemetry instrumented. |
| `testing/` | `casehub-desiredstate-testing` | `io.casehub.desiredstate.testing` | `MockNodeProvisioner`, `MockActualStateAdapter`, `MockPendingApprovalHandler`, `MockTransitionExecutor`, `MockConfigurationRetriever`, `MockConfigurationAdapter`, `CannedEventSource`, `TestTimeouts`. Test scope only. |
| `engine-adapter/` | `casehub-desiredstate-engine` | `io.casehub.desiredstate.engine` | `CaseTransitionExecutor` displaces `SimpleTransitionExecutor`. `TransitionWorkflowGenerator` generates Serverless Workflow 1.0 definitions. `DesiredStateDispatch` registers `desiredstate:dispatch` via `CallableDispatchRegistry` and handles full PendingApproval lifecycle within workflow steps. `DesiredStateReplanDispatch` registers `desiredstate:replan` for RAS-triggered situation response via `SituationRecompilerEngine`. `DesiredStateExecutionRegistry` tracks per-execution graph/tenancy context and active case IDs. `DesiredStateExecutionContext` record. CTE pre-filters approval-gated nodes before case creation. |
| `work-adapter/` | `casehub-desiredstate-work` | `io.casehub.desiredstate.work` | `WorkItemPendingApprovalHandler` -- WorkItem-backed approval lifecycle via `WorkItemCreator` SPI. Classpath-activated, displaces `NoOpPendingApprovalHandler`. |
| `ras-adapter/` | `casehub-desiredstate-ras` | `io.casehub.desiredstate.ras` | `NodeFaultGanglion` (NODE_FAULTED -> detected, NODE_RECOVERED -> anti), `PersistentDriftGanglion` (persistent drift detection), `DesiredStateSituationDefinitionProvider` (3 situations: repeated-failure via Streak(3), persistent-drift via Count(3), zone-degradation via Rate(60%, 10)), `DesiredStateCorrelationKeyExtractor` (extracts `parentNodeId` for zone-level aggregation). |
| `persistence-jpa/` | `casehub-desiredstate-persistence-jpa` | `io.casehub.desiredstate.persistence.jpa` | `JpaFaultCountStore` -- JPA-backed `FaultCountStore` with Flyway migration at `db/desiredstate/migration/`. `FaultCountEntity` with composite key `(namespace, tenancy_id, node_id)`. Portable SQL (H2 MODE=PostgreSQL + PostgreSQL). Tier 2 in CDI priority ladder -- yields to application-provided stores. |
| `examples/dungeon/` | `casehub-desiredstate-example-dungeon` | `io.casehub.desiredstate.example.dungeon` | Dungeon domain: rooms, creatures, traps as nodes; `GoblinProvisioner`, `HeroRaidFaultPolicy`, `DungeonGoalCompiler`, `DungeonVisualizer`. |
| `examples/pipeline/` | `casehub-desiredstate-example-pipeline` | `io.casehub.desiredstate.example.pipeline` | Data pipeline: medallion-layered (Bronze/Silver/Gold) with schema validation, three-tier fault escalation, pluggable `ExecutionBackend` strategy. Demonstrates engine-adapter integration via `PipelineCaseTransitionTest`. |
| `examples/spatial/` | `casehub-desiredstate-example-spatial` | `io.casehub.desiredstate.example.spatial` | Battlefield spatial/vector POC: `TerrainGrid`, `FogOfWar`, `BattlefieldWorld`, multiple goal compilers, `ZoneRebalanceFaultPolicy`, `GridRenderer`. Graph sufficiency research for spatial domains. |
| `examples/expansion/` | `casehub-desiredstate-example-expansion` | `io.casehub.desiredstate.example.expansion` | Build-then-defend lifecycle: primary test vehicle for `CompilationResult.Lifecycle` phase transitions. `ExpansionGoalCompiler` produces lifecycle with "construct" and "defend" phases. `ThreatResponseSituationRecompiler` escalates defense posture on situation. |

---

## Internal Architecture

### ImmutableDesiredStateGraph

Default implementation of `DesiredStateGraph` SPI. Dual adjacency maps (forward: node -> deps, reverse: node -> dependents) using `Map.copyOf()`. Cycle detection via DFS on every edge addition. Dangling-dependency check on edge addition (both endpoints must exist). Version counter incremented on every mutation for CAS-based concurrent updates.

Created by `DefaultDesiredStateGraphFactory` (`@ApplicationScoped`).

### TransitionPlanner

Compares desired graph to actual state. Produces a `TransitionPlan` with topologically ordered additions (roots before leaves, Kahn's algorithm) and removals (orphaned nodes, leaves before roots). Plans are deterministic given the same inputs. Ordering rule: pruning before growing -- removals execute first, then additions.

Exhaustive `NodeStatus` handling: `PRESENT` nodes are skipped (already correct), `ABSENT` nodes are additions, `DRIFTED` nodes are skipped by the planner (present but degraded -- fault policies handle them), `UNKNOWN` nodes are treated as additions (provision to resolve ambiguity).

### ReconciliationLoop

Per-tenant event-driven reconciliation engine (`@ApplicationScoped`). Two trigger paths:
- **Event-driven** -- subscribes to `MergedEventSource.stream()` with debouncing (default 1s window).
- **Periodic re-sync** -- interval-grouped timers from `NodeProvisionerRouter.resyncIntervalFor(NodeType)`. Types sharing the same effective interval share a timer.

Each cycle: read actual state -> detect drift (create `NODE_DEGRADED` fault events for `DRIFTED` nodes) -> evaluate drift faults -> plan transitions -> execute -> evaluate execution faults -> CAS update desired graph with mutations -> emit CloudEvents -> match CBR outcomes -> fire `GlobalReconciliationListener` callbacks.

The loop never dies on exception -- a dead loop is worse than a failed cycle.

**Key methods:**
- `start(tenancyId, desired)` / `start(tenancyId, desired, listener)` -- fires immediate initial reconciliation
- `stop(tenancyId)` -- tears down tenant loop, fires `GlobalReconciliationListener.onTenantStopped`
- `updateDesired(tenancyId, newDesired)` -- atomic swap; recomputes interval groups if types changed
- `compareAndSetDesired(tenancyId, expected, newDesired)` -- CAS update for concurrent safety
- `getDesired(tenancyId)` -- read current desired graph
- `requestReconciliation(tenancyId)` -- out-of-band debounced trigger
- `setListener(tenancyId, listener)` -- set/replace per-tenant `ReconciliationListener`

**Builder pattern** (`ReconciliationLoop.Builder`) -- for test construction without CDI. Required params: planner, executor, actualStateAdapterRouter, faultPolicyEngine, mergedEventSource. Optional: router, debounceWindow, resyncInterval, cloudEventSink, cbrTracker, globalListeners.

### FaultPolicyEngine

Discovers all `FaultPolicy` beans via CDI `Instance<FaultPolicy>`. On fault event, runs all matching policies, merges mutations, detects conflicts (`ConflictingMutationException` when two policies produce contradictory mutations for the same node).

### SimpleTransitionExecutor

`@DefaultBean` -- sequential executor. Walks removals (leaves-first) then additions (roots-first). Per-step precedence: `requiresHuman(action)` -> `HumanNodeHandler`, else check `PendingApprovalHandler.check()` -> if `Pending`/`Rejected` handle accordingly, if `Approved` add `PlanApproval` to context, if `None` proceed to provisioner. After provisioner returns `PendingApproval`, calls `PendingApprovalHandler.recordPending()`.

### OpenTelemetry Tracing

Comprehensive span tree per cycle using `GlobalOpenTelemetry.getTracer("io.casehub.desiredstate")`:
- `reconcile` -- full-graph or type-filtered with `desiredstate.reconcile.types`
- `readActual` -- with `desiredstate.node.count`
- `detectDrift` -- with `desiredstate.drift.count` (conditional -- only when drift found)
- `plan` -- with `desiredstate.additions`, `desiredstate.removals`
- `execute`
- `faultFeedback` -- with `desiredstate.fault.count`, `desiredstate.mutation.count` (conditional -- only when faults exist)

Library instrumentation pattern: tracer obtained via `GlobalOpenTelemetry`, not CDI injection. Without an OTel SDK on classpath, tracing is no-op -- zero overhead. Errors set `StatusCode.ERROR` and call `recordException()`.

### Multi-Provisioner Dispatch

`NodeProvisionerRouter` (API interface) / `DefaultNodeProvisionerRouter` (runtime impl): each `NodeProvisioner` declares `handledTypes()` and `resyncInterval()`. The router builds a `Map<NodeType, NodeProvisioner>` lookup table, enforcing no `NodeType` is claimed by two provisioners (fail-fast `IllegalArgumentException` at construction). Missing provisioner for a node type returns `Failed("No provisioner for node type: X")`. Validates resync intervals >= 1 second.

`CdiNodeProvisionerRouter` -- CDI subclass injecting `Instance<NodeProvisioner>` and `PreferenceProvider` for interval overrides.

### Multi-Adapter Dispatch

`ActualStateAdapterRouter` (API interface) / `DefaultActualStateAdapterRouter` (runtime impl): routes `readActual()` by `NodeType` using adapter `handledTypes()` declarations. Always calls all adapters (orphan detection for deprovision). Merges results.

`CdiActualStateAdapterRouter` -- CDI subclass injecting `Instance<ActualStateAdapter>`.

### Multi-Source Event Composition

`MergedEventSource` (API interface) / `DefaultMergedEventSource` (runtime impl): composes multiple domain `EventSource` streams via `Multi.merge()` with per-stream error isolation (retry + recover). Single-source optimisation bypasses retry wrapping.

`CdiMergedEventSource` -- CDI subclass injecting `Instance<EventSource>`.

### Per-Type Reconciliation Scheduling

Types are grouped by their effective resync interval (provisioner default or Preferences override). Each distinct interval gets one `ScheduledFuture` reconciling all types at that frequency. `reconcileTypes(Set<NodeType>)` filters the desired graph via `filterByTypes()` and reconciles only matching types. Different node types can have different reconciliation frequencies. Scheduler pool size scales with the number of distinct interval groups (capped at available processors).

### LifecycleManager

`@ApplicationScoped` -- orchestrates multi-phase `CompilationResult.Lifecycle` deployments. Internal `TenantLifecycle` record tracks `(List<Phase>, phaseIndex)`. On `onCycleCompleted()`: checks if current phase's `completionCondition.isComplete()` against actual state, computes next phase index, and advances via **dual CAS**: `lifecycles.replace(tenancyId, current, next)` on the `ConcurrentHashMap` entry + `loop.compareAndSetDesired(tenancyId, desired, nextPhase.graph())` on the `AtomicReference`. If either CAS fails (concurrent update), rolls back. `casRetryMutations()` also uses a CAS retry loop for fault-policy mutations.

**Key methods:** `start(tenancyId, CompilationResult)`, `stop(tenancyId)`, `updateDesired(tenancyId, CompilationResult)`.

### SituationRecompiler Chain

`SituationRecompilerEngine` (`@ApplicationScoped`) -- chain-of-responsibility aggregation of `SituationRecompiler` beans ordered by `priority()` (ascending). The ras-adapter module provides ganglia as situation detectors; `CbrSituationRecompiler` provides CBR-based recompilation at `priority() = Integer.MAX_VALUE` (fallback position). Domain-specific recompilers run first.

### FaultCountEvictionListener

`@ApplicationScoped` `GlobalReconciliationListener` -- after each reconciliation cycle, calls `FaultCountStore.evictAcrossNamespaces(tenancyId, retainedNodes)` where `retainedNodes` are the current graph's node IDs. This removes stale fault counts for nodes that have been removed from the graph. Also calls eviction on `onTenantStopped`. No namespace registry needed -- `evictAcrossNamespaces` works across all namespaces.

### ReconciliationEventEmitter

Package-private class instantiated by `ReconciliationLoop`. Builds CloudEvents for reconciliation lifecycle: `ReconciliationCompletedData` (cycle summary), `NodeFaultedData` (per-node failures), `NodeDriftedData` (per-node drift), `NodeRecoveredData` (per-node recovery from DRIFTED to PRESENT). CloudEvent type URIs defined in `DesiredStateEventTypes`.

### NoOp Default Beans

All `@DefaultBean` -- provide safe defaults when no domain-specific implementation is present. Displacement by `@ApplicationScoped` classpath presence:

| Default | Module | Behaviour | Displaced by |
|---------|--------|-----------|-------------|
| `NoOpHumanNodeHandler` | runtime | Skips node (Skipped outcome) | Engine-adapter `HumanTaskTarget` bindings |
| `NoOpPendingApprovalHandler` | runtime | Returns Failed on recordPending (misconfiguration signal) | `WorkItemPendingApprovalHandler` (work-adapter) |
| `NoOpConfigurationRetriever` | runtime | Returns empty list | Domain-specific retriever |
| `NoOpConfigurationAdapter` | runtime | Returns empty Optional | Domain-specific adapter |
| `DefaultFaultCountStore` | runtime | Wraps `InMemoryFaultCountStore` | `JpaFaultCountStore` (persistence-jpa) or custom store |

---

## Engine Adapter Architecture

When `casehub-desiredstate-engine` is on the classpath, `CaseTransitionExecutor` displaces `SimpleTransitionExecutor`. Transition plans become casehub-engine cases:

1. **Pre-filtering** -- CTE checks `PendingApprovalHandler` for each step. `Pending` steps are skipped, `Rejected` steps fire rejection. Only `None`/`Approved` steps proceed to case creation.
2. **Prune phase** -- automated removals become a Serverless Workflow where each step calls `desiredstate:dispatch` with `action=DEPROVISION`. Executed as a `FlowWorkerFunction` in a `Worker`.
3. **Grow phase** -- automated additions become a separate workflow with `action=PROVISION`.
4. **Human tasks** -- additions/removals with human gating become `HumanTaskTarget` bindings in the case definition (binding names: `human-provision-<nodeId>`, `human-deprovision-<nodeId>`).
5. **Case lifecycle** -- CTE cancels any previous active case before starting a new one (via `DesiredStateExecutionRegistry.getActiveCaseId`), cascading cancellation to associated WorkItems.

### Key Classes

| Class | Responsibility |
|-------|---------------|
| `CaseTransitionExecutor` | `@ApplicationScoped` `TransitionExecutor`. Generates two-phase case (prune Worker, grow Worker), partitions human nodes into `HumanTaskTarget` bindings. Reports outcomes optimistically (V1). |
| `TransitionWorkflowGenerator` | `@ApplicationScoped`. Generates Serverless Workflow 1.0 definitions from `List<OrderedStep>`. Each step becomes a sequential `call` task invoking `desiredstate:dispatch`. |
| `DesiredStateDispatch` | `@ApplicationScoped`. Registers `desiredstate:dispatch` via `CallableDispatchRegistry`. Handles full PendingApproval lifecycle within workflow steps: check -> provision/deprovision -> record pending. Routes via `NodeProvisionerRouter`. |
| `DesiredStateReplanDispatch` | `@ApplicationScoped`. Registers `desiredstate:replan` for RAS-triggered situation response. Reads current desired graph via `ReconciliationLoop.getDesired()`, reads actual state via `ActualStateAdapterRouter`, calls `SituationRecompilerEngine.recompile()`, and if a new result is returned, calls `LifecycleManager.updateDesired()`. |
| `DesiredStateExecutionRegistry` | `@ApplicationScoped`. Tracks per-execution `DesiredStateExecutionContext` (graph + tenancyId) and active case IDs per tenant. |
| `DesiredStateExecutionContext` | Record: `(DesiredStateGraph graph, String tenancyId)`. |

V1 reports outcomes optimistically -- proper case completion observation is a follow-up.

---

## CBR Internals

### CbrFaultPolicy

`@ApplicationScoped` `FaultPolicy` -- CBR retrieve -> filter by confidence -> adapt -> `GraphDiff` to diff adapted graph fragment against current graph (scoped by NodeType) to produce `List<GraphMutation>`. Records proposal via `CbrProposalTracker`.

### CbrSituationRecompiler

`@ApplicationScoped` `SituationRecompiler` -- same retrieve/adapt pipeline for situation events. Returns `Optional<CompilationResult>` (whole-graph replacement). Priority `Integer.MAX_VALUE` (fallback in chain).

### CbrProposalTracker

`@ApplicationScoped` -- mediates CBR proposals and reconciliation outcomes. `recordProposal()` stores proposals keyed by affected nodeIds. `matchOutcomes(TransitionResult)` maps affected nodeIds to outcomes (SUCCEEDED, FAILED, SKIPPED, REJECTED, SUPERSEDED, ALREADY_PRESENT), computes success rate, and returns `CbrOutcomeData` records.

### GraphDiff

Package-private utility. Diffs an adapted graph fragment against the current graph to produce `List<GraphMutation>`. `targetNodeId(GraphMutation)` extracts the target NodeId from any mutation variant. Scopes comparison by NodeType.

### CloudEvent Feedback

Outcomes are emitted as `io.casehub.cbr.outcome` CloudEvents with extensions for tenancyId, cbrPath, and successRate -- closing the CBR feedback loop. Type URIs defined in `CbrEventTypes`.

---

## Dependencies

### Depends On

| Repo | Module | How |
|------|--------|-----|
| `casehub-platform` | `platform-api` | Via parent BOM. Tenancy, governance types, preference types (`DurationPreference`, `DoublePreference`, `IntPreference`). |
| `casehub-engine` | `engine-api`, `engine-common`, `engine-flow` | Engine-adapter only: `CaseHubRuntime`, `CaseDefinition`, `FlowWorkerFunction`, `CallableDispatchRegistry`. |
| `casehub-worker` | `worker-api` | Engine-adapter only: `Worker`, `Capability`. |
| `casehub-work` | `work-api` | Work-adapter only: `WorkItemCreator`, `WorkItemCreateRequest`, `WorkItemRef`. |
| `casehub-ras` | `ras-api` | RAS-adapter only: `Ganglion`, `JavaSwitchGanglion`, `SituationDefinitionProvider`, `CorrelationKeyExtractor`, `ActiveSituation`. Also `ras-api` in the API module for `SituationRecompiler` signature. |

### Depended On By

| Repo | What it uses |
|------|-------------|
| `casehub-ops` | `desiredstate-api` -- deployment desired-state domain (ops module uses the graph SPIs) |

---

## Examples Summary

Four examples validate the SPI surface:

| Example | Domain pattern | Key SPI exercise |
|---------|---------------|-----------------|
| **Dungeon** | Flat dependency graph (rooms -> creatures) | Basic GoalCompiler, NodeProvisioner, FaultPolicy, HumanNodeHandler, 2D tile visualizer (SSE) |
| **Pipeline** | Medallion layers (Bronze/Silver/Gold) | Inferred dependencies, three-tier fault escalation (retry -> AI -> human), `ExecutionBackend` strategy, PendingApproval gates, schema validation |
| **Spatial** | 10x10 terrain grid | Multiple goal compilers, FaultPolicy with ActualState, situation detection integration, graph model stress test |
| **Expansion** | Build-then-defend lifecycle | `CompilationResult.Lifecycle` with construct/defend phases, `CompletionCondition`, `SituationRecompiler` for threat-triggered replanning, HTN planning strategies |

---

## Current State

- Core framework complete: API, runtime, testing, engine-adapter, work-adapter, ras-adapter, persistence-jpa all on main.
- Four working examples (dungeon, pipeline, spatial, expansion) demonstrating the full SPI surface including lifecycle phases and spatial graph sufficiency.
- Engine-adapter integration demonstrated in `PipelineCaseTransitionTest`.
- CBR pipeline complete: retrieve, adapt, apply, outcome feedback via CloudEvents.
- Multi-provisioner dispatch with per-type reconciliation scheduling.
- `LifecycleManager` for multi-phase deployments with dual CAS phase transitions.
- `SituationRecompiler` SPI with CBR and RAS-adapter implementations.
- Comprehensive OTel tracing on all reconciliation phases.
- JPA-backed `FaultCountStore` in persistence-jpa module with Flyway migration.
- `FaultCountEvictionListener` for automatic stale fault count cleanup.
- `ReconciliationLoop.Builder` for test construction without CDI.
- `TransitionExecutor` returns `TransitionResult` directly (blocking; Mutiny `Uni` wrapper removed in #91).
- `ReactiveNodeProvisioner` deleted (#53) -- zero implementations, dead SPI.
- `WorkItemHumanNodeHandler` removed (#72) -- creating orphaned WorkItems without case lifecycle is not valid.
- No graph persistence module -- graphs are in-memory only.
- `CaseTransitionExecutor` reports outcomes optimistically (V1); proper case completion observation is a follow-up.
