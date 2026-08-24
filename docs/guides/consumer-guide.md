# casehub-desiredstate -- Consumer Guide

> Generic desired-state management runtime: declare what should exist, observe what does, reconcile the gap continuously.

**GitHub:** [casehubio/casehub-desiredstate](https://github.com/casehubio/casehub-desiredstate)
**Tier:** Foundation (alongside casehub-platform, casehub-ledger, casehub-work, casehub-qhorus)

---

## Purpose

Desired-state management follows the Kubernetes controller pattern: desired state is declarative, actual state is observed, and the runtime closes the gap automatically. But unlike Kubernetes, nodes can require human approval or human provisioning, and transition plans can be delegated to casehub-engine as CaseDefinitions with Serverless Workflow phases.

The framework is domain-agnostic. It provides graph management, topological transition planning, fault policy, and reconciliation orchestration. Domain-specific concerns -- what a "node" means, how to provision it, how to observe its state -- are injected via SPIs.

---

## Modules to Depend On

| Module | artifactId | When to use |
|--------|-----------|-------------|
| `api/` | `casehub-desiredstate-api` | Always. Core SPIs and domain types. Pure Java + Mutiny `provided`. No CDI, no framework. |
| `runtime/` | `casehub-desiredstate` | Always in production. CDI runtime: reconciliation loop, transition planner, fault policy engine. `@ApplicationScoped` beans. OpenTelemetry instrumented. |
| `testing/` | `casehub-desiredstate-testing` | Test scope only. Mock SPI implementations and test utilities. |
| `engine-adapter/` | `casehub-desiredstate-engine` | When transition plans should become casehub-engine cases with Worker(Workflow) phases. Classpath-activated -- displaces `SimpleTransitionExecutor`. |
| `work-adapter/` | `casehub-desiredstate-work` | When approval-gated nodes need WorkItem-backed approval lifecycle via casehub-work. Classpath-activated. |
| `ras-adapter/` | `casehub-desiredstate-ras` | When reconciliation faults and drift should feed into casehub-ras situation detection. Provides ganglia, situation definitions, and correlation key extraction. |
| `persistence-jpa/` | `casehub-desiredstate-persistence-jpa` | When fault counts must survive restarts. JPA-backed `FaultCountStore` with Flyway migration. Tier 2 in CDI priority ladder -- yields to application-provided stores. |
| `annotations/runtime/` | `casehub-desiredstate-annotations` | Annotation-driven graph declarations. Two models: interface (`@DesiredState` + `@Node`) for centralized graphs, class-based (`@DeclareNode`) for cross-module composition. `@DependsOn` supports string IDs and type-safe `Class<? extends NodeSpec>[]` refs. Also: `@FaultPolicyDef`, `@GoalMethod`. The build extension (deployment module, auto-activated) scans annotations and generates `GoalCompiler` + `ThresholdFaultPolicy` CDI beans. |

---

## Key Abstractions

### DesiredStateGraph

Immutable directed acyclic graph of `DesiredNode` instances connected by `Dependency` edges. All mutations return new instances. Versioned (`version()`) for optimistic concurrency.

**Core operations:**
- `withNode(DesiredNode)`, `withoutNode(NodeId)` -- add/remove nodes
- `withDependency(Dependency)`, `withoutDependency(Dependency)` -- add/remove edges
- `withMutation(GraphMutation)` -- apply a single mutation
- `overlay(DesiredStateGraph)` -- merge two graphs (union; shared nodes must be equal)
- `connect(DesiredStateGraph)` -- join graphs (all leaves of this -> all roots of other)
- `filterByTypes(Set<NodeType>)` -- subtractive filter, removes nodes not matching types

**Navigation:**
- `nodes()` -- all nodes as `Map<NodeId, DesiredNode>`
- `dependencies()` -- all edges as `Set<Dependency>`
- `dependenciesOf(NodeId)` -- direct dependencies of a node
- `dependentsOf(NodeId)` -- nodes that depend on a node
- `roots()` -- nodes with no dependencies
- `leaves()` -- nodes with no dependents
- `isEmpty()`, `version()`

**Edge convention:** `Dependency(from, to)` means "from depends on to." The `to` node must be provisioned before `from`.

### DesiredNode

Record: `(NodeId id, NodeType type, NodeSpec spec, HumanGating humanGating)`. All fields non-null.

- `NodeType` is an open string classifier (e.g. `"vm"`, `"dns-record"`, `"data-source"`) -- the runtime does not constrain it.
- `NodeSpec` is a marker interface -- each domain provides its own implementations. `NodeSpec.humanGating()` returns `HumanGating.NONE` by default; domains can override for type-level gating.
- `HumanGating` controls per-action human routing: `NONE`, `PROVISION_ONLY`, `DEPROVISION_ONLY`, `ALL`. Merge semantics: per-action OR between node-level and spec-level gating.
- `requiresHuman(StepAction)` checks both node-level and spec-level gating for a specific action.
- `requiresHuman()` returns true if any action requires human routing.

### GoalCompiler\<G\>

SPI: `compile(G goals, DesiredStateGraphFactory factory) -> CompilationResult`. Translates domain-specific goals into the generic graph representation. Returns either a `SingleGraph(DesiredStateGraph)` or a `Lifecycle(List<Phase>)` for multi-phase deployments. Each domain implements one.

### CompilationResult

Sealed interface with two variants:
- `SingleGraph(DesiredStateGraph)` -- static desired state
- `Lifecycle(List<Phase>)` -- multi-phase transitions; each `Phase` has an `id`, `graph`, and `CompletionCondition`

Factory methods: `CompilationResult.single(graph)`, `CompilationResult.lifecycle(phases)`.

### NodeProvisioner

SPI for provisioning and deprovisioning nodes.

```java
Set<NodeType> handledTypes();          // which types this provisioner handles
Duration resyncInterval();             // periodic reconciliation interval (default: 5 min)
ProvisionResult provision(DesiredNode, ProvisionContext);
DeprovisionResult deprovision(DesiredNode, DeprovisionContext);
```

**Provision results** (sealed): `Success`, `Failed(reason)`, `PendingApproval(nodeId, planReference)`.
**Deprovision results** (sealed): `Success`, `Failed(reason)`, `PendingApproval(nodeId, planReference)`.

`PendingApproval` triggers a re-entry protocol: the runtime calls `provision()` again with `context.approval()` populated after human approval. Provisioners should check `context.hasApproval()` and proceed with the approved plan, or return a new `PendingApproval` if the plan is stale. The `planReference` is opaque to the runtime -- round-tripped unchanged.

Each provisioner declares `handledTypes()`. The runtime routes calls by `NodeType` via `NodeProvisionerRouter`. Overlapping types across provisioners cause construction-time failure.

### ActualStateAdapter

SPI: `readActual(DesiredStateGraph desired, String tenancyId) -> ActualState`. Returns a snapshot of observed status for each node. Called at the start of every reconciliation cycle. Declares `handledTypes()` for multi-adapter routing.

**NodeStatus enum:** `PRESENT` (matches spec), `ABSENT` (does not exist), `DRIFTED` (exists but diverged from spec), `UNKNOWN` (status could not be determined).

### TransitionExecutor

SPI: `execute(TransitionPlan plan, String tenancyId) -> TransitionResult`. Two implementations:
- `SimpleTransitionExecutor` (`@DefaultBean`) -- sequential in-process execution using `NodeProvisioner` directly. Handles `PendingApproval` re-entry and `HumanNodeHandler` delegation. Precedence per action: humanGating > PendingApproval > provisioner.
- `CaseTransitionExecutor` (engine-adapter) -- translates the plan into a casehub-engine `CaseDefinition` with prune/grow Worker(Workflow) phases and `HumanTaskTarget` bindings, then starts it via `CaseHubRuntime`.

### FaultPolicy / FaultPolicyEngine

SPI: `onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) -> List<GraphMutation>`. Called when provisioning fails, nodes drift, approvals are rejected, or human nodes time out. Policies return graph mutations that the reconciliation loop applies to the desired graph.

**FaultType enum:** `NODE_DESTROYED`, `NODE_DEGRADED`, `PROVISION_FAILED`, `DEPROVISION_FAILED`, `HUMAN_NODE_TIMEOUT`, `DEPENDENCY_UNAVAILABLE`, `APPROVAL_REJECTED`.

Static factory: `FaultPolicy.addReviewNode(ReviewSpecFactory) → TypedFaultPolicy` -- creates a review node with dependency edge to the faulted node, `HumanGating.ALL`, and ID derived from `ReviewSpec.nodeType().value()`. Returns `TypedFaultPolicy` with eagerly-captured `outputNodeType()`. Runtime consistency assertion guards probe-vs-actual NodeType mismatch (e.g. `NodeType.of("ai-review")` produces `"ai-review-n1"`).

`FaultPolicyEngine` discovers all `FaultPolicy` beans via CDI, runs all matching, merges mutations, and detects conflicts (`ConflictingMutationException`).

### ThresholdFaultPolicy

Reusable `FaultPolicy` in the API module -- counts faults per node via pluggable `FaultCountStore` SPI. Supports multi-tier escalation with graph-presence guards. Builder-configured:

```java
ThresholdFaultPolicy.builder()
    .faultTypes(Set.of(FaultType.PROVISION_FAILED))
    .nodeTypes(Set.of(NodeType.of("compute")))    // optional filter
    .tier(4, addReviewNode(aiSpec))
    .tier(7, addReviewNode(humanSpec))
    .faultCountStore(store)                         // optional; defaults to InMemoryFaultCountStore
    .namespace("provision-escalation")              // required when custom store provided
    .build();
```

Tier nodeTypes are auto-merged into `ignoreTypes`. Evaluation is highest-tier-first, first-match-wins. Tier N+1 fires only if a dependent of the faulted node with tier N's `NodeType` exists in the graph (graph-presence guard via `dependentsOf()`). `resetCount(tenancyId, nodeId)` for external recovery-reset. Lazy eviction on fault for removed nodes.

### GraphMutation

Sealed interface with five variants: `AddNode(DesiredNode)`, `RemoveNode(NodeId)`, `UpdateNode(NodeId, DesiredNode)`, `AddDependency(Dependency)`, `RemoveDependency(Dependency)`.

### GraphMutations

Static utility: `GraphMutations.addNodeDependingOn(DesiredNode, NodeId)` returns `[AddNode, AddDependency]` -- the common pattern for adding a node with a dependency edge to an existing node.

### HumanNodeHandler

SPI for human-gated nodes -- replaces the provisioner entirely when `node.requiresHuman(action)` is true.

```java
StepOutcome onProvision(DesiredNode node, ProvisionContext context);
default StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context);  // default: Skipped
```

`NoOpHumanNodeHandler` (`@DefaultBean`) skips the node for both actions -- misconfiguration signal. Human nodes that need lifecycle management require `CaseTransitionExecutor` (engine-adapter), which creates `HumanTaskTarget` case bindings delegating to casehub-work.

### PendingApprovalHandler

SPI for provisioner-initiated approval gates -- wraps the provisioner for automated nodes that need human approval before the machine provisions.

```java
ApprovalCheckResult check(DesiredNode, StepAction, String tenancyId);
StepOutcome recordPending(DesiredNode, StepAction, String tenancyId, String planReference);
void acknowledgeRejection(DesiredNode, StepAction, String tenancyId);
```

**ApprovalCheckResult** (sealed): `None`, `Pending(planReference)`, `Approved(PlanApproval)`, `Rejected(planReference, reason)`.

`NoOpPendingApprovalHandler` (`@DefaultBean`) returns `Failed` on `recordPending()` -- misconfiguration signal when no handler is configured to create WorkItems. `WorkItemPendingApprovalHandler` (work-adapter, classpath-activated) creates WorkItems and polls each cycle.

**Contrast with HumanNodeHandler:** `PendingApprovalHandler` wraps the provisioner (approval before machine action). `HumanNodeHandler` replaces the provisioner (human does the action).

### EventSource

SPI: `stream() -> Multi<StateEvent>`. The reconciliation loop subscribes to this for event-driven triggers. `StateEvent` carries `nodeId`, `newStatus`, and optional `detail`. Multiple `EventSource` beans are merged via `MergedEventSource` with per-stream error isolation.

### ReconciliationListener

`@FunctionalInterface`: `onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual)`. Per-tenant post-cycle callback. Used by `LifecycleManager` for phase completion checks. Consumers can provide a listener when starting the reconciliation loop.

### GlobalReconciliationListener

SPI: CDI-discovered application-scoped listener fired for all tenants on every full reconciliation cycle (not type-filtered cycles). Two methods:
- `onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual)`
- `default onTenantStopped(String tenancyId)` -- fires during stop for cleanup

Use for cross-tenant analytics, auditing, metric aggregation, and fault count eviction.

### FaultCountStore

SPI: persistence abstraction for tracking fault counts per node. Used by `ThresholdFaultPolicy` to enforce retry limits. Namespace-scoped and tenant-isolated.

```java
int incrementAndGet(String namespace, String tenancyId, NodeId nodeId);
int getCount(String namespace, String tenancyId, NodeId nodeId);
void reset(String namespace, String tenancyId, NodeId nodeId);
void remove(String namespace, String tenancyId, NodeId nodeId);
void evict(String namespace, String tenancyId, Set<NodeId> retainedNodes);
void evictAcrossNamespaces(String tenancyId, Set<NodeId> retainedNodes);
```

Three tiers (CDI priority ladder: custom app store > JPA store > in-memory default):
- `InMemoryFaultCountStore` (API module) -- `ConcurrentHashMap` with composite key. Thread-safe. Used as builder default in `ThresholdFaultPolicy`, not CDI-managed.
- `DefaultFaultCountStore` (runtime module) -- `@DefaultBean @ApplicationScoped` CDI fallback wrapping `InMemoryFaultCountStore`. Yields to JPA store when persistence-jpa is on classpath.
- `JpaFaultCountStore` (persistence-jpa module) -- JPA-backed durable storage with Flyway migration. `FaultCountEntity` with composite key `(namespace, tenancy_id, node_id)`.

`FaultCountEvictionListener` (runtime module) -- `@ApplicationScoped` `GlobalReconciliationListener` that calls `evictAcrossNamespaces` after each cycle and on tenant stop, removing stale counts for nodes no longer in the graph.

### SituationRecompiler

SPI: `recompile(String tenancyId, DesiredStateGraph current, ActualState actual, ActiveSituation situation, DesiredStateGraphFactory factory) -> Optional<CompilationResult>`. Situation-driven graph recompilation independent of GoalCompiler. Supports priority ordering for chain-of-responsibility via `priority()` (ascending; default 0).

Multiple recompilers are aggregated by `SituationRecompilerEngine` -- the engine tries each in priority order until one returns a non-empty result.

### ConfigurationRetriever / ConfigurationAdapter (CBR SPIs)

Case-Based Reasoning SPIs for fault and situation response:
- `ConfigurationRetriever`: `retrieve(RetrievalContext context, int maxResults) -> List<RetrievedConfiguration>` -- finds similar past configurations.
- `ConfigurationAdapter`: `adapt(RetrievedConfiguration retrieved, RetrievalContext context) -> Optional<AdaptedConfiguration>` -- transforms retrieved config to current context.

Runtime provides `NoOpConfigurationRetriever` and `NoOpConfigurationAdapter` as `@DefaultBean` fallbacks.

### StepOutcome

Sealed interface -- per-node execution outcome: `Succeeded`, `Failed(reason)`, `Skipped(reason)`, `Rejected(reason)`. All reason fields are non-null.

---

## CBR Pipeline

Case-Based Reasoning for fault and situation response. The pipeline retrieves past configurations that worked, adapts them to the current context, applies the result, and feeds outcomes back.

### CbrConfiguration

Thresholds controlling the pipeline: `minimumRetrievalConfidence`, `minimumAdaptationConfidence`, `maxCandidates`. Configurable via `DesiredStatePreferenceKeys` in the runtime module.

### Consumer-Facing Flow

1. **Retrieve** -- `ConfigurationRetriever.retrieve(RetrievalContext, maxResults)` finds similar past configurations by fault/situation context.
2. **Adapt** -- `ConfigurationAdapter.adapt(RetrievedConfiguration, RetrievalContext)` transforms retrieved config to current context.
3. **Apply** -- Adapted graph is diffed against current graph; resulting `GraphMutation` list is applied by the reconciliation loop.
4. **Revise** -- After execution, `CbrProposalTracker.matchOutcomes()` maps affected nodes to outcomes (SUCCEEDED, FAILED, SKIPPED, REJECTED, SUPERSEDED, ALREADY_PRESENT), computes success rate, and emits `io.casehub.cbr.outcome` CloudEvents, closing the feedback loop.

Consumers implement `ConfigurationRetriever` and `ConfigurationAdapter` SPIs. The runtime provides `CbrFaultPolicy` (fault path) and `CbrSituationRecompiler` (situation path at `priority() = Integer.MAX_VALUE` -- fallback position).

---

## Lifecycle Management

For multi-phase desired-state transitions without re-invoking `GoalCompiler`:

1. `GoalCompiler.compile()` returns `CompilationResult.Lifecycle(List<Phase>)`.
2. Each `Phase` has an `id`, `graph`, and `CompletionCondition`.
3. `LifecycleManager` (`@ApplicationScoped`) starts reconciliation on the first phase graph.
4. After each cycle, `CompletionCondition.isComplete(desired, actual)` is evaluated.
5. On completion, `LifecycleManager` uses `compareAndSetDesired()` to atomically advance to the next phase graph.
6. Fault-triggered replanning via `SituationRecompiler` can return a new `CompilationResult.Lifecycle` -- lifecycle state resets to the new sequence.

**CompletionCondition** SPI with built-ins: `allPresent()` (all nodes PRESENT), `never()` (terminal phase).

---

## Testing Module

`casehub-desiredstate-testing` (test scope) provides mock SPIs and test utilities:

| Class | Purpose |
|-------|---------|
| `MockNodeProvisioner` | Configurable success/failure per node, declares `handledTypes()` |
| `MockActualStateAdapter` | Configurable per-node status returns |
| `MockPendingApprovalHandler` | Configurable approval check results |
| `MockTransitionExecutor` | Controllable transition execution for testing lifecycle/loop behaviour |
| `MockConfigurationRetriever` | Configurable CBR retrieval results |
| `MockConfigurationAdapter` | Configurable CBR adaptation results |
| `CannedEventSource` | Push events into the reconciliation loop deterministically |
| `TestTimeouts` | Standardised timeout constants for condition-based test waiting |

---

## Configuration

Preference keys via `DesiredStatePreferenceKeys` (runtime module):
- `RESYNC_INTERVAL` -- per-NodeType resync interval override (default: 5 minutes)
- `CBR_MIN_RETRIEVAL_CONFIDENCE` -- minimum retrieval confidence for CBR pipeline (default: 0.5)
- `CBR_MIN_ADAPTATION_CONFIDENCE` -- minimum adaptation confidence for CBR pipeline (default: 0.6)
- `CBR_MAX_CANDIDATES` -- maximum CBR candidates to retrieve (default: 3)

---

## CloudEvent Types

The runtime emits CloudEvents during reconciliation:

| Type URI | Data class | When emitted |
|----------|-----------|--------------|
| `io.casehub.desiredstate.reconciliation.completed` | `ReconciliationCompletedData` | After each reconciliation cycle |
| `io.casehub.desiredstate.node.faulted` | `NodeFaultedData` | Per-node provisioning failure |
| `io.casehub.desiredstate.node.drifted` | `NodeDriftedData` | Per-node drift detection |
| `io.casehub.desiredstate.node.recovered` | `NodeRecoveredData` | Per-node recovery (DRIFTED -> PRESENT) |
| `io.casehub.cbr.outcome` | `CbrOutcomeData` | CBR proposal outcome feedback |

---

## What This Repo Does NOT Do

- Persist desired-state graphs -- graphs are in-memory per tenant
- Define domain-specific node types -- consumers implement `NodeSpec` and `GoalCompiler`
- Schedule or time work items -- that is `casehub-work` and `casehub-engine`
- Provide stream infrastructure (Kafka, AMQP) -- `EventSource` is an SPI; stream adapters live elsewhere
- Multi-cluster orchestration -- single-runtime reconciliation only
- Constrain `NodeType` vocabulary -- open string, domain-defined
- Detect situations -- that is `casehub-ras`; the ras-adapter bridges RAS detections to graph mutations
