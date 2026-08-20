# TransitionPlanner: Exhaustive Status Handling — Design Spec

**Issue:** #38
**Date:** 2026-06-23
**Status:** Design

---

## 1. Problem

`TransitionPlanner.plan()` has two implicit gaps in its status handling:

1. **DRIFTED nodes in desired are ignored.** Line 52 checks `ABSENT || UNKNOWN` only. A node that exists but has diverged from its spec is never re-provisioned. No automatic self-healing.

2. **DRIFTED orphans are not removed.** Line 34 checks `PRESENT` only for orphan detection. A drifted node not in the desired graph is invisible to the planner.

Root cause: the planner uses ad-hoc if-chains that implicitly skip unhandled statuses. `NodeStatus` has four values; the planner explicitly handles two per code path and silently ignores the rest.

---

## 2. Fix

Restructure `plan()` to classify every node through exhaustive switch expressions on `NodeStatus`. The planner's behavior is a 2×4 matrix — two contexts (in-desired, orphan) crossed with four statuses:

| Status | In desired graph | Not in desired (orphan) |
|---|---|---|
| PRESENT | no action | DEPROVISION |
| ABSENT | PROVISION | no action |
| DRIFTED | PROVISION | DEPROVISION |
| UNKNOWN | PROVISION | no action |

Java switch expressions on an enum without a `default` clause fail to compile if any constant is missing. Adding a fifth `NodeStatus` value forces the planner author to decide what it means in both contexts.

### plan() implementation

```java
public TransitionPlan plan(DesiredStateGraph desired, ActualState actual) {
    List<OrderedStep> removals = new ArrayList<>();

    // Orphan detection: nodes in actual but not in desired
    for (Map.Entry<NodeId, NodeStatus> entry : actual.statuses().entrySet()) {
        NodeId nodeId = entry.getKey();
        if (!desired.nodes().containsKey(nodeId)) {
            boolean remove = switch (entry.getValue()) {
                case PRESENT, DRIFTED -> true;
                case ABSENT, UNKNOWN  -> false;
            };
            if (remove) {
                removals.add(new OrderedStep(
                    new DesiredNode(nodeId, new UnknownSpec(), false),
                    StepAction.DEPROVISION));
            }
        }
    }

    // Desired node classification: what needs provisioning
    Set<NodeId> toAdd = new HashSet<>();
    for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
        NodeStatus status = actual.statuses().getOrDefault(entry.getKey(), NodeStatus.UNKNOWN);
        boolean provision = switch (status) {
            case ABSENT, UNKNOWN, DRIFTED -> true;
            case PRESENT                  -> false;
        };
        if (provision) {
            toAdd.add(entry.getKey());
        }
    }

    // Topologically sort additions: roots-first (Kahn's algorithm)
    List<NodeId> sorted = topologicalSort(desired, toAdd);
    List<OrderedStep> additions = new ArrayList<>();
    for (NodeId nodeId : sorted) {
        additions.add(new OrderedStep(desired.nodes().get(nodeId), StepAction.PROVISION));
    }

    return new TransitionPlan(removals, additions, desired, desired);
}
```

### What changes

| Aspect | Before | After |
|---|---|---|
| DRIFTED in desired | Silently ignored | Re-provisioned (topologically ordered) |
| DRIFTED orphan | Silently ignored | Deprovisioned |
| Status dispatch | Ad-hoc if-chains, implicit gaps | Exhaustive switch expressions, compile-time safe |
| Adding a 5th NodeStatus | Silent fallthrough, runtime bug | Compile error in both switches |

### What does NOT change

- `NodeStatus` enum — unchanged
- `StepAction` enum — no `REPROVISION` added; provisioners are idempotent by contract
- `OrderedStep` record — no `priorStatus` field; tracing handles this at the reconciliation loop level
- `topologicalSort()` — unchanged; DRIFTED nodes enter `toAdd` the same way ABSENT nodes do
- `UnknownSpec` for orphan removal — pre-existing limitation (ActualState carries status, not type)
- `SimpleTransitionExecutor` — calls `provisioner.provision()` regardless of whether it's first-time or re-provisioning
- `ReconciliationLoop.detectDrift()` — unchanged; still fires NODE_DEGRADED faults before the planner runs

---

## 3. Interaction with ReconciliationLoop

The reconciliation cycle runs: readActual → detectDrift → plan → execute → faultFeedback.

`detectDrift()` fires NODE_DEGRADED fault events and applies fault policy mutations (e.g., adding HUMAN_REVIEW nodes) BEFORE `plan()` runs. With this fix, `plan()` also includes the drifted node itself in the additions.

Both actions are correct and non-conflicting:
- Fault policy adds domain-specific response (review nodes, escalation)
- Planner adds re-provisioning of the drifted node itself

If re-provisioning fixes the drift: the adapter reports PRESENT on the next cycle, self-healed. If re-provisioning doesn't fix the drift (e.g., schema drift requires human approval): the adapter still reports DRIFTED, fault policy continues handling, re-provisioning is harmlessly idempotent.

### Double-fault per cycle

A DRIFTED node that fails re-provisioning now generates two fault events in a single reconciliation cycle:

1. **NODE_DEGRADED** — from `detectDrift()` (before the planner runs)
2. **PROVISION_FAILED** — from `faultFeedback()` (after the executor returns a failure)

Before this fix, only NODE_DEGRADED could fire for a drifted node (since the planner never attempted re-provisioning, PROVISION_FAILED was impossible for that node).

Both faults are semantically correct and non-conflicting — the node is still degraded AND the fix attempt failed. They target different fault types and are handled by different policies (e.g., `SchemaDriftFaultPolicy` handles NODE_DEGRADED for schemas; `ProvisionEscalationFaultPolicy` handles PROVISION_FAILED with retry counting). FaultPolicy implementors must understand that both can fire for the same drifted node in the same cycle.

---

## 4. Why not considered

| Considered | Rejected because |
|---|---|
| `REPROVISION` as StepAction | Provisioners are idempotent. Provision is provision. Two actions for the same `provision()` call adds API surface with no behavioral difference. |
| `priorStatus` on OrderedStep | Executor doesn't need it. Tracing gets this from reconciliation loop context. |
| `DEGRADED` as a new NodeStatus | Different concept from DRIFTED (runtime performance vs spec divergence) but the planner treats both the same. Separate design issue. |
| Classification methods on NodeStatus | Planner owns "what action for this status." Putting methods on the enum couples it to planner logic. |
| Private helper methods | For 4-case switches, inline is clearer. Methods add naming burden without testability gain. |

---

## 5. Test strategy

TDD. 8 new tests covering every cell of the 2×4 matrix:

| # | Test | Cell |
|---|---|---|
| 1 | `driftedInDesired_producesAddition` | In desired + DRIFTED |
| 2 | `driftedOrphan_producesRemoval` | Not in desired + DRIFTED |
| 3 | `driftedWithDependencies_respectsTopologicalOrder` | In desired + DRIFTED (ordering) |
| 4 | `mixedStatuses_classifiesCorrectly` | PRESENT + ABSENT + DRIFTED + UNKNOWN across desired and orphan |
| 5 | `presentInDesired_noAction` | In desired + PRESENT (existing, verify not broken) |
| 6 | `unknownOrphan_noAction` | Not in desired + UNKNOWN |
| 7 | `absentOrphan_noAction` | Not in desired + ABSENT |
| 8 | `driftedWithPresentDependency_provisionsDriftedOnly` | B DRIFTED depends on A PRESENT — only B in toAdd, zero in-degree (A satisfied, outside toAdd) |

Tests 5-7 verify the exhaustive handling covers the "no action" cells explicitly. Test 8 validates that topological sort correctly handles partial re-provisioning — the dependency subgraph is different from a clean ABSENT deployment. Existing tests continue to pass unchanged.

ReconciliationLoop integration: verify that a DRIFTED node is actually re-provisioned through the full reconcile cycle (readActual → detectDrift → plan → execute). Existing `ReconciliationLoopTest.driftDetection_runsBeforeEmptyPlanCheck` renamed to `driftDetection_addsFaultPolicyNodesAlongsideReprovision` — the "empty plan check" premise is invalid after this fix (DRIFTED → provision means the plan is non-empty regardless). Stale comment at line 270 fixed (node "a" IS now planned for addition), assertion strengthened to verify both "a" (re-provisioned) and "a-fix" (fault-policy-injected) appear in additions.

---

## 6. Files changed

| File | Change |
|---|---|
| `runtime/src/main/java/.../TransitionPlanner.java` | Exhaustive switch expressions in `plan()` |
| `runtime/src/test/java/.../TransitionPlannerTest.java` | 8 new tests covering the 2×4 matrix |
| `runtime/src/test/java/.../ReconciliationLoopTest.java` | Rename `driftDetection_runsBeforeEmptyPlanCheck` → `driftDetection_addsFaultPolicyNodesAlongsideReprovision`: fix stale comment, assert both "a" and "a-fix" in additions. New integration test: DRIFTED → re-provisioned. |
