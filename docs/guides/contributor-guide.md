# casehub-desiredstate — Contributor Guide

> Internals, architecture, and extension points for platform builders working on the desired-state runtime itself.

**GitHub:** [casehubio/casehub-desiredstate](https://github.com/casehubio/casehub-desiredstate)

---

## Full Module Structure

| Module | artifactId | Contents |
|--------|-----------|----------|
| `api/` | `casehub-desiredstate-api` | Core SPIs and domain types. Pure Java + Mutiny `provided`. No CDI, no framework. |
| `runtime/` | `casehub-desiredstate` | CDI runtime: `ImmutableDesiredStateGraph`, `TransitionPlanner`, `ReconciliationLoop`, `SimpleTransitionExecutor`, `FaultPolicyEngine`, `DefaultNodeProvisionerRouter`, `CdiNodeProvisionerRouter`, `DefaultFaultCountStore`, `FaultCountEvictionListener`, `SituationRecompilerEngine`, `CbrFaultPolicy`, `CbrSituationRecompiler`, `GraphDiff`. Multi-provisioner dispatch, per-type reconciliation scheduling, CDI priority ladder fallbacks, fault count eviction, and CBR chain. `@ApplicationScoped` beans. OpenTelemetry instrumented. |
| `testing/` | `casehub-desiredstate-testing` | `MockNodeProvisioner`, `MockActualStateAdapter`, `MockPendingApprovalHandler`, `CannedEventSource`. Test scope only. |
| `engine-adapter/` | `casehub-desiredstate-engine` | `CaseTransitionExecutor` displaces `SimpleTransitionExecutor`. `TransitionWorkflowGenerator` generates Serverless Workflow 1.0 definitions. `DesiredStateWorkerFunction` wraps `NodeProvisioner` for engine dispatch. `DesiredStateDispatch` registers `desiredstate:dispatch` via `CallableDispatchRegistry`. `DesiredStateReplanDispatch` registers `desiredstate:replan` for RAS-triggered situation response. CTE pre-filters approval-gated nodes before case creation. |
| `work-adapter/` | `casehub-desiredstate-work` | `WorkItemPendingApprovalHandler` — WorkItem-backed approval lifecycle via `WorkItemCreator` SPI. Classpath-activated, displaces `NoOpPendingApprovalHandler`. |
| `ras-adapter/` | `casehub-desiredstate-ras` | `NodeFaultGanglion` (NODE_FAULTED -> detected, NODE_RECOVERED -> anti), `PersistentDriftGanglion` (persistent drift detection), `DesiredStateSituationDefinitionProvider` (3 situations: repeated-failure via Streak(3), persistent-drift via Count(3), zone-degradation via Rate(60%, 10)), `DesiredStateCorrelationKeyExtractor` (extracts `parentNodeId` for zone-level aggregation). |
| `persistence-jpa/` | `casehub-desiredstate-persistence-jpa` | `JpaFaultCountStore` — JPA-backed `FaultCountStore` with Flyway migration at `db/desiredstate/migration/`. `FaultCountEntity` with composite key `(namespace, tenancy_id, node_id)`. Tier 2 in CDI priority ladder — yields to application-provided stores. |
| `examples/dungeon/` | `casehub-desiredstate-example-dungeon` | Dungeon domain: rooms, creatures, traps as nodes; `GoblinProvisioner`, `HeroRaidFaultPolicy`, `DungeonGoalCompiler`, `DungeonVisualizer`. |
| `examples/pipeline/` | `casehub-desiredstate-example-pipeline` | Data pipeline: medallion-layered (Bronze/Silver/Gold) with schema validation, three-tier fault escalation, pluggable `ExecutionBackend` strategy. Demonstrates engine-adapter integration via `PipelineCaseTransitionTest`. |
| `examples/spatial/` | `casehub-desiredstate-example-spatial` | Battlefield spatial/vector POC: `TerrainGrid`, `FogOfWar`, `BattlefieldWorld`, multiple goal compilers, `ZoneRebalanceFaultPolicy`, `GridRenderer`. Graph sufficiency research for spatial domains. |
| `examples/expansion/` | `casehub-desiredstate-example-expansion` | Build-then-defend lifecycle: primary test vehicle for `CompilationResult.Lifecycle` phase transitions. `ExpansionGoalCompiler` produces lifecycle with "build" and "defend" phases. `ExpansionSituationRecompiler` escalates defense posture on situation. |

---

## Internal Architecture

### TransitionPlanner

Compares desired graph to actual state. Produces a `TransitionPlan` with topologically ordered additions (roots before leaves, Kahn's algorithm) and removals (orphaned nodes, leaves before roots). Plans are deterministic given the same inputs. Ordering rule: pruning before growing — removals execute first, then additions.

### ReconciliationLoop

Per-tenant event-driven reconciliation engine (`@ApplicationScoped`). Two trigger paths:
- **Event-driven** — subscribes to `MergedEventSource.stream()` with debouncing.
- **Periodic re-sync** — default 5 minutes, configurable per NodeType via `DesiredStatePreferenceKeys.RESYNC_INTERVAL`.

Each cycle: read actual state -> detect drift -> plan transitions -> execute -> apply fault feedback -> match CBR outcomes.

The loop never dies on exception — a dead loop is worse than a failed cycle.

### OpenTelemetry Tracing

Comprehensive span tree per cycle using `GlobalOpenTelemetry.getTracer("io.casehub.desiredstate")`:
- `reconcile` — full-graph or type-filtered with `desiredstate.reconcile.types`
- `readActual` — with `desiredstate.node.count`
- `detectDrift` — with `desiredstate.drift.count`
- `plan` — with `desiredstate.additions`, `desiredstate.removals`
- `execute`
- `faultFeedback` — with `desiredstate.fault.count`, `desiredstate.mutation.count`

Errors set `StatusCode.ERROR` and call `recordException()`.

### Multi-Provisioner Dispatch

`NodeProvisionerRouter` (API interface) / `DefaultNodeProvisionerRouter` (runtime impl): each `NodeProvisioner` declares `handledTypes()` and `resyncInterval()`. The router builds a `Map<NodeType, NodeProvisioner>` lookup table, enforcing no NodeType is claimed by two provisioners. `CdiNodeProvisionerRouter` is the CDI-wired subclass injecting `Instance<NodeProvisioner>` and `PreferenceProvider` for interval overrides.

`ReconciliationLoop` uses `computeIntervalGroups()` to group node types by resync interval and creates **separate `ScheduledFuture` timers per interval group**. Each timer fires `reconcileTypes(Set<NodeType>)` which filters the desired graph and reconciles only matching types — different node types can have different reconciliation frequencies.

### LifecycleManager

Manages multi-phase `CompilationResult.Lifecycle` deployments. Internal `TenantLifecycle` record tracks `(List<Phase>, phaseIndex)`. On `onCycleCompleted()`: checks if current phase's `completionCondition.isComplete()` against actual state, computes next phase index, and advances via **dual CAS**: `lifecycles.replace(tenancyId, current, next)` on the ConcurrentHashMap entry + `loop.compareAndSetDesired(tenancyId, desired, nextPhase.graph())` on the AtomicReference. If either CAS fails (concurrent update), rolls back. `casRetryMutations()` also uses a CAS retry loop for fault-policy mutations.

### SituationRecompiler Chain

`SituationRecompilerEngine` (`@ApplicationScoped`) — chain-of-responsibility aggregation of `SituationRecompiler` beans ordered by `priority()`. The ras-adapter module provides ganglia as situation detectors; `CbrSituationRecompiler` provides CBR-based recompilation at `priority() = Integer.MAX_VALUE` (fallback position). Domain-specific recompilers run first.

---

## Engine Adapter Architecture

When `casehub-desiredstate-engine` is on the classpath, `CaseTransitionExecutor` displaces `SimpleTransitionExecutor`. Transition plans become casehub-engine cases:

1. **Prune phase** — removals become a Serverless Workflow where each step calls `desiredstate:dispatch` with `action=DEPROVISION`. Executed as a `FlowWorkerFunction` in a `Worker`.
2. **Grow phase** — automated additions become a separate workflow with `action=PROVISION`.
3. **Human tasks** — additions with human gating become `HumanTaskTarget` bindings in the case definition (binding names: `human-provision-<nodeId>`, `human-deprovision-<nodeId>`).

`TransitionWorkflowGenerator` generates Serverless Workflow 1.0 definitions. `DesiredStateWorkerFunction` wraps `NodeProvisioner` calls for engine dispatch. `DesiredStateDispatch` registers `desiredstate:dispatch` via `CallableDispatchRegistry` (engine-flow). `DesiredStateReplanDispatch` registers `desiredstate:replan` for RAS-triggered situation response via `SituationRecompilerEngine`.

CTE pre-filters approval-gated nodes before case creation. CTE cancels any previous active case before starting a new one, cascading cancellation to associated WorkItems.

V1 reports outcomes optimistically — proper case completion observation is a follow-up.

---

## CBR Internals

### CbrSituationRecompiler

Implements `SituationRecompiler` — retrieves past configurations, filters by confidence, adapts, and tracks via `CbrProposalTracker`. Priority `Integer.MAX_VALUE` (fallback in chain).

### CbrFaultPolicy

Implements `FaultPolicy` — same retrieve/adapt pipeline for fault events. Uses `GraphDiff` to diff adapted graph fragment against current graph, scoped by NodeType, to produce `List<GraphMutation>`.

### CbrProposalTracker

`@ApplicationScoped` — mediates CBR proposals and reconciliation outcomes. `matchOutcomes()` is called from `ReconciliationLoop` after execution completes. Maps affected nodeIds to outcomes (SUCCEEDED, FAILED, SKIPPED, REJECTED, SUPERSEDED, ALREADY_PRESENT), computes success rate, and returns `CbrOutcomeData` records.

### CloudEvent Feedback

Outcomes are emitted as `io.casehub.cbr.outcome` CloudEvents with extensions for tenancyId, cbrPath, and successRate — closing the CBR feedback loop. Additional CloudEvent types in `DesiredStateEventTypes`: `io.casehub.desiredstate.reconciliation.completed`, `io.casehub.desiredstate.node.faulted`, `io.casehub.desiredstate.node.drifted`, `io.casehub.desiredstate.node.recovered`.

---

## Dependencies

### Depends On

| Repo | Module | How |
|------|--------|-----|
| `casehub-platform` | `platform-api` | Via parent BOM. Tenancy, governance types. |
| `casehub-engine` | `engine-api`, `engine-common`, `engine-flow` | Engine-adapter only: `CaseHubRuntime`, `CaseDefinition`, `FlowWorkerFunction`, `Worker`, `Binding`. |
| `casehub-worker` | `worker-api` | Engine-adapter only: `Worker`, `Capability`. |
| `casehub-work` | `work-api` | Work-adapter only: `WorkItemCreator`, `WorkItemCreateRequest`, `WorkItemRef`. |
| `casehub-ras` | `ras-api` | RAS-adapter only: `Ganglion`, `JavaSwitchGanglion`, `SituationDefinitionProvider`, `CorrelationKeyExtractor`. |

### Depended On By

| Repo | What it uses |
|------|-------------|
| `casehub-ops` | `desiredstate-api` — deployment desired-state domain (ops module uses the graph SPIs) |

---

## Current State

- Core framework complete: API, runtime, testing, engine-adapter, work-adapter, ras-adapter all on main.
- Four working examples (dungeon, pipeline, spatial, expansion) demonstrating the full SPI surface including lifecycle phases and spatial graph sufficiency.
- Engine-adapter integration demonstrated in `PipelineCaseTransitionTest`.
- CBR pipeline complete: retrieve, adapt, apply, outcome feedback via CloudEvents.
- Multi-provisioner dispatch with per-type reconciliation scheduling.
- `LifecycleManager` for multi-phase deployments with dual CAS phase transitions.
- `SituationRecompiler` SPI with CBR and RAS-adapter implementations.
- Comprehensive OTel tracing on all reconciliation phases.
- JPA-backed `FaultCountStore` in persistence-jpa module.
- No graph persistence module — graphs are in-memory only.
- `CaseTransitionExecutor` reports outcomes optimistically (V1); proper case completion observation is a follow-up.
