# CaseHub Desired State

## Project Type

type: java

## Repository Role

Foundation-tier generic desired-state management runtime. Domain-agnostic — knows about graphs, nodes,
edges, planners, reconciliation loops, and fault policy primitives. Knows nothing about Kubernetes pods,
IoT devices, CaseHub agents, or infrastructure resources. Domains plug in via SPIs.

**Tier:** Foundation (alongside casehub-platform, casehub-ledger, casehub-work, casehub-qhorus in the build order)

**Design philosophy:** Generic first, domains layered on top. The runtime is written once. Each new domain
contributes only domain-specific knowledge via SPIs: GoalCompiler, ActualStateAdapter, NodeProvisioner,
FaultPolicy, EventSource, HumanNodeHandler, PendingApprovalHandler. NodeProvisionerRouter dispatches to
provisioners by NodeType. Execution delegates to TransitionExecutor SPI — SimpleTransitionExecutor (default)
for lightweight deployments; CaseTransitionExecutor (engine-adapter, classpath-activated) for case-backed
execution with Worker(Workflow) phases via casehub-engine-flow.

**Architecture:** `ARC42STORIES.MD` — Arc42Stories format, CaseHub Foundation-tier profile
**Research doc:** `docs/research/2026-06-07-desired-state-management-research.md`
**Design spec:** `docs/specs/2026-06-12-generic-runtime-design.md`

## Platform Docs
- [Platform Index](https://raw.githubusercontent.com/casehubio/parent/main/docs/INDEX.md) — discovery index (start here)
- [Building Platform](https://raw.githubusercontent.com/casehubio/parent/main/docs/guides/building-platform.md) — platform contributor guide

## Repo Guide

This repo owns its own documentation, synced to parent via CI:
- `docs/guides/consumer-guide.md` — for app builders: modules, APIs, quick start
- `docs/guides/contributor-guide.md` — for platform builders: architecture, SPIs, internals

Update the relevant guide in the same session when implementation changes modules, SPIs, or public APIs. Do not defer — drift compounds.

Read `docs/guides/consumer-guide.md` for app-level work. Only read `docs/guides/contributor-guide.md` when modifying this repo's internals or extension points.

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode deploy -DskipTests   # CI only — requires GITHUB_TOKEN
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `api/` | `casehub-desiredstate-api` | `io.casehub.desiredstate.api` | Core SPIs + domain types. Pure Java, Mutiny provided, CDI annotations provided. |
| `runtime/` | `casehub-desiredstate` | `io.casehub.desiredstate.runtime` | TransitionPlanner, ReconciliationLoop, FaultPolicyEngine, ImmutableDesiredStateGraph, SimpleTransitionExecutor, DefaultNodeProvisionerRouter, CdiNodeProvisionerRouter, DefaultFaultCountStore, FaultCountEvictionListener, DesiredStatePreferenceKeys, SituationRecompilerEngine, CbrFaultPolicy, CbrSituationRecompiler, GraphDiff. Multi-provisioner dispatch, per-type reconciliation scheduling, CDI priority ladder fallbacks, fault count eviction, and CBR chain. Quarkus library. |
| `testing/` | `casehub-desiredstate-testing` | `io.casehub.desiredstate.testing` | Mock SPIs and test fixtures. **Test scope only.** |
| `engine-adapter/` | `casehub-desiredstate-engine` | `io.casehub.desiredstate.engine` | CaseTransitionExecutor — orchestration-tier bridge. Generates cases with Worker(Workflow) phases. DesiredStateDispatch registers `desiredstate:dispatch` via CallableDispatchRegistry (engine-flow) for workflow step execution with full PendingApproval lifecycle. DesiredStateReplanDispatch registers `desiredstate:replan` for RAS-triggered situation response via SituationRecompilerEngine (reads ActualState via ActualStateAdapterRouter). CTE pre-filters approval-gated nodes before case creation. |
| `work-adapter/` | `casehub-desiredstate-work` | `io.casehub.desiredstate.work` | WorkItemPendingApprovalHandler — WorkItem-backed approval lifecycle via WorkItemCreator SPI. Classpath-activated, displaces NoOpPendingApprovalHandler. |
| `examples/dungeon/` | `casehub-desiredstate-example-dungeon` | `io.casehub.desiredstate.example.dungeon` | Nefarious Dungeons — teaching example implementing all SPIs with 2D tile visualizer. |
| `examples/pipeline/` | `casehub-desiredstate-example-pipeline` | `io.casehub.desiredstate.example.pipeline` | Data Pipeline — teaching example with medallion architecture (Bronze/Silver/Gold), schema validation, three-tier fault escalation (retry → AI → human), pluggable `ExecutionBackend` strategy per processing stage. PendingApproval gates on Gold-tier nodes. |
| `examples/spatial/` | `casehub-desiredstate-example-spatial` | `io.casehub.desiredstate.example.spatial` | Spatial/vector POC — 10x10 terrain grid, fog of war, three scenarios evaluating graph model with spatial state. Defense posture, attack waypoints, force distribution. |
| `examples/expansion/` | `casehub-desiredstate-example-expansion` | `io.casehub.desiredstate.example.expansion` | Expansion — build-then-defend lifecycle teaching example with HTN planner, fault-triggered replanning via SituationRecompiler. |
| `persistence-jpa/` | `casehub-desiredstate-persistence-jpa` | `io.casehub.desiredstate.persistence.jpa` | JPA-backed FaultCountStore — durable fault counts across restarts. Tier 2 in CDI priority ladder. Flyway migration at `db/desiredstate/migration/`. |
| `ras-adapter/` | `casehub-desiredstate-ras` | `io.casehub.desiredstate.ras` | RAS bridge — Ganglia for reconciliation patterns, situation definitions, correlation key extraction for zone-level aggregate detection. |

## Core SPIs (api/)

| SPI | Signature | Domain responsibility |
|-----|-----------|----------------------|
| `GoalCompiler<G>` | `compile(G goals, DesiredStateGraphFactory) → CompilationResult` | Translate goal declaration into node graph or phase sequence |
| `ActualStateAdapter` | `readActual(DesiredStateGraph, String tenancyId) → ActualState` | Read current reality from domain sources |
| `ActualStateAdapter` | `handledTypes() → Set<NodeType>` | Declare node types this adapter handles (abstract — no default) |
| `ActualStateAdapterRouter` | `readActual(DesiredStateGraph, String tenancyId) → ActualState` | Route readActual calls to the correct adapter by NodeType |
| `ActualStateAdapterRouter` | `allHandledTypes() → Set<NodeType>` | Get all node types handled by registered adapters |
| `MergedEventSource` | `stream() → Multi<StateEvent>` | Composed event stream from multiple domain EventSource beans |
| `NodeProvisioner` | `handledTypes() → Set<NodeType>` | Declare node types this provisioner handles (abstract — no default) |
| `NodeProvisioner` | `resyncInterval() → Duration` | Declare resync interval for handled types (default: 5 minutes) |
| `NodeProvisioner` | `provision(DesiredNode, ProvisionContext) → ProvisionResult` | Create/update a single node |
| `NodeProvisioner` | `deprovision(DesiredNode, DeprovisionContext) → DeprovisionResult` | Remove a single node |
| `NodeProvisionerRouter` | `provision(DesiredNode, ProvisionContext) → ProvisionResult` | Route provision calls to the correct provisioner by NodeType |
| `NodeProvisionerRouter` | `deprovision(DesiredNode, DeprovisionContext) → DeprovisionResult` | Route deprovision calls to the correct provisioner by NodeType |
| `NodeProvisionerRouter` | `resyncIntervalFor(NodeType) → Duration` | Get effective resync interval for a type (provisioner default or Preferences override) |
| `FaultPolicy` | `onFault(String tenancyId, FaultEvent, DesiredStateGraph, ActualState) → List<GraphMutation>` | Mutate graph in response to fault (with actual state visibility). `addReviewNode(NodeType, ReviewSpecFactory)` static factory — creates review node with dependency edge to faulted node, ID derived from `NodeType.value()` |
| `FaultCountStore` | `incrementAndGet(namespace, tenancyId, nodeId) → int`, `getCount(...)`, `reset(...)`, `remove(...)`, `evict(namespace, tenancyId, retainedNodes)`, `evictAcrossNamespaces(tenancyId, retainedNodes)` | Pluggable fault count storage — namespace-scoped, tenant-isolated. `evictAcrossNamespaces` for cross-namespace bulk eviction of removed nodes |
| `EventSource` | `stream() → Multi<StateEvent>` | Stream actual-state events into reconciliation loop |
| `TransitionExecutor` | `execute(TransitionPlan, String tenancyId) → TransitionResult` | Execute a transition plan (SPI'd — simple or case-backed) |
| `HumanNodeHandler` | `onProvision(DesiredNode, ProvisionContext) → StepOutcome` | Handle human-gated nodes during provision (called when `requiresHuman(PROVISION)`) |
| `HumanNodeHandler` | `default onDeprovision(DesiredNode, DeprovisionContext) → StepOutcome` | Handle human-gated nodes during deprovision (default: Skipped; called when `requiresHuman(DEPROVISION)`) |
| `PendingApprovalHandler` | `check(DesiredNode, StepAction, String tenancyId) → ApprovalCheckResult` | Track approval lifecycle for provisioner-initiated PendingApproval requests |
| `SituationRecompiler` | `recompile(String tenancyId, DesiredStateGraph, ActualState, ActiveSituation, DesiredStateGraphFactory) → Optional<CompilationResult>` | Situation-driven graph recompilation — independent of GoalCompiler. `priority()` default method for chain ordering |
| `ConfigurationRetriever` | `retrieve(RetrievalContext, int maxResults) → List<RetrievedConfiguration>` | CBR Retrieve — find similar past configurations by fault/situation context |
| `ConfigurationAdapter` | `adapt(RetrievedConfiguration, RetrievalContext) → Optional<AdaptedConfiguration>` | CBR Reuse — adapt retrieved configuration to current context |
| `ReconciliationListener` | `onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph, ActualState)` | Per-tenant post-cycle callback for lifecycle phase completion checks |
| `GlobalReconciliationListener` | `onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph, ActualState)`, `default onTenantStopped(String tenancyId)` | Application-scoped post-cycle callback — CDI-discovered, fires for all tenants. `onTenantStopped` fires during stop for cleanup. Fires only from full `reconcile()`, not from type-filtered `reconcileTypes()` |
| `CompletionCondition` | `isComplete(DesiredStateGraph, ActualState) → boolean` | Predicate for lifecycle phase completion |
| `DesiredStateGraph` | query + mutation + `filterByTypes(Set<NodeType>)` methods | SPI interface — graph backing store is pluggable. `filterByTypes` is a default method using subtractive approach via `withoutNode()` |
| `DesiredStateGraphFactory` | `empty()`, `of(nodes, deps)` | Creates graph instances |

## Core Runtime Types

| Type | Purpose |
|------|---------|
| `CompilationResult` | Sealed — `SingleGraph(DesiredStateGraph)` \| `Lifecycle(List<Phase>)`. Returned by GoalCompiler.compile() |
| `Phase` | `id`, `graph`, `completionCondition`. Successor sequence is list ordering |
| `LifecycleManager` | `@ApplicationScoped` — orchestrates phase transitions via CAS. `start()`, `stop()`, `updateDesired()`, `compareAndSetDesired()` |
| `DesiredNode` | `id`, `type`, `spec` (opaque domain payload), `humanGating` (per-action enum). `requiresHuman(StepAction)` and `requiresHuman()` merge node + spec gating |
| `HumanGating` | Enum — `NONE`, `PROVISION_ONLY`, `DEPROVISION_ONLY`, `ALL`. `requiresHuman(StepAction)`, `any()`, `merge(HumanGating)` |
| `NodeSpec` | Marker interface — domains implement with typed records. `humanGating()` default returns `NONE` |
| `NodeId`, `NodeType`, `Dependency` | Value types for graph identity and edges |
| `TransitionPlan` | Pruning-first ordered steps — `removals`, `additions`, `before`/`after` graphs |
| `TransitionResult` | Per-node `StepOutcome` map (Succeeded/Failed/Skipped) |
| `ActualState` | Map of `NodeId → NodeStatus` (PRESENT/ABSENT/DEGRADED/UNKNOWN) |
| `ReconciliationResult` | `resolved`, `drifted`, `faulted` node sets + `mutations` |
| `FaultEvent` | Node + `FaultType` + detail |
| `ThresholdFaultPolicy` | Reusable `FaultPolicy` (api module) — counts faults per node via pluggable `FaultCountStore` SPI. Multi-tier escalation with graph-presence guards via `dependentsOf()`. Builder: faultTypes, nodeTypes, ignoreTypes, tier(threshold, action, nodeType), faultCountStore, namespace. Auto-ignore tier nodeTypes. First-match-wins evaluation (highest tier first). `resetCount(tenancyId, nodeId)` for external recovery-reset. Lazy eviction on fault for removed nodes. Default `InMemoryFaultCountStore` |
| `InMemoryFaultCountStore` | Default `FaultCountStore` — `ConcurrentHashMap` with `(namespace, tenancyId, nodeId)` composite key. Thread-safe. In `api/` (builder default, not CDI-managed) |
| `ReviewSpecFactory` | `(FaultEvent, DesiredStateGraph) → NodeSpec` callback for `FaultPolicy.addReviewNode()` |
| `GraphMutation` | Sealed interface — AddNode, RemoveNode, UpdateNode(id, adaptedNode), AddDependency, RemoveDependency. UpdateNode carries full adapted DesiredNode |
| `GraphMutations` | Static utility (api module) — `addNodeDependingOn(DesiredNode, NodeId)` returns `[AddNode, AddDependency]`. Common pattern for adding a node with a dependency edge to an existing node |
| `ProvisionContext` | `tenancyId` + `DesiredStateGraph` + optional `PlanApproval` (re-entry after approval) |
| `DeprovisionContext` | `tenancyId` + `DesiredStateGraph` + optional `PlanApproval` (re-entry after approval) |
| `PlanApproval` | `planReference`, `approvedBy`, `approvedAt` — carried in context on re-entry |
| `ApprovalCheckResult` | Sealed — None / Pending(planReference) / Approved(PlanApproval) / Rejected(planReference, reason) |
| `ProvisionResult`, `DeprovisionResult` | Sealed — Success / Failed(reason) / PendingApproval(nodeId, planReference) |
| `StepOutcome` | Sealed — Succeeded / Failed(reason) / Skipped(reason) / Rejected(reason) |
| `DefaultNodeProvisionerRouter` | Runtime implementation of NodeProvisionerRouter — builds routing table from all provisioners, validates resync intervals, integrates Preferences overrides |
| `CdiNodeProvisionerRouter` | CDI-wired subclass injecting `Instance<NodeProvisioner>` and `PreferenceProvider` |
| `DefaultActualStateAdapterRouter` | Runtime implementation of ActualStateAdapterRouter — builds routing table from all adapters, dispatches readActual by NodeType, merges results |
| `CdiActualStateAdapterRouter` | CDI-wired subclass injecting `Instance<ActualStateAdapter>` |
| `DefaultFaultCountStore` | `@DefaultBean @ApplicationScoped` — CDI fallback wrapping `InMemoryFaultCountStore`. Yields to `JpaFaultCountStore` when `persistence-jpa` is on classpath. Tier 1a functional fallback |
| `FaultCountEvictionListener` | `@ApplicationScoped` GlobalReconciliationListener — calls `evictAcrossNamespaces` after each cycle and on tenant stop. CDI-discovered. No namespace registry needed |
| `JpaFaultCountStore` | `@ApplicationScoped` (persistence-jpa/) — JPA-backed FaultCountStore. Portable SQL (H2 MODE=PostgreSQL + PostgreSQL). Flyway migration V1 at `db/desiredstate/migration/` |
| `FaultCountEntity` | JPA entity for `ds_fault_count` table — composite key `(namespace, tenancy_id, node_id)`, count field. `@IdClass(Key.class)` |
| `DefaultMergedEventSource` | Runtime implementation of MergedEventSource — merges multiple EventSource streams with per-stream error isolation |
| `CdiMergedEventSource` | CDI-wired subclass injecting `Instance<EventSource>` |
| `DesiredStatePreferenceKeys` | Preference key definitions — `RESYNC_INTERVAL` with per-NodeType sub-key support, `CBR_MIN_RETRIEVAL_CONFIDENCE`, `CBR_MIN_ADAPTATION_CONFIDENCE`, `CBR_MAX_CANDIDATES` |
| `RetrievalContext` | CBR context — `currentGraph`, `actualState`, `faultEvent` or `situation`. Factory methods: `forFault()`, `forSituation()` |
| `RetrievedConfiguration` | Past configuration that worked — `graph`, `confidence`, `sourceId`, `metadata` |
| `AdaptedConfiguration` | Adapted configuration — `graph`, `confidence`, `sourceId` |
| `CbrConfiguration` | CBR thresholds — `minimumRetrievalConfidence`, `minimumAdaptationConfidence`, `maxCandidates` |
| `CbrFaultPolicy` | `@ApplicationScoped` FaultPolicy — CBR retrieve → adapt → diff chain for per-node mutations |
| `CbrSituationRecompiler` | `@ApplicationScoped` SituationRecompiler — CBR retrieve → adapt → CompilationResult for whole-graph replacement. `priority() = Integer.MAX_VALUE` (fallback) |
| `SituationRecompilerEngine` | `@ApplicationScoped` — chain-of-responsibility aggregation of SituationRecompiler beans by priority |
| `GraphDiff` | Package-private utility — diffs adapted graph fragment against current to produce `List<GraphMutation>`. `targetNodeId()` extracts target NodeId from a mutation. Scope by NodeType |
| `CbrProposal` | Record — `sourceId`, `path` (FAULT/SITUATION), `affectedNodeIds`, `timestamp`. Tracks what CBR proposed |
| `CbrPath` | Enum — `FAULT`, `SITUATION`. Distinguishes CBR entry path |
| `CbrOutcomeData` | CloudEvent data — CBR outcome with per-node results, success rate, timestamps |
| `CbrEventTypes` | CloudEvent type URI constants for `io.casehub.cbr.*` namespace |
| `CbrProposalTracker` | `@ApplicationScoped` — mediates CBR proposals and reconciliation outcomes. Records proposals, matches against TransitionResult |
| `ReconciliationCompletedData` | CloudEvent data — cycle summary |
| `NodeFaultedData` | CloudEvent data — per-node fault |
| `NodeDriftedData` | CloudEvent data — per-node drift |
| `NodeRecoveredData` | CloudEvent data — per-node recovery |
| `DesiredStateEventTypes` | CloudEvent type URI constants for `io.casehub.desiredstate.*` namespace |

## Ordering Rule — Pruning Before Growing

1. Diff desired graph vs actual state
2. Plan removal workflows (leaves before roots — dependency-aware)
3. Plan addition workflows (roots before leaves — dependency-aware)
4. Execute via TransitionExecutor SPI (simple sequential or case-backed Worker(Workflow) phases)

This ensures no dangling dependencies and no half-removed states.

## Human Nodes

`DesiredNode.humanGating` controls per-action routing via `HumanGating` enum (NONE, PROVISION_ONLY,
DEPROVISION_ONLY, ALL). `SimpleTransitionExecutor` checks `node.requiresHuman(StepAction)` independently
for each action — a node with `PROVISION_ONLY` routes provision to `HumanNodeHandler` and deprovision
to the provisioner. Precedence per action: humanGating > PendingApproval > provisioner.

`NodeSpec.humanGating()` provides type-level gating (default NONE). `DesiredNode.humanGating` provides
instance-level gating. Merge: per-action OR — either source can elevate an action to human-gated.

`NoOpHumanNodeHandler` (`@DefaultBean`) skips the node for both actions (misconfiguration signal).
Human nodes that need lifecycle management require `CaseTransitionExecutor` (engine-adapter) — it
creates `HumanTaskTarget` case bindings (binding names: `human-provision-<nodeId>`,
`human-deprovision-<nodeId>`), delegating human task execution to casehub-work. CTE cancels any
previous active case before starting a new one, cascading cancellation to associated WorkItems.

`WorkItemHumanNodeHandler` was removed (#72) — creating orphaned WorkItems without case lifecycle
is not a valid deployment option.

**Approval-gated nodes:** `NodeProvisioner.provision()` may return `PendingApproval(nodeId, planReference)` →
`SimpleTransitionExecutor` delegates to `PendingApprovalHandler` SPI. `NoOpPendingApprovalHandler` (`@DefaultBean`)
returns Failed (misconfiguration signal). `WorkItemPendingApprovalHandler` (work-adapter, classpath-activated)
creates a WorkItem and polls each cycle; on approval, re-calls the provisioner with
`PlanApproval` in `ProvisionContext`. On rejection, fires `FaultType.APPROVAL_REJECTED` via `StepOutcome.Rejected`.
Same pattern applies to deprovision via `DeprovisionContext`.

## Cross-Repo Conventions

Protocols live in `casehub/garden`. Do not write protocol files in this repo.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `docs/specs/` |
| adr | `docs/adr/` |
| handover | workspace `HANDOFF.md` |
| write-blog | project `docs/blog/` |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/casehub-desiredstate

## Workspace

**Project repo:** `/Users/mdproctor/claude/casehub/desiredstate`
**Workspace:** `/Users/mdproctor/claude/public/casehub-desiredstate`
**Workspace type:** public
