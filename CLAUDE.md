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
FaultPolicy, EventSource. Execution delegates to TransitionExecutor SPI — SimpleTransitionExecutor (default)
for lightweight deployments; CaseTransitionExecutor (engine-adapter, classpath-activated) for case-backed
execution with Worker(Workflow) phases via casehub-engine-flow.

**Architecture:** `ARC42STORIES.MD` — Arc42Stories format, CaseHub Foundation-tier profile
**Research doc:** `docs/superpowers/research/2026-06-07-desired-state-management-research.md`
**Design spec:** `docs/superpowers/specs/2026-06-12-generic-runtime-design.md`

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode deploy -DskipTests   # CI only — requires GITHUB_TOKEN
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `api/` | `casehub-desiredstate-api` | `io.casehub.desiredstate.api` | Core SPIs + domain types. Pure Java, Mutiny provided, CDI annotations provided. |
| `runtime/` | `casehub-desiredstate` | `io.casehub.desiredstate.runtime` | TransitionPlanner, ReconciliationLoop, FaultPolicyEngine, ImmutableDesiredStateGraph, SimpleTransitionExecutor. Quarkus library. |
| `testing/` | `casehub-desiredstate-testing` | `io.casehub.desiredstate.testing` | Mock SPIs and test fixtures. **Test scope only.** |
| `engine-adapter/` | `casehub-desiredstate-engine` | `io.casehub.desiredstate.engine` | CaseTransitionExecutor — orchestration-tier bridge. Generates cases with Worker(Workflow) phases. |
| `examples/dungeon/` | `casehub-desiredstate-example-dungeon` | `io.casehub.desiredstate.example.dungeon` | Nefarious Dungeons — teaching example implementing all SPIs with 2D tile visualizer. |
| `examples/pipeline/` | `casehub-desiredstate-example-pipeline` | `io.casehub.desiredstate.example.pipeline` | Data Pipeline — teaching example with medallion architecture (Bronze/Silver/Gold), schema validation, three-tier fault escalation (retry → AI → human), pluggable `ExecutionBackend` strategy per processing stage. |

## Core SPIs (api/)

| SPI | Signature | Domain responsibility |
|-----|-----------|----------------------|
| `GoalCompiler<G>` | `compile(G goals, DesiredStateGraphFactory) → DesiredStateGraph` | Translate goal declaration into node graph |
| `ActualStateAdapter` | `readActual(DesiredStateGraph) → ActualState` | Read current reality from domain sources |
| `NodeProvisioner` | `provision(DesiredNode, ProvisionContext) → ProvisionResult` | Create/update a single node |
| `NodeProvisioner` | `deprovision(DesiredNode, DeprovisionContext) → DeprovisionResult` | Remove a single node |
| `ReactiveNodeProvisioner` | `provision/deprovision → Uni<Result>` | Reactive variant of NodeProvisioner |
| `FaultPolicy` | `onFault(FaultEvent, DesiredStateGraph) → List<GraphMutation>` | Mutate graph in response to fault |
| `EventSource` | `stream() → Multi<StateEvent>` | Stream actual-state events into reconciliation loop |
| `TransitionExecutor` | `execute(TransitionPlan) → Uni<TransitionResult>` | Execute a transition plan (SPI'd — simple or case-backed) |
| `DesiredStateGraph` | query + mutation methods | SPI interface — graph backing store is pluggable |
| `DesiredStateGraphFactory` | `empty()`, `of(nodes, deps)` | Creates graph instances |

## Core Runtime Types (api/)

| Type | Purpose |
|------|---------|
| `DesiredNode` | `id`, `type`, `spec` (opaque domain payload), `requiresHuman` |
| `NodeSpec` | Marker interface — domains implement with typed records |
| `NodeId`, `NodeType`, `Dependency` | Value types for graph identity and edges |
| `TransitionPlan` | Pruning-first ordered steps — `removals`, `additions`, `before`/`after` graphs |
| `TransitionResult` | Per-node `StepOutcome` map (Succeeded/Failed/Skipped) |
| `ActualState` | Map of `NodeId → NodeStatus` (PRESENT/ABSENT/DEGRADED/UNKNOWN) |
| `ReconciliationResult` | `resolved`, `drifted`, `faulted` node sets + `mutations` |
| `FaultEvent` | Node + `FaultType` + detail |
| `GraphMutation` | Sealed interface — AddNode, RemoveNode, UpdateNode, AddDependency, RemoveDependency |
| `ProvisionContext` | `tenancyId` + `DesiredStateGraph` |
| `ProvisionResult`, `DeprovisionResult` | Sealed — Success / Failed(reason) |
| `StepOutcome` | Sealed — Succeeded / Failed(reason) / Skipped(reason) |

## Ordering Rule — Pruning Before Growing

1. Diff desired graph vs actual state
2. Plan removal workflows (leaves before roots — dependency-aware)
3. Plan addition workflows (roots before leaves — dependency-aware)
4. Execute via TransitionExecutor SPI (simple sequential or case-backed Worker(Workflow) phases)

This ensures no dangling dependencies and no half-removed states.

## Human Nodes

`DesiredNode.requiresHuman = true` → `SimpleTransitionExecutor` skips the node (StepOutcome.Skipped).
`CaseTransitionExecutor` (engine-adapter) generates a `humanTask` binding in the case definition —
the engine's HITL infrastructure handles WorkItem creation and completion. The reconciliation loop
detects human node completion on the next cycle via ActualStateAdapter.

## Cross-Repo Conventions

Protocols live in `casehub/garden`. Do not write protocol files in this repo.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `docs/superpowers/specs/` |
| adr | `docs/adr/` |
| handover | workspace `HANDOFF.md` |
| write-blog | workspace `blog/` |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/casehub-desiredstate

## Workspace

**Project repo:** `/Users/mdproctor/claude/casehub/desiredstate`
**Workspace:** `/Users/mdproctor/claude/public/casehub-desiredstate`
**Workspace type:** public
