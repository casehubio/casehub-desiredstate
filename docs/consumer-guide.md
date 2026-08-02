# casehub-desiredstate — Consumer Guide

> Generic desired-state management runtime: declare what should exist, observe what does, reconcile the gap continuously.

**GitHub:** [casehubio/casehub-desiredstate](https://github.com/casehubio/casehub-desiredstate)
**Tier:** Foundation (alongside casehub-platform, casehub-ledger, casehub-work, casehub-qhorus)

---

## Purpose

Desired-state management follows the Kubernetes controller pattern: desired state is declarative, actual state is observed, and the runtime closes the gap automatically. But unlike Kubernetes, nodes can require human approval or human provisioning, and transition plans can be delegated to casehub-engine as CaseDefinitions with Serverless Workflow phases.

The framework is domain-agnostic. It provides graph management, topological transition planning, fault policy, and reconciliation orchestration. Domain-specific concerns — what a "node" means, how to provision it, how to observe its state — are injected via SPIs.

---

## Modules to Depend On

| Module | artifactId | When to use |
|--------|-----------|-------------|
| `api/` | `casehub-desiredstate-api` | Always. Core SPIs and domain types. Pure Java + Mutiny `provided`. No CDI, no framework. |
| `runtime/` | `casehub-desiredstate` | Always in production. CDI runtime: reconciliation loop, transition planner, fault policy engine. `@ApplicationScoped` beans. OpenTelemetry instrumented. |
| `testing/` | `casehub-desiredstate-testing` | Test scope only. `MockNodeProvisioner`, `MockActualStateAdapter`, `MockPendingApprovalHandler`, `CannedEventSource`. |
| `engine-adapter/` | `casehub-desiredstate-engine` | When transition plans should become casehub-engine cases with Worker(Workflow) phases. Classpath-activated — displaces `SimpleTransitionExecutor`. |
| `work-adapter/` | `casehub-desiredstate-work` | When approval-gated nodes need WorkItem-backed approval lifecycle via casehub-work. Classpath-activated. |
| `ras-adapter/` | `casehub-desiredstate-ras` | When reconciliation faults and drift should feed into casehub-ras situation detection. Provides ganglia, situation definitions, and correlation key extraction. |
| `persistence-jpa/` | `casehub-desiredstate-persistence-jpa` | When fault counts must survive restarts. JPA-backed `FaultCountStore` with Flyway migration. Tier 2 in CDI priority ladder — yields to application-provided stores. |

---

## Key Abstractions

### DesiredStateGraph

Immutable directed acyclic graph of `DesiredNode` instances connected by `Dependency` edges. Core operations: `withNode()`, `withoutNode()`, `withDependency()`, `withMutation()`, `overlay()` (merge graphs), `connect()` (join graphs). Navigation: `dependenciesOf(nodeId)`, `dependentsOf(nodeId)`, `roots()`, `leaves()`. Versioned (`version()`) for optimistic concurrency. All mutations return new instances.

### DesiredNode

Record: `(NodeId id, NodeType type, NodeSpec spec, HumanGating humanGating)`. `NodeType` is an open string classifier (e.g. `"vm"`, `"dns-record"`, `"human-task"`, `"data-source"`) — the runtime does not constrain it. `NodeSpec` is a marker interface — each domain provides its own implementations. `HumanGating` controls per-action human routing (NONE, PROVISION_ONLY, DEPROVISION_ONLY, ALL).

### GoalCompiler\<G\>

SPI: `compile(G goals, DesiredStateGraphFactory factory) -> CompilationResult`. Translates domain-specific goals into the generic graph representation. Returns either a `SingleGraph` or a `Lifecycle` (list of phases with completion conditions). Each domain implements one.

### NodeProvisioner / ReactiveNodeProvisioner

SPIs for provisioning and deprovisioning nodes. `provision(DesiredNode, ProvisionContext) -> ProvisionResult` returns `Success`, `Failed`, or `PendingApproval`. `PendingApproval` triggers a re-entry protocol: the runtime calls `provision()` again with `context.approval()` populated after human approval. Each provisioner declares `handledTypes()` and `resyncInterval()`.

### ActualStateAdapter

SPI: `readActual(DesiredStateGraph, tenancyId) -> ActualState`. Returns a snapshot of observed status (`PRESENT`, `ABSENT`, `DEGRADED`, `UNKNOWN`) for each node. Called at the start of every reconciliation cycle. Declares `handledTypes()` for multi-adapter routing.

### TransitionExecutor

SPI: `execute(TransitionPlan, tenancyId) -> TransitionResult`. Two implementations:
- `SimpleTransitionExecutor` (`@DefaultBean`) — sequential in-process execution using `NodeProvisioner` directly. Handles `PendingApproval` re-entry and `HumanNodeHandler` delegation.
- `CaseTransitionExecutor` (engine-adapter) — translates the plan into a casehub-engine `CaseDefinition` with prune/grow worker phases and human task bindings, then starts it via `CaseHubRuntime`.

### FaultPolicy / FaultPolicyEngine

SPI: `onFault(tenancyId, FaultEvent, DesiredStateGraph, ActualState) -> List<GraphMutation>`. Called when provisioning fails, nodes drift, approvals are rejected, or human nodes time out. Policies return graph mutations that the reconciliation loop applies to the desired graph. `FaultType` enum: `NODE_DESTROYED`, `NODE_DEGRADED`, `PROVISION_FAILED`, `DEPROVISION_FAILED`, `HUMAN_NODE_TIMEOUT`, `DEPENDENCY_UNAVAILABLE`, `APPROVAL_REJECTED`.

`ThresholdFaultPolicy` is a reusable builder-configured policy in the API module — counts faults per node via `FaultCountStore` SPI and delegates to a configured `FaultPolicy` at threshold.

### GraphMutation

Sealed interface with five variants: `AddNode`, `RemoveNode`, `UpdateNode`, `AddDependency`, `RemoveDependency`. Used by fault policies and for programmatic graph modification.

### HumanNodeHandler vs PendingApprovalHandler

Two distinct human-in-the-loop patterns:
- `HumanNodeHandler` — replaces the provisioner entirely for nodes with human gating. Engine-adapter creates `HumanTaskTarget` case bindings, delegating execution to casehub-work.
- `PendingApprovalHandler` — wraps the provisioner for automated nodes that need human approval before the machine provisions. Handles `check()` (is approval already granted?), `recordPending()` (record that approval is needed), and `acknowledgeRejection()`. Work-adapter provides `WorkItemPendingApprovalHandler` (classpath-activated).

### EventSource

SPI: `stream() -> Multi<StateEvent>`. The reconciliation loop subscribes to this for event-driven triggers. `StateEvent` carries `nodeId`, `newStatus`, and optional `detail`. Multiple `EventSource` beans are merged via `MergedEventSource`.

### GlobalReconciliationListener

SPI: `onReconciliationCycleCompleted(tenancyId, DesiredStateGraph, ActualState)`. Application-scoped listener fired for all tenants on every reconciliation cycle. Use for cross-tenant analytics, auditing, and metric aggregation. Also fires `onTenantStopped(tenancyId)` during stop for cleanup.

### FaultCountStore

SPI: persistence abstraction for tracking fault counts per node. Used by `FaultPolicyEngine` and `ThresholdFaultPolicy` to enforce retry limits and backoff policies. Namespace-scoped and tenant-isolated. Three tiers:
- `InMemoryFaultCountStore` (API module) — simple in-memory map, resets on restart.
- `JpaFaultCountStore` (persistence-jpa module) — durable JPA-backed storage with Flyway migration.
- Custom app-provided store (highest priority).

CDI priority ladder: custom app store > JPA store > in-memory default.

### SituationRecompiler

SPI: `recompile(tenancyId, DesiredStateGraph, ActualState, ActiveSituation, DesiredStateGraphFactory) -> Optional<CompilationResult>`. Situation-driven graph recompilation independent of GoalCompiler. Supports priority ordering for chain-of-responsibility via `priority()`.

---

## CBR Pipeline

Case-Based Reasoning for fault and situation response. The pipeline retrieves past configurations that worked, adapts them to the current context, applies the result, and feeds outcomes back.

### CbrConfiguration

Thresholds controlling the pipeline: `minimumRetrievalConfidence`, `minimumAdaptationConfidence`, `maxCandidates`. Configurable via `DesiredStatePreferenceKeys`.

### Consumer-Facing Flow

1. **Retrieve** — `ConfigurationRetriever.retrieve(RetrievalContext, maxResults)` finds similar past configurations by fault/situation context.
2. **Adapt** — `ConfigurationAdapter.adapt(RetrievedConfiguration, RetrievalContext)` transforms retrieved config to current context.
3. **Apply** — Adapted graph is diffed against current graph; resulting `GraphMutation` list is applied by the reconciliation loop.
4. **Revise** — After execution, `CbrProposalTracker.matchOutcomes()` maps affected nodes to outcomes and emits `io.casehub.cbr.outcome` CloudEvents, closing the feedback loop.

Consumers implement `ConfigurationRetriever` and `ConfigurationAdapter` SPIs. The runtime provides `CbrFaultPolicy` (fault path) and `CbrSituationRecompiler` (situation path).

---

## Configuration

Preference keys via `DesiredStatePreferenceKeys`:
- `RESYNC_INTERVAL` — per-NodeType resync interval override
- `CBR_MIN_RETRIEVAL_CONFIDENCE` — minimum retrieval confidence for CBR pipeline
- `CBR_MIN_ADAPTATION_CONFIDENCE` — minimum adaptation confidence for CBR pipeline
- `CBR_MAX_CANDIDATES` — maximum CBR candidates to retrieve

---

## What This Repo Does NOT Do

- Persist desired-state graphs — graphs are in-memory per tenant
- Define domain-specific node types — consumers implement `NodeSpec` and `GoalCompiler`
- Schedule or time work items — that is `casehub-work` and `casehub-engine`
- Provide stream infrastructure (Kafka, AMQP) — `EventSource` is an SPI; stream adapters live elsewhere
- Multi-cluster orchestration — single-runtime reconciliation only
- Constrain `NodeType` vocabulary — open string, domain-defined
- Detect situations — that is `casehub-ras`; the ras-adapter bridges RAS detections to graph mutations
