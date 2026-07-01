# Type-Aware Runtime — Multi-Provisioner Dispatch + Per-Type Reconciliation Scheduling

**Date:** 2026-07-01
**Issues:** #18 (multi-provisioner dispatch), #19 (reconciliation scheduling)
**Status:** Implemented

## Problem

The desired-state runtime is type-blind. Every `DesiredNode` carries a `NodeType` — defined
as *"classifies a node by its provisioning domain"* — but the runtime ignores it entirely.

This creates two problems:

**1. Every domain reinvents provisioner dispatch (#18)**

`SimpleTransitionExecutor` and `DesiredStateDispatch` inject a single `NodeProvisioner`.
Every domain works around this by building monolithic provisioners with internal routing:

- `GoblinProvisioner` — if/else chain on `NodeType`
- `PipelineProvisioner` — if/else chain + secondary `ExecutionBackend.handles()` dispatch
- `DeploymentNodeProvisioner` — sealed spec switch + per-type handler delegation
- `InfraNodeProvisioner` — `Map<String, InfraBackend>` keyed by backend name
- `IoTNodeProvisioner` — `Map<String, DeviceProvider>` keyed by provider ID

casehub-ops has four separate `@ApplicationScoped NodeProvisioner` beans
(`DeploymentNodeProvisioner`, `InfraNodeProvisioner`, `ComplianceNodeProvisioner`,
`IoTNodeProvisioner`). These cannot co-exist on the same classpath without CDI ambiguity.
There is no composite provisioner, no coordinator — the single-provisioner contract forces
single-domain deployments.

**2. One-size-fits-all reconciliation scheduling (#19)**

`ReconciliationLoop` runs one fixed 5-minute resync interval for all nodes in a tenant's
graph. IoT devices that change every few seconds get the same polling frequency as
infrastructure that changes once a month. Domains cannot declare their scheduling needs —
and the issue explicitly states *"domains should not implement their own timers."*

**Root cause:** Both problems stem from the same gap — `NodeType` is a natural routing key
that the runtime should use for dispatch and scheduling but currently ignores.

**Scope:** This spec addresses NodeProvisioner routing and per-type scheduling. Other SPIs
(GoalCompiler, ActualStateAdapter, EventSource, FaultPolicy) have analogous CDI ambiguity
but require their own routing strategies — tracked in #51 and #52. True multi-domain
deployments require solving all five SPIs; this is one step.

## Design

Make `NodeType` a first-class routing key in the runtime. Two SPI additions on
`NodeProvisioner`, one new routing interface, and targeted changes to the executor and
reconciliation loop.

### 1. NodeProvisioner SPI additions

```java
public interface NodeProvisioner {
    Set<NodeType> handledTypes();
    default Duration resyncInterval() { return Duration.ofMinutes(5); }
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}
```

**`handledTypes()`** — no default, abstract. Every implementation must declare its types
explicitly. The compile error is the migration — it forces every provisioner to be explicit
about its provisioning domain. This replaces internal if/else dispatch with a runtime
contract.

**`resyncInterval()`** — default 5 minutes. The provisioner is the domain expert on its
domain's volatility. IoT overrides to 10–30s. Infra overrides to 30min. This is a
provisioner-level default — operational overrides per NodeType are applied via Preferences
in the router (§2).

**Why `resyncInterval()` on the provisioner:**
The provisioner knows its domain's volatility. Separating scheduling into a distinct SPI
would require domains to implement two things instead of one with no additional value.
The interval is a `default` method — provisioners that don't care about scheduling inherit
the 5-minute default and never think about it. One method, zero ceremony for the common case.
Preferences overrides (§2) provide per-NodeType granularity without adding SPI complexity.

**Why one interval per provisioner (not per NodeType):**
A provisioner handles related types that share the same volatility profile. IoT devices
(temperature sensors, motion sensors, lights) all need frequent polling. Infrastructure
(VMs, DNS, certificates) all need infrequent polling. When per-type granularity IS needed,
operators override via Preferences (`desiredstate.resync.<nodeType>`) — no SPI break, no
code change, no redeployment.

**Validation contract:** `resyncInterval()` must return a non-null Duration ≥ 1 second.
The router validates at construction time (fail-fast). Zero or negative durations cause
`IllegalArgumentException`. Null causes `NullPointerException`. The 1-second floor prevents
tight spin loops in `scheduleAtFixedRate`.

### 2. NodeProvisionerRouter — interface in api, implementation in runtime

```java
// In casehub-desiredstate-api — pure Java interface
public interface NodeProvisionerRouter {
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
    Duration resyncIntervalFor(NodeType type);
    Set<NodeType> allHandledTypes();
}
```

**Why an interface in `api`:**
The router is consumed by both `SimpleTransitionExecutor` (runtime/) and
`DesiredStateDispatch` (engine-adapter/). Engine-adapter depends on `api` at compile scope
and on `runtime` only at test scope. An interface in `api` lets both modules program against
the contract without engine-adapter taking a compile dependency on runtime. This follows the
module-tier-structure protocol: Tier 1 `api/` contains interfaces, records, enums, and
POJOs. The concrete implementation — routing table construction, validation, Preferences
integration — lives in runtime (Tier 2/3).

**Concrete implementation in runtime:**

```java
// In casehub-desiredstate (runtime)
public class DefaultNodeProvisionerRouter implements NodeProvisionerRouter {

    private static final Duration MIN_RESYNC = Duration.ofSeconds(1);
    private static final Duration DEFAULT_RESYNC = Duration.ofMinutes(5);

    private final Map<NodeType, NodeProvisioner> routing;
    private final PreferenceProvider preferenceProvider;

    public DefaultNodeProvisionerRouter(Collection<NodeProvisioner> provisioners,
                                         PreferenceProvider preferenceProvider) {
        Map<NodeType, NodeProvisioner> table = new LinkedHashMap<>();
        for (NodeProvisioner p : provisioners) {
            Duration interval = p.resyncInterval();
            if (interval == null || interval.compareTo(MIN_RESYNC) < 0) {
                throw new IllegalArgumentException(
                    p.getClass().getName() + ".resyncInterval() returned " + interval
                    + "; must be ≥ " + MIN_RESYNC);
            }
            for (NodeType type : p.handledTypes()) {
                NodeProvisioner existing = table.put(type, p);
                if (existing != null) {
                    throw new IllegalArgumentException(
                        "NodeType " + type.value() + " claimed by both "
                        + existing.getClass().getName() + " and "
                        + p.getClass().getName());
                }
            }
        }
        this.routing = Map.copyOf(table);
        this.preferenceProvider = preferenceProvider;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new ProvisionResult.Failed(
                "No provisioner for node type: " + node.type().value());
        }
        return p.provision(node, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new DeprovisionResult.Failed(
                "No provisioner for node type: " + node.type().value());
        }
        return p.deprovision(node, context);
    }

    @Override
    public Duration resyncIntervalFor(NodeType type) {
        // Preferences override takes precedence
        Preferences prefs = preferenceProvider.resolve(SettingsScope.global());
        DurationPreference override = prefs.get(
            DesiredStatePreferenceKeys.RESYNC_INTERVAL, type.value());
        if (override != null) {
            Duration value = override.duration();
            if (value.compareTo(MIN_RESYNC) < 0) {
                throw new IllegalArgumentException(
                    "Preferences override for " + type.value() + " returned "
                    + value + "; must be ≥ " + MIN_RESYNC);
            }
            return value;
        }
        // Fall back to provisioner's declared default
        NodeProvisioner p = routing.get(type);
        return p != null ? p.resyncInterval() : DEFAULT_RESYNC;
    }

    @Override
    public Set<NodeType> allHandledTypes() {
        return routing.keySet();
    }
}
```

**Preferences integration (satisfies #19 "configurable interval"):**

Operators override resync intervals per NodeType via Preferences without code changes:

```yaml
# In casehub preferences (YAML, DB, or config file)
desiredstate.resync.physical-device: PT10S
desiredstate.resync.infra-vm: PT1H
```

The router checks `PreferenceProvider` for a `desiredstate.resync.<nodeType>` override
before falling back to the provisioner's declared default. This gives operational control
at per-NodeType granularity without adding `Map<NodeType, Duration>` complexity to the
SPI.

Preferences overrides are validated with the same `MIN_RESYNC` floor as provisioner
defaults — an operator setting `desiredstate.resync.physical-device: PT0S` gets an
`IllegalArgumentException` at resolution time, not a CPU spin loop at scheduling time.

**`DurationPreference` — new Preferences type (casehub-platform):**

`DurationPreference` does not exist in the codebase today. This spec introduces it:

```java
// In casehub-platform-api
public record DurationPreference(Duration duration) implements MultiValuePreference {
    public DurationPreference {
        Objects.requireNonNull(duration, "duration must not be null");
    }
}
```

The `PreferenceKey` definition and ISO-8601 parser live in casehub-desiredstate runtime
(the Preferences infrastructure parses stored strings via the key's parser function):

```java
// In casehub-desiredstate (runtime)
public final class DesiredStatePreferenceKeys {
    public static final PreferenceKey<DurationPreference> RESYNC_INTERVAL =
        new PreferenceKey<>("desiredstate", "resync",
            new DurationPreference(Duration.ofMinutes(5)),
            s -> new DurationPreference(Duration.parse(s)));
}
```

This is the first `MultiValuePreference` in production use. The infrastructure exists
(`Preferences.get(key, subKey)`, `getOrDefault(key, subKey)`) but has only been exercised
in tests. Implementation must verify that the preference storage backend (MongoDB / config
file) correctly handles multi-value keys with the `<namespace>.<name>.<subKey>` pattern.

**CDI wiring in runtime:**

```java
@ApplicationScoped
public class CdiNodeProvisionerRouter extends DefaultNodeProvisionerRouter {
    @Inject
    public CdiNodeProvisionerRouter(Instance<NodeProvisioner> provisioners,
                                     PreferenceProvider preferenceProvider) {
        super(StreamSupport.stream(provisioners.spliterator(), false).toList(),
              preferenceProvider);
    }
}
```

Both modules inject `NodeProvisionerRouter` (interface type). CDI resolves
`CdiNodeProvisionerRouter`. Contract in api, implementation in runtime.
`CdiNodeProvisionerRouter` is NOT a `NodeProvisioner` — no CDI ambiguity with domain
provisioner beans.

### 3. SimpleTransitionExecutor changes

Constructor changes from:

```java
public SimpleTransitionExecutor(NodeProvisioner provisioner,
                                 HumanNodeHandler humanNodeHandler,
                                 PendingApprovalHandler pendingApprovalHandler)
```

To:

```java
public SimpleTransitionExecutor(NodeProvisionerRouter router,
                                 HumanNodeHandler humanNodeHandler,
                                 PendingApprovalHandler pendingApprovalHandler)
```

All internal `provisioner.provision(node, context)` calls become
`router.provision(node, context)`. Same for deprovision. No other logic changes — the
router is a transparent replacement.

### 4. DesiredStateDispatch changes

Same pattern — `NodeProvisioner provisioner` becomes `NodeProvisionerRouter router`.
All `provisioner.provision(node, context)` calls become `router.provision(node, context)`.

### 5. ReconciliationLoop scheduling changes

The loop injects `NodeProvisionerRouter` (interface type) and drops the `resyncInterval`
constructor parameter. No concrete dependency on `DefaultNodeProvisionerRouter` — the
interface provides `allHandledTypes()` and `resyncIntervalFor(NodeType)`, which is
everything the scheduler needs.

**Interval-grouped timers replace the single resync timer.** Types are grouped by their
effective interval (provisioner default or Preferences override). Each distinct interval
gets one `ScheduledFuture` reconciling all types at that frequency.

```java
// In TenantLoop.start():
Map<Duration, Set<NodeType>> intervalGroups = new LinkedHashMap<>();
for (NodeType type : router.allHandledTypes()) {
    Duration interval = router.resyncIntervalFor(type);
    intervalGroups.computeIfAbsent(interval, k -> new LinkedHashSet<>()).add(type);
}

for (Map.Entry<Duration, Set<NodeType>> group : intervalGroups.entrySet()) {
    long millis = group.getKey().toMillis();
    Set<NodeType> types = Set.copyOf(group.getValue());
    ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
        () -> reconcileTypes(types), millis, millis, TimeUnit.MILLISECONDS);
    resyncFutures.put(group.getKey(), future);
}
```

**Why interval-grouped, not per-provisioner:** Per-provisioner timers assign one interval
per provisioner. But Preferences allows per-NodeType overrides — a provisioner handling
types with different Preferences intervals cannot be served by a single timer without
silently ignoring overrides or picking nondeterministically. Interval-grouped timers
respect per-NodeType granularity: types with the same effective interval share a timer;
types with different intervals get separate timers. If no Preferences overrides exist,
all types within a provisioner share the same default interval and naturally group together
into one timer — identical behaviour to per-provisioner timers, but correct under
Preferences.

**Type-filtered reconciliation (`reconcileTypes`):**

When an interval group's timer fires:

1. Filter the desired graph to nodes whose `NodeType` is in the triggering set
2. Call `readActual(filteredGraph, tenancyId)` — the adapter sees only the relevant nodes
3. Plan against the filtered graph and filtered actual state
4. Execute transitions for the filtered plan
5. Run fault feedback for the filtered execution results

Cross-type dependencies WITHIN an interval group are handled normally by the planner.
Cross-type dependencies ACROSS groups (rare) are resolved by the dependent group's next
cycle or by the event-driven path.

**Concurrency contract:** `reconcileTypes()` is designed for concurrent execution with
disjoint type sets. Multiple interval groups may fire simultaneously. The design guarantees
safety through:

- **Disjoint node sets:** Each type belongs to exactly one interval group. Concurrent
  cycles operate on non-overlapping nodes. No two cycles provision or deprovision the
  same node.
- **Handler thread safety:** `HumanNodeHandler` and `PendingApprovalHandler` are
  `@ApplicationScoped` CDI beans shared across cycles. Both operate at node granularity
  (`check(node, ...)`, `onProvision(node, ...)`). Concurrent calls with disjoint nodes
  have no logical contention. Note: CDI does not guarantee thread safety for
  `@ApplicationScoped` beans (unlike EJB `@Singleton`). Implementations are responsible
  for their own thread safety — but per-node operations with disjoint node sets have no
  shared mutable state to protect.
- **CAS-retry composability:** Concurrent cycles produce mutations for disjoint type
  domains. Graph mutations are structural (AddNode, RemoveNode, UpdateNode) and
  type-scoped — mutations from one type domain cannot conflict with mutations from
  another. If cycle A commits before cycle B, B's CAS-retry re-applies B's mutations
  to A's committed graph. The type-disjointness guarantee means this composition is safe.
- **OTel tracing:** Each `reconcileTypes()` call creates its own span. `Scope` uses
  thread-local storage. Concurrent cycles on different threads create sibling spans.
  Spans include the triggering type set as an attribute for diagnostic clarity.

**Scheduler pool size:** The current single-thread `ScheduledExecutorService` must be
replaced with a pool sized to the number of distinct interval groups (or a reasonable
ceiling). With a single thread, a slow 30-minute infra reconciliation would block the 30s
IoT timer from firing. Pool size = `min(intervalGroups.size(), Runtime.availableProcessors())`.

**Event-driven reconciliation remains full-graph.** Events represent external state changes
that may span type boundaries. The event-driven path reads all actual state, plans against
the full graph, and executes all transitions — unchanged from current behavior.

**Event-driven / type-filtered overlap:** The full-graph event-driven `reconcile()` can
run concurrently with type-filtered `reconcileTypes()` calls. The event-driven path
processes ALL types, overlapping with every interval group. This is a pre-existing
condition — current code already has concurrent event-driven and timer-driven `reconcile()`
calls with the same overlap. The CAS-retry loop handles this: if the full-graph cycle
commits first, a type-filtered cycle's retry re-applies its mutations to the new base.
Mutations are graph-structural and derived from fault events, not graph content — they are
safely re-applicable regardless of concurrent modifications from other cycles. For
overlapping types (event-driven processes a type that a type-filtered cycle is also
processing), both cycles may produce mutations for the same node. The CAS-retry
serialises these commits; the second writer's retry re-applies to the first writer's
result. Duplicate mutations (e.g., two PROVISION_FAILED faults for the same node) are
idempotent — `FaultPolicyEngine.evaluate()` produces the same graph mutation for the same
fault event regardless of base graph version.

**When `updateDesired(tenancyId, newDesired)` is called:**

If the set of node types changes (types added or removed), recompute interval groups —
cancel obsolete timers, start new ones for newly-added groups.

**Preferences override staleness:** Interval groups are computed at `TenantLoop.start()`
and recomputed when `updateDesired()` is called. `PreferenceProvider` has no change
notification API. If an operator changes a resync Preferences override at runtime, the
change takes effect on the next `updateDesired()` call (triggered by goal recompilation)
or on service restart. There is no automatic detection of Preferences changes. This is an
acceptable V1 limitation — operators responding to incidents can trigger reconciliation
via `requestReconciliation()` or restart the service. A periodic interval refresh or
Preferences change listener can be added later without SPI changes.

**Test-friendly constructor** adds `Duration resyncOverride` that bypasses interval-grouped
scheduling and uses a single timer at the override interval. Tests that need deterministic
timing pass this override.

### 6. ReactiveNodeProvisioner cleanup (#53)

`ReactiveNodeProvisioner` has zero production implementations across the entire ecosystem.
The only references are in `SpiContractTest` (verifying implementability). Since this spec
already breaks every `NodeProvisioner` implementation (adding abstract `handledTypes()`),
this is the right time to delete the dead SPI. One migration, complete cleanup.

**Delete:** `ReactiveNodeProvisioner.java` from api/, the test in `SpiContractTest.java`,
and the `§8 Reactive parity` entry in ARC42STORIES.MD.

### 7. Testing module changes

`MockNodeProvisioner` gains configurable `handledTypes`:

```java
public class MockNodeProvisioner implements NodeProvisioner {
    private Set<NodeType> handledTypes = Set.of();
    private Duration resyncInterval = Duration.ofMinutes(5);

    // existing fields and methods unchanged

    @Override
    public Set<NodeType> handledTypes() { return handledTypes; }

    @Override
    public Duration resyncInterval() { return resyncInterval; }

    public void setHandledTypes(Set<NodeType> types) { this.handledTypes = Set.copyOf(types); }
    public void setResyncInterval(Duration interval) { this.resyncInterval = interval; }
}
```

`NodeProvisionerRouter` is an interface — tests provide a stub implementation or construct
a `DefaultNodeProvisionerRouter` with a `List<NodeProvisioner>` and a mock
`PreferenceProvider`. No CDI needed.

### 8. Example changes

**GoblinProvisioner:**

```java
@Override
public Set<NodeType> handledTypes() {
    return Set.of(DungeonNodeTypes.ROOM, DungeonNodeTypes.CREATURE, DungeonNodeTypes.TRAP);
}
```

Internal if/else dispatch remains — the provisioner is simple enough that extracting
per-type provisioners adds no value. The `handledTypes()` declaration is what the runtime
needs; internal structure is the provisioner's own concern.

**PipelineProvisioner:**

```java
@Override
public Set<NodeType> handledTypes() {
    return Set.of(
        PipelineNodeTypes.DATA_SOURCE, PipelineNodeTypes.SCHEMA,
        PipelineNodeTypes.AI_REVIEW, PipelineNodeTypes.HUMAN_REVIEW,
        PipelineNodeTypes.PROCESSING_STAGE);
}
```

Processing stage types that delegate to `ExecutionBackend` are included — the provisioner
handles them via its secondary dispatch layer. The runtime routes to the provisioner; the
provisioner routes internally to the backend.

## Cross-Repo Impact (casehub-ops)

Four provisioners need `handledTypes()` implementations. Mechanical — the types they
handle are already implicit in their code:

| Provisioner | `handledTypes()` | `resyncInterval()` |
|-------------|-----------------|-------------------|
| `DeploymentNodeProvisioner` | agent, channel, case-type, trust-policy, endpoint | default (5min) |
| `InfraNodeProvisioner` | infra types per backend | `Duration.ofMinutes(30)` |
| `ComplianceNodeProvisioner` | compliance control types | default (5min) |
| `IoTNodeProvisioner` | physical-device, device-config | `Duration.ofSeconds(30)` |

These changes are deferred until the casehub-ops repo is confirmed clean on main.

## What This Does NOT Change

- **`TransitionExecutor` SPI** — unchanged. Executor-level abstraction (Simple vs CTE) is
  orthogonal to provisioner dispatch.
- **`ActualStateAdapter`** — unchanged. Multi-adapter dispatch is tracked in #51.
- **`EventSource`** — unchanged. Event-driven reconciliation remains full-graph.
- **`TransitionPlanner`** — unchanged. Plans against whatever graph it receives.
- **`GoalCompiler`** — unchanged. Multi-compiler composition tracked in #52.

## CAS Race Fix (GE-20260616-3d2605)

Both `detectDrift()` and `faultFeedback()` in `ReconciliationLoop.TenantLoop` use
`desiredRef.compareAndSet(desired, mutated)`. If another reconciliation cycle updated
the ref concurrently, mutations are silently dropped. This is an existing bug, not
introduced by this design. But with potentially faster resync intervals, the race
becomes more probable.

**Fix approach — merge-and-retry loop:**

```java
// Accumulate all mutations as a list during fault processing
List<GraphMutation> mutations = /* accumulated during detectDrift/faultFeedback */;

// Apply via CAS-retry loop
DesiredStateGraph current;
DesiredStateGraph updated;
do {
    current = desiredRef.get();
    updated = current;
    for (GraphMutation mutation : mutations) {
        updated = updated.withMutation(mutation);
    }
} while (updated != current && !desiredRef.compareAndSet(current, updated));
```

Mutations are graph-structural (AddNode, RemoveNode, UpdateNode) and are derived from
fault events, not from the graph's current content. They are safely re-applicable to any
version of the graph. The retry loop is non-blocking and converges in O(1) iterations
under typical contention (concurrent `updateDesired()` calls are rare — only during
goal recompilation).

This approach applies to both `detectDrift()` and `faultFeedback()`. The existing
progressive-mutation accumulation (#32 fix) is preserved — mutations still accumulate
on a local graph during processing. The change is at the commit step: CAS-retry instead
of single-shot CAS.

## Module Placement Summary

| Artifact | What goes here |
|----------|---------------|
| `casehub-desiredstate-api` | `NodeProvisioner.handledTypes()`, `NodeProvisioner.resyncInterval()`, `NodeProvisionerRouter` interface |
| `casehub-platform-api` | `DurationPreference` record |
| `casehub-desiredstate` (runtime) | `DefaultNodeProvisionerRouter`, `CdiNodeProvisionerRouter`, `DesiredStatePreferenceKeys`, modified `SimpleTransitionExecutor`, modified `ReconciliationLoop` |
| `casehub-desiredstate-engine` (engine-adapter) | Modified `DesiredStateDispatch` |
| `casehub-desiredstate-testing` | Modified `MockNodeProvisioner` |
| `examples/dungeon` | `GoblinProvisioner.handledTypes()` |
| `examples/pipeline` | `PipelineProvisioner.handledTypes()` |
