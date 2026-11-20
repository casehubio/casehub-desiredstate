# D9: Desired-State-First GroupLifecycleService

**Issue:** casehubio/casehub-desiredstate#231
**Epic:** casehubio/casehub-desiredstate#230
**Date:** 2026-11-05
**Status:** Draft

## Problem

`DesiredStateGroupLifecycleService` is a CRUD wrapper. Every mutation (`createGroup`,
`joinGroup`, `adoptRole`, `changeTopology`, `dissolveGroup`, etc.) writes directly to
`GroupInstanceStore.save()`. The desired-state reconciliation loop is never invoked for
org mutations.

The spec (§6.1, D9) requires: every GroupLifecycleService mutation updates the desired-state
graph BEFORE updating GroupInstance. OrgNodeProvisioner, called by reconciliation, is the
only writer to OrgRegistry and GroupInstanceStore.

## Architecture

### Data Flow (After Fix)

```
Caller
  │
  ▼
GroupLifecycleService.createGroup(...)
  │
  ├─ 1. Read current OrgGoal from OrgGoalStore
  ├─ 2. Mutate OrgGoal (add/update/remove unit or relationship)
  ├─ 3. Compile: OrgGoalCompiler.compile(updatedGoal, factory) → CompilationResult
  ├─ 4. First mutation for tenant?
  │     YES → LifecycleManager.start(tenancyId, compilationResult)
  │     NO  → CAS swap: reconciliationLoop.compareAndSetDesired(tenancyId, old, new)
  ├─ 5. On success: persist OrgGoal to OrgGoalStore
  └─ 6. Return CreateGroupResult(instanceId)  [for create ops]
         or void                               [for other mutations]
         ↓ (async)
  ReconciliationLoop detects delta
  → TransitionPlanner plans additions/removals
  → OrgNodeProvisioner.provision() writes to OrgRegistry + GroupInstanceStore
  → CloudEvents emitted → GroupLifecycleListener events fire
```

### Key Principle: Always Compile

Every mutation flows through OrgGoalCompiler. No direct graph manipulation. This guarantees:
- Compiler invariants always apply (dependency edges, human gating, node spec defaults)
- Single code path — no divergence between "fast path" and "consistency path"
- OrgGoal (intent) is always the source of truth, not OrgRegistry (actual state)

### Layering

```
┌─────────────────────────────────────────────────┐
│  Callers (agents, API, EmergencePromotionListener)  │
└──────────────────────┬──────────────────────────┘
                       │ mutations
                       ▼
┌─────────────────────────────────────────────────┐
│  GroupLifecycleService (INTENT layer)            │
│  - Mutates OrgGoal                              │
│  - Compiles via OrgGoalCompiler                 │
│  - CAS swaps desired graph via ReconciliationLoop│
└──────────────────────┬──────────────────────────┘
                       │ desired graph
                       ▼
┌─────────────────────────────────────────────────┐
│  ReconciliationLoop (RECONCILIATION layer)       │
│  - Detects delta (desired vs actual)            │
│  - Plans transitions                            │
│  - Executes via OrgNodeProvisioner              │
└──────────────────────┬──────────────────────────┘
                       │ provision/deprovision
                       ▼
┌─────────────────────────────────────────────────┐
│  OrgNodeProvisioner (ACTUATION layer)            │
│  - Writes to OrgRegistry                        │
│  - Writes to GroupInstanceStore                  │
│  - Creates channels via OrgChannelCreator        │
└─────────────────────────────────────────────────┘
```

No circular delegation: GroupLifecycleService never calls OrgNodeProvisioner.
OrgNodeProvisioner never calls GroupLifecycleService. The reconciliation loop
is the unidirectional bridge.

**§6.1 divergence:** The society design spec (§6.1) says OrgNodeProvisioner
should delegate to SocietyLifecycleManager for mutual exclusion. This spec
diverges: the provisioner writes to stores directly (like every other domain's
provisioner). Mutual exclusion is provided by per-society locking (#237),
not by routing all writes through a single manager. The delegation model
creates circular dependency — GroupLifecycleService → graph → reconciliation →
provisioner → GroupLifecycleService — requiring context-sensitive behavior
(skip graph write when called from reconciliation). That layering violation
is worse than the problem it solves.

## New Components

### OrgGoalStore (SPI)

Stores the OrgGoal (intent) per tenant. The OrgGoal represents what the caller
WANTS — `List<OrganizationalUnit>` + `List<AgentRelationship>`. This is distinct
from OrgRegistry, which represents what EXISTS.

```java
public interface OrgGoalStore {
    void store(String tenancyId, OrgGoal goal);
    Optional<OrgGoal> load(String tenancyId);
    void remove(String tenancyId);
}
```

Default: `InMemoryOrgGoalStore` (ConcurrentHashMap). Same pattern as
`InMemoryFaultCountStore`, `InMemoryExemptionStore`.

**Module placement:** Both `OrgGoalStore` and `InMemoryOrgGoalStore` live in the
`eidos/` module — they depend on eidos-org-api types (`OrganizationalUnit`,
`AgentRelationship`) and cannot be in the generic `api/` module.

### OrgGoal

High-level org intent. Currently a nested record inside OrgGoalCompiler
(`OrgGoalCompiler.OrgGoal`). Promoted to a top-level type in the eidos module
for independent persistence and use by GroupLifecycleService. Structurally
identical to `OrgRegistrar.OrgDefinition` — OrgGoal replaces OrgDefinition
as the canonical intent representation. Mutation methods return new instances
(immutable record with copy-on-write):

- `withUnit(OrganizationalUnit)` — add or replace unit
- `withoutUnit(String unitId)` — remove unit
- `withRelationship(AgentRelationship)` — add or replace relationship
- `withoutRelationship(String relationshipId)` — remove relationship

### CreateGroupResult

Lightweight identity returned by `createGroup()` and `proposeGroup()`:

```java
public record CreateGroupResult(String instanceId) {}
```

Server generates the instanceId. Callers use it for subsequent calls
(`joinGroup(instanceId, ...)`, `adoptRole(instanceId, ...)`). The group
does not exist in GroupInstanceStore yet — only in the desired graph.

## Changes to Existing Components

### DesiredStateGroupLifecycleService

**Remove dependencies:**
- `GroupInstanceStore` — no longer writes directly

**Add dependencies:**
- `LifecycleManager` — start reconciliation loop for first mutation per tenant
- `ReconciliationLoop` — read/CAS desired graph for subsequent mutations
- `OrgGoalStore` — read/write OrgGoal intent
- `OrgGoalCompiler` — compile OrgGoal to graph
- `DesiredStateGraphFactory` — create graph instances

**Method changes:**

| Method | Before | After |
|--------|--------|-------|
| `createGroup(...)` | returns `GroupInstance`, writes to instanceStore | returns `CreateGroupResult`, writes to OrgGoal + compiles + CAS |
| `proposeGroup(...)` | returns `GroupInstance`, writes to instanceStore | returns `CreateGroupResult`, same pattern |
| `joinGroup(...)` | returns void, writes to instanceStore | returns void, updates OrgGoal unit members + compiles + CAS |
| `leaveGroup(...)` | returns void, writes to instanceStore | returns void, updates OrgGoal unit members + compiles + CAS |
| `adoptRole(...)` | returns void, writes to instanceStore | returns void, updates OrgGoal unit roles + compiles + CAS |
| `releaseRole(...)` | returns void, writes to instanceStore | returns void, updates OrgGoal unit roles + compiles + CAS |
| `dissolveGroup(...)` | returns void, writes to instanceStore | returns void, removes unit from OrgGoal + compiles + CAS |
| `changeTopology(...)` | returns void, writes to instanceStore | returns void, updates OrgGoal unit topology + compiles + CAS |
| `approveProposal(...)` | returns void, writes to instanceStore | returns void, updates OrgGoal unit state + compiles + CAS |
| `rejectProposal(...)` | returns void, writes to instanceStore | returns void, removes unit from OrgGoal + compiles + CAS |
| `activateGroup(...)` | returns void, writes to instanceStore | returns void, updates OrgGoal unit state + compiles + CAS |
| `commitToMission(...)` | returns void, writes to instanceStore | returns void, updates unit missions in OrgGoal + compiles + CAS |
| `withdrawFromMission(...)` | returns void, writes to instanceStore | returns void, updates unit missions in OrgGoal + compiles + CAS |
| `fulfillMission(...)` | returns void, writes to instanceStore | returns void, removes mission from unit in OrgGoal + compiles + CAS |

**Common mutation pattern:**

```java
void mutate(String tenancyId, Function<OrgGoal, OrgGoal> mutation) {
    OrgGoal goal = orgGoalStore.load(tenancyId).orElse(OrgGoal.empty());
    OrgGoal updated = mutation.apply(goal);
    CompilationResult result = goalCompiler.compile(updated, graphFactory);

    if (!reconciliationLoop.isRunning(tenancyId)) {
        lifecycleManager.start(tenancyId, result);
        orgGoalStore.store(tenancyId, updated);
        return;
    }

    for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
        DesiredStateGraph current = reconciliationLoop.getDesired(tenancyId);
        DesiredStateGraph newGraph = switch (result) {
            case CompilationResult.SingleGraph sg -> sg.graph();
            case CompilationResult.Lifecycle lc ->
                throw new UnsupportedOperationException(
                    "Org domain uses single-graph compilation");
        };
        if (reconciliationLoop.compareAndSetDesired(tenancyId, current, newGraph)) {
            orgGoalStore.store(tenancyId, updated);
            return;
        }
        goal = orgGoalStore.load(tenancyId).orElse(OrgGoal.empty());
        updated = mutation.apply(goal);
        result = goalCompiler.compile(updated, graphFactory);
    }
    throw new ConcurrentModificationException(
        "CAS failed after " + MAX_CAS_ATTEMPTS + " attempts");
}
```

Key design choices in this pattern:
- **OrgGoalStore written AFTER CAS success** — eliminates the race where concurrent
  readers see intermediate state. A crash between CAS success and store write loses
  the latest goal, but on restart the desired graph (in ReconciliationLoop) is
  authoritative and OrgGoal can be reconstructed.
- **LifecycleManager.start() for first mutation** — handles loop startup. Subsequent
  mutations use ReconciliationLoop directly for CAS semantics.
- **Pattern match on CompilationResult** — exhaustive switch over the sealed type.
  Org domain currently uses SingleGraph only; Lifecycle variant is rejected explicitly.
- **Full re-read and recompile on CAS retry** — if CAS fails, re-read OrgGoal (another
  thread may have updated it), re-apply the mutation, and recompile. This ensures
  the retry operates on current state.

**Validation:** Preconditions (group exists, agent not already member, valid state
transition) validate against the OrgGoal — not against OrgRegistry or GroupInstanceStore.
This is a subtle but important shift: validation checks desired state (what we want),
not actual state (what exists).

**Query method behavioral change:** `findInstance()`, `findInstances()`, and
`instancesForAgent()` read from GroupInstanceStore, which is populated by
OrgNodeProvisioner during reconciliation. After `createGroup()` returns, the
group exists in the OrgGoal and desired graph but NOT in GroupInstanceStore.
Callers that do `createGroup() → findInstance()` will get `Optional.empty()`
until reconciliation completes. Callers should either:
- Observe via GroupLifecycleListener events (#233)
- Poll with backoff
- Query OrgGoal directly for pending groups (new method on GroupLifecycleService)

**Example: joinGroup end-to-end**

```java
public void joinGroup(String instanceId, String agentId, String tenancyId) {
    mutate(tenancyId, goal -> {
        OrganizationalUnit unit = goal.findUnit(instanceId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown group: " + instanceId));
        // OrganizationalUnit is a 17-field record — reconstruct with updated members
        var updatedMembers = new ArrayList<>(unit.members());
        updatedMembers.add(new Membership(agentId, List.of(), Instant.now()));
        OrganizationalUnit updated = unit.withMembers(updatedMembers);
        return goal.withUnit(updated); // replaces unit by unitId
    });
}
```

`OrganizationalUnit.withMembers()` and similar `with*` methods return a new record
with the specified field changed. OrgGoal.withUnit() finds the existing unit by
`unitId` and replaces it.

### GroupLifecycleService Interface (eidos-org-api, cross-repo)

- `createGroup(...)` → returns `CreateGroupResult` instead of `GroupInstance`
- `proposeGroup(...)` → returns `CreateGroupResult` instead of `GroupInstance`
- All other mutations already return void — no change needed

### OrgNodeProvisioner

No changes in this issue. Continues writing to OrgRegistry + GroupInstanceStore
directly. Issue #234 will ensure it is the ONLY store writer.

### OrgGoalCompiler

Minor: may need to accept `OrgGoal` directly (currently takes individual lists).
If `OrgGoal` already has `units()` and `relationships()` accessors, no change needed.

## Tenancy Model

The tenancyId for org reconciliation is the society identifier. Each society
has its own ReconciliationLoop instance, its own OrgGoal in the store, and its
own desired graph. This aligns with the society-as-tenant model in the spec.

## Error Handling

- **CAS contention:** Bounded retry (3 attempts). On failure, throw
  `ConcurrentModificationException`. Caller retries at a higher level.
- **Validation failures:** Throw immediately. No OrgGoal mutation or graph change.
- **Provisioning failures:** Handled by OrgFaultPolicy (2-tier escalation:
  3 faults → org:review, 5 faults → org:human-review). GroupLifecycleService
  is not involved.

## Startup / Boot

On startup, `OrgBootstrap` (or its replacement in #235) loads OrgGoal from
`OrgGoalStore`, compiles via `OrgGoalCompiler`, and starts the `ReconciliationLoop`
with the compiled graph via `LifecycleManager.start()`. This establishes the
initial desired state.

If `OrgGoalStore` is empty on first boot, the system starts with an empty desired
graph. The first GroupLifecycleService mutation for a tenant handles loop startup
automatically (see mutation pattern above).

**Migration path (#231 → #235):** During the transition window before #235 lands,
`OrgBootstrap` continues to write directly to `OrgRegistry` (imperative path).
`DesiredStateGroupLifecycleService` writes to OrgGoal/desired graph (intent path).
These paths are disconnected — OrgBootstrap populates actual state, GroupLifecycleService
populates desired state. Reconciliation bridges the gap: after boot, the first
mutation starts the reconciliation loop, which detects the delta between desired
(from OrgGoal) and actual (from OrgRegistry via DesiredStateOrgAdapter). This is
not ideal but safe — #235 unifies the boot path through desired-state-first.

## Testing Strategy

### Unit Tests
- Mock `ReconciliationLoop` and `OrgGoalStore`
- Each mutation: verify OrgGoal is read, mutated correctly, stored, compiled,
  and CAS-swapped
- CAS retry: mock `compareAndSetDesired` to fail on first attempt, verify retry
- Validation: verify precondition checks against OrgGoal state

### Integration Tests
- Full stack: ReconciliationLoop + OrgNodeProvisioner + real stores
- `createGroup()` → verify GroupInstance appears in GroupInstanceStore after
  reconciliation converges
- Multiple mutations → verify OrgGoal and desired graph stay consistent

## Scope Boundaries

**In scope (#231):**
- DesiredStateGroupLifecycleService refactoring (desired-state-first)
- OrgGoalStore SPI + InMemoryOrgGoalStore
- OrgGoal mutation methods (withUnit, withoutUnit, etc.)
- CreateGroupResult type
- GroupLifecycleService interface change (createGroup/proposeGroup return type)
- Tests

**Out of scope (separate issues):**
- #232: Constraint enforcement in GroupLifecycleService
- #233: GroupLifecycleListener event firing
- #234: OrgNodeProvisioner as sole store writer
- #235: Unified boot path (replaces OrgBootstrap)
- #237: Per-society locking and CAS retry
- #238: EmergencePromotionListener bypass fix

## References

- Spec §6.1: `engine wksp specs/2026-11-02-self-organising-agent-society-design.md` lines 596-622
- `eidos/src/main/java/.../DesiredStateGroupLifecycleService.java` — current CRUD implementation
- `eidos/src/main/java/.../OrgGoalCompiler.java` — existing goal compiler
- `eidos/src/main/java/.../OrgNodeProvisioner.java` — existing provisioner
- `runtime-core/.../ReconciliationLoop.java` — reconciliation API (updateDesired, compareAndSetDesired)
- Decision review: R1-02 (data flow inversion), R1-03 (premature complexity), R1-04 (return type)
- Spec review: R1-02 (§6.1 divergence), R1-03 (CAS race fix), R1-04 (exhaustive match), R1-05/R1-06 (LifecycleManager startup)
