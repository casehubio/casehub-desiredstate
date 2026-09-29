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
| `yaml/runtime/` | `casehub-desiredstate-yaml` | YAML-driven graph declarations. Declare desired-state graphs in `META-INF/desiredstate/*.yaml` files. Jackson-based deserializer with `NodeSpecRegistry` (maps YAML `type:` strings to `@NodeTypeId`-annotated `NodeSpec` classes), variable resolution via `casehub-yaml-core` (`VariableResolver` with deferred prefixes), reusable YAML modules with parameter constraints (`minLength`, `maxLength`, `pattern`, `minimum`, `maximum`, `allowedValues`), module extension (`extends`), typed outputs, and cross-module references (`${module.alias.output}`). Modules are discovered at `META-INF/desiredstate/modules/*.yaml`. Build-time validation (unknown types, dangling dependencies, cycles, parameter validation). The build extension (deployment module, auto-activated) discovers YAML files and generates `GoalCompiler<Void>` CDI beans qualified by namespace + name. |
| `plugin/api/` | `casehub-desiredstate-plugin-api` | Step pipeline SPI for YAML-declared plugins. Depend on this to implement custom `StepPrimitive` beans (named operations invocable from plugin YAML). Pure Java. |
| `plugin/runtime/` | `casehub-desiredstate-plugin` | YAML plugin runtime. Add this to enable `META-INF/desiredstate/plugins/*.yaml` — each plugin file declares a complete self-healing resource type (spec schema, actual-state detection, provisioning steps, fault policies, CBR features, RAS situations) without Java. The build extension (deployment module, auto-activated) validates plugins exhaustively at build time. Built-in step primitives: `rest-call`, `json-extract`, `compare-state`, `assert`. Compound YAML primitives compose other primitives hierarchically. |
| `annotations/runtime/` | `casehub-desiredstate-annotations` | Annotation-driven graph declarations. Two models: interface (`@DesiredState` + `@Node`) for centralized graphs, class-based (`@DeclareNode`) for cross-module composition. `@DependsOn` supports string IDs and type-safe `Class<? extends NodeSpec>[]` refs. Graph rewriting via `@GraphRule` (parameterized pattern matching with `@Match`, `@DirectDep`, `@Reaches`, `@NotExists` or imperative with full `DesiredStateGraph` access). Graph validation via `@GraphInvariant` (same pattern vocabulary, universal quantification — fires after rules converge). Standalone rule/invariant containers: `@GraphRule(graph = {"pipeline:*"})` classes with include/exclude matching (`!` prefix for exclusions). `@Tier(nodeType)` eliminates runtime `ReviewSpecFactory` probe. Also: `@FaultPolicyDef`, `@GoalMethod`. The build extension (deployment module, auto-activated) scans annotations and generates `GoalCompiler` + `ThresholdFaultPolicy` CDI beans. |

---

## Key Abstractions

### DesiredStateGraph

Immutable directed acyclic graph of `DesiredNode` instances connected by `Dependency` edges. All mutations return new instances. Versioned (`version()`) for optimistic concurrency.

**Core operations:**
- `withNode(DesiredNode)`, `withoutNode(NodeId)` -- add/remove nodes
- `withDependency(Dependency)`, `withoutDependency(Dependency)` -- add/remove edges
- `withMutation(GraphMutation<DesiredNode>)` -- apply a single mutation
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

SPI: `execute(TransitionPlan plan, String tenancyId) -> TransitionResult`. Three implementations:
- `SimpleTransitionExecutor` (`@DefaultBean`) -- sequential in-process execution using `NodeProvisioner` directly. Handles `PendingApproval` re-entry and `HumanNodeHandler` delegation. Precedence per action: humanGating > PendingApproval > provisioner.
- `ParallelTransitionExecutor` -- layer-based concurrent provisioning via virtual threads. Independent nodes (same topological layer) provision concurrently; dependencies between layers are respected. Per-NodeType `Semaphore` rate limiting from `NodeProvisioner.maxConcurrency()`. Failure in one layer propagates to dependents in subsequent layers. Activated via `PARALLEL_EXECUTION` BooleanPreference.
- `CaseTransitionExecutor` (engine-adapter) -- translates the plan into a casehub-engine `CaseDefinition` with prune/grow Worker(Workflow) phases and `HumanTaskTarget` bindings, then starts it via `CaseHubRuntime`.

### FaultPolicy / FaultPolicyEngine

SPI: `onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) -> List<GraphMutation<DesiredNode>>`. Called when provisioning fails, nodes drift, approvals are rejected, or human nodes time out. Policies return graph mutations that the reconciliation loop applies to the desired graph.

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

Sealed generic interface `GraphMutation<N>` with five variants: `AddNode<N>(String id, N node)`, `RemoveNode<N>(String id)`, `UpdateNode<N>(String id, N adaptedNode)`, `AddEdge<N>(String from, String to)`, `RemoveEdge<N>(String from, String to)`. All desiredstate consumers use `GraphMutation<DesiredNode>`. Variant renames: `AddDependency` → `AddEdge`, `RemoveDependency` → `RemoveEdge`.

### GraphMutations

Static utility: `GraphMutations.addNodeDependingOn(DesiredNode, NodeId)` returns `List<GraphMutation<DesiredNode>>` (`[AddNode, AddEdge]`) -- the common pattern for adding a node with a dependency edge to an existing node.

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

### ReconciliationStateStore

SPI: persistence abstraction for the last-reconciled desired graph per tenant. Used by `TransitionPlanner` to resolve orphan node specs during deprovisioning -- when a node is removed from the desired graph, the planner retrieves the original `DesiredNode` (with real spec, type, humanGating) from the stored previous graph.

```java
void store(String tenancyId, DesiredStateGraph lastReconciledDesired);
Optional<DesiredStateGraph> load(String tenancyId);
void remove(String tenancyId);
```

Three tiers (CDI priority ladder: JPA store > in-memory default):
- `InMemoryReconciliationStateStore` (API module) -- `ConcurrentHashMap` with `tenancyId` key. Thread-safe. Lost on restart.
- `DefaultReconciliationStateStore` (runtime module) -- `@DefaultBean @ApplicationScoped` CDI fallback wrapping `InMemoryReconciliationStateStore`.
- `JpaReconciliationStateStore` (persistence-jpa module) -- `@ApplicationScoped` JPA-backed store. Serializes the full `DesiredStateGraph` as JSON per tenant. Classpath-activated -- add `casehub-desiredstate-persistence-jpa` as a dependency to enable durable orphan resolution across restarts.

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

## Node Lifecycle State Machines

Provisioners can declare per-NodeType lifecycle state machines via `NodeLifecycleDefinition` beans. When present, provisioners are automatically wrapped with `StatefulNodeProvisioner`, enforcing valid state transitions (e.g. `ABSENT → PROVISIONING → PRESENT`) and firing custom events on state entry/exit.

### Lifecycle Definition

```java
@Produces
@ApplicationScoped
NodeLifecycleDefinition vmLifecycle() {
    return new NodeLifecycleDefinition(NodeType.of("cloud-vm"),
        Set.of(
            new Transition(ABSENT, PROVISIONING),
            new Transition(PROVISIONING, PRESENT),
            new Transition(PROVISIONING, DRIFTED),
            new Transition(PRESENT, DEPROVISIONING),
            new Transition(DEPROVISIONING, ABSENT),
            new Transition(PRESENT, SUSPENDING),
            new Transition(SUSPENDING, SUSPENDED),
            new Transition(SUSPENDED, RESUMING),
            new Transition(RESUMING, PRESENT)
        ),
        Map.of(PRESENT, List.of(new TransitionAction.EmitEvent("vm.ready"))),
        Map.of());
}
```

### How It Works

1. The runtime matches `NodeLifecycleDefinition` beans to `NodeProvisioner` beans by `NodeType`.
2. Matching provisioners are wrapped with `StatefulNodeProvisioner`, which maintains a per-node `OrcStateMachine<NodeLifecycleState>` (from yaml-core).
3. Each provision/deprovision/suspend/resume call validates the transition against the state machine before delegating.
4. On success, the state machine transitions to the target state and fires `onEnter`/`onExit` actions.
5. On failure, the state machine transitions to a recovery state (e.g. `PROVISIONING → DRIFTED`) if defined.
6. Invalid transitions return `Failed` without calling the delegate provisioner.

### CloudEvents

Custom events declared in `onEnter`/`onExit` are emitted as `io.casehub.desiredstate.lifecycle.state-entered` / `state-exited` CloudEvents with the custom event type in the `customEventType` extension.

---

## Cross-Domain Composition

When multiple domains share one classpath (e.g. infra, deployment, compliance, IoT), the `CrossDomainCompositionEngine` merges their graphs with correct ordering. Each domain compiles its own goals (preserving `GoalCompiler<G>` type safety) and registers the result with the engine.

### Push-Model Registration

```java
@ApplicationScoped
public class InfraDomainRegistrar {
    @Inject InfraGoalCompiler compiler;
    @Inject CrossDomainCompositionEngine engine;

    void onStartup(@Observes StartupEvent event) {
        CompilationResult result = compiler.compile(goals, graphFactory);
        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("infra"), result)
            .provides(Set.of(NodeTypes.K8S_NAMESPACE, NodeTypes.DATABASE_CLUSTER))
            .build());
    }
}

@ApplicationScoped
public class DeploymentDomainRegistrar {
    @Inject DeploymentGoalCompiler compiler;
    @Inject CrossDomainCompositionEngine engine;

    void onStartup(@Observes StartupEvent event) {
        CompilationResult result = compiler.compile(goals, graphFactory);
        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("deployment"), result)
            .provides(Set.of(NodeTypes.AGENT, NodeTypes.CHANNEL))
            .requires(Set.of(NodeTypes.K8S_NAMESPACE))
            .build());
    }
}
```

**`provides`** declares which `NodeType`s a domain contributes. **`requires`** declares which `NodeType`s from other domains must exist before this domain's nodes can be provisioned. The engine creates cross-domain edges from the requiring domain's root nodes to the providing domain's typed nodes -- `TransitionPlanner` handles ordering from there.

### Composition Modes

| Mode | When | Behaviour |
|------|------|-----------|
| **Flattened** (default) | 2+ registrations | All domain graphs merged via `overlay()` into one `DesiredStateGraph` with cross-domain edges. Single `ReconciliationLoop`. |
| **Hierarchical** | Explicit config | Meta-loop with one domain-level node per domain. Inner `ReconciliationLoop` per domain. `CompletionCondition` gates downstream domains. |
| **Single-domain** | 0 or 1 registration | Passthrough -- zero composition overhead. |

### Startup Validation

The engine validates at startup: duplicate `provides` (fail-fast), unsatisfied `requires` (fail-fast), circular domain dependencies (Kahn's algorithm), and node ID collisions across domains (identical specs allowed, differing specs fail-fast).

### Per-Domain Lifecycle

Each domain can return `CompilationResult.Lifecycle` with multiple phases. The engine tracks per-domain phase state and recomposes on phase transitions. Domain-specific `SituationRecompiler`s receive only their domain graph (not the composed graph), preserving domain isolation.

### Backward Compatibility

Existing single-domain apps are unchanged. The engine is inert with zero or one registrations. Apps calling `ReconciliationLoop.start()` or `LifecycleManager.start()` directly continue to work.

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
- `PARALLEL_EXECUTION` -- `BooleanPreference`: when true, `ParallelTransitionExecutor` replaces `SimpleTransitionExecutor` for layer-based concurrent provisioning (default: false)
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
| `io.casehub.desiredstate.lifecycle.state-entered` | `LifecycleStateEnteredData` | Node lifecycle state entered (with optional custom event type) |
| `io.casehub.desiredstate.lifecycle.state-exited` | `LifecycleStateExitedData` | Node lifecycle state exited |
| `io.casehub.cbr.outcome` | `CbrOutcomeData` | CBR proposal outcome feedback |

---

## Cardinality Constraints

`@Match`, `@DirectDep`, and `@Reaches` annotations support `minCount`/`maxCount` cardinality fields. `PatternParameterDescriptor` carries cardinality metadata. `GraphInvariantEngine` validates both match-level and expansion-level cardinality at build time. YAML patterns support `minCount`/`maxCount` via `YamlPattern` converter + validation.

## TypeScript SDK (ts-core)

`TsGraphRecorder` enables graph definitions in TypeScript via `defineGraph()`, `defineLifecycle()`, and `node()` helpers. Produces a JSON envelope consumed by `GoalCompiler`. `TsDesiredStateProcessor` processes TypeScript graphs with cross-surface filter broadening. Cross-surface `@GraphRule` annotations apply rules across Java and TypeScript graph surfaces.

## YAML Lifecycle Hooks

`verify`, `notify`, and `wait` hooks are available in YAML lifecycle definitions for declarative lifecycle management — verify a condition, send a notification, or wait for a duration/event before proceeding.

---

## Spring Boot Integration

The desiredstate runtime can be embedded in Spring Boot applications via auto-configuration modules. Each module uses `@AutoConfiguration` + `@ConditionalOnMissingBean` — add the dependency and the beans are registered automatically.

### Maven Dependencies

```xml
<!-- Core runtime (required) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-runtime-spring</artifactId>
</dependency>

<!-- Annotation-driven graphs (@DesiredState, @Node, @GraphRule) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-annotations-spring</artifactId>
</dependency>

<!-- YAML-driven graphs (META-INF/desiredstate/*.yaml) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-yaml-spring</artifactId>
</dependency>

<!-- TypeScript DSL graphs (META-INF/desiredstate/*.ds.json) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-ts-spring</artifactId>
</dependency>

<!-- Durable fault counts + reconciliation state via JPA -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-persistence-spring-jpa</artifactId>
</dependency>
```

### How It Works

**Annotation scanning:** `annotations-spring` reads `META-INF/jandex.idx` from all classpath JARs at startup. Classes annotated with `@DesiredState`, `@DeclareNode`, `@GraphRule`, `@GraphInvariant`, and `@FaultPolicyDef` are discovered and registered as `GoalCompiler` and `ThresholdFaultPolicy` beans — same behaviour as the Quarkus build extension, but at startup rather than build time.

**YAML graphs:** `yaml-spring` discovers `META-INF/desiredstate/*.yaml` and `META-INF/desiredstate/modules/*.yaml` on the classpath using Spring's `PathMatchingResourcePatternResolver`. `@NodeTypeId` classes are scanned from Jandex indexes. Each YAML graph file becomes a `GoalCompiler` bean.

**Persistence:** `persistence-spring-jpa` provides `FaultCountStore` and `ReconciliationStateStore` implementations backed by JPA. Uses Spring `@Transactional` with constructor-injected `EntityManager`. Requires a `DataSource` bean and Flyway migrations at `classpath:db/desiredstate/migration/`.

**SPI fallbacks:** `runtime-spring` registers `@ConditionalOnMissingBean` defaults for `FaultCountStore` (in-memory), `ReconciliationStateStore` (in-memory), `HumanNodeHandler` (no-op), and `PendingApprovalHandler` (no-op). Application-provided beans take precedence.

---

## Testing Plugins

`casehub-desiredstate-plugin-testing` provides a declarative test framework for YAML plugins. Plugin authors write `*.test.yaml` files alongside their plugin definitions — no Java authoring required.

### Minimal Test Class

```java
class MyPluginTest {
    @RegisterExtension
    static PluginTestExtension ext = PluginTestExtension.forPlugin("my-plugin");

    @TestFactory
    Stream<DynamicTest> tests() { return ext.discoverTests(); }
}
```

### Test YAML Format

Place test files at `src/test/resources/META-INF/desiredstate/tests/<plugin-name>.test.yaml`:

```yaml
plugin: my-plugin
infrastructure: http-mock   # or: shell-sandbox

setup:
  stubs:                     # WireMock stubs shared across all tests
    - request: { method: GET, path: /auth }
      response: { status: 200, body: { token: test } }
  variables:
    auth:
      api: { endpoint: "${wiremock.url}", token: test-token }

tests:
  - name: provision creates resource
    spec: { name: my-resource }
    expectations:
      - request: { method: POST, path: /api/resources }
        response: { status: 201 }
    action: provision
    assert:
      provision: success

  - name: actual state is PRESENT
    spec: { name: my-resource }
    action: actual-state
    assert:
      actual-state: PRESENT

  - name: fault injection after 3 failures
    spec: { name: my-resource }
    fault-injection:
      action: provision
      fail-count: 3
      error: "Connection refused"
    assert:
      provision: failed
      error-matches: "Connection refused"
```

### Infrastructure Types

| Type | What it provides | Use for |
|------|-----------------|---------|
| `http-mock` | Embedded WireMock server | REST API plugins (most common) |
| `shell-sandbox` | Temp directory with `${sandbox.dir}` | File-system / CLI plugins |

### Assertions

| Key | Values | Notes |
|-----|--------|-------|
| `provision` | `success`, `failed` | Matches `ProvisionResult` type |
| `deprovision` | `success`, `failed` | Matches `DeprovisionResult` type |
| `actual-state` | `PRESENT`, `ABSENT`, `DRIFTED`, `UNKNOWN` | Matches `NodeStatus` |
| `error-matches` | substring or regex | Matches failure message |
| `file-exists` | path | Shell-sandbox only |
| `file-absent` | path | Shell-sandbox only |

### Maven Dependency

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-plugin-testing</artifactId>
    <version>${casehub.version}</version>
    <scope>test</scope>
</dependency>
```

---

## What This Repo Does NOT Do

- Persist desired-state graphs -- graphs are in-memory per tenant
- Define domain-specific node types -- consumers implement `NodeSpec` and `GoalCompiler` in Java, or declare types via plugin YAML (`META-INF/desiredstate/plugins/*.yaml`) for REST-based resources
- Schedule or time work items -- that is `casehub-work` and `casehub-engine`
- Provide stream infrastructure (Kafka, AMQP) -- `EventSource` is an SPI; stream adapters live elsewhere
- Multi-cluster orchestration -- single-runtime reconciliation only
- Constrain `NodeType` vocabulary -- open string, domain-defined
- Detect situations -- that is `casehub-ras`; the ras-adapter bridges RAS detections to graph mutations
