# HumanGating Per-Action Flags, Work-Adapter Boundary, and Case-Lifecycle Coordination

**Issues:** #72 (work-adapter boundary), #79 (per-action requiresHuman), #80 (WorkItem lifecycle coordination)
**Date:** 2026-07-17
**Status:** Approved

## Problem

`DesiredNode.requiresHuman` is a single boolean that routes **both** lifecycle actions (provision and deprovision) to `HumanNodeHandler`, bypassing the provisioner entirely. This conflates "requires human for any action" with "requires human for all actions."

Real domains need independent per-action human gating:
- **Automated deploy, human-reviewed removal**: A Gold-tier data pipeline stage can be auto-deployed (schema validation catches issues), but removing it requires human review (data loss, downstream consumer breakage).
- **Human deploy, automated teardown**: A critical service requires human review before deployment, but teardown is safe to automate.

The current design makes both patterns impossible — if `requiresHuman=true`, neither action can reach the provisioner.

Issue #79 initially deferred this change, noting that `HumanNodeHandler.onProvision`/`onDeprovision` already provides per-action control. However, the handler is called too late: `SimpleTransitionExecutor` evaluates `node.requiresHuman()` to decide whether to route to the handler or the provisioner. With a single boolean, when `requiresHuman=true`, both provision and deprovision route to the handler — the provisioner is never called. The handler can return `Skipped` for one action, but it cannot delegate back to the provisioner. Per-action control must exist at the routing level (the `requiresHuman` flag), not the handler level.

Additionally, `WorkItemHumanNodeHandler` in work-adapter creates orphaned WorkItems with no lifecycle management. If human provisioning matters enough to block reconciliation, it needs case-backed orchestration (CTE → engine → casehub-work), not direct WorkItem creation without audit trail or cancellation semantics.

## Design

### 1. HumanGating Enum (api module)

Replace `boolean requiresHuman` with a four-state enum. Two booleans have exactly four meaningful states — an enum models this precisely with exhaustive switch and named constants.

```java
public enum HumanGating {
    NONE,              // fully automated
    PROVISION_ONLY,    // human provision, automated deprovision
    DEPROVISION_ONLY,  // automated provision, human deprovision
    ALL;               // both actions human-gated

    public boolean requiresHuman(StepAction action) {
        return switch (action) {
            case PROVISION -> this == PROVISION_ONLY || this == ALL;
            case DEPROVISION -> this == DEPROVISION_ONLY || this == ALL;
        };
    }

    public boolean any() { return this != NONE; }

    public HumanGating merge(HumanGating other) {
        if (this == ALL || other == ALL) return ALL;
        boolean p = requiresHuman(StepAction.PROVISION) || other.requiresHuman(StepAction.PROVISION);
        boolean d = requiresHuman(StepAction.DEPROVISION) || other.requiresHuman(StepAction.DEPROVISION);
        if (p && d) return ALL;
        if (p) return PROVISION_ONLY;
        if (d) return DEPROVISION_ONLY;
        return NONE;
    }
}
```

### 2. DesiredNode Changes

```java
public record DesiredNode(NodeId id, NodeType type, NodeSpec spec, HumanGating humanGating) {
    public boolean requiresHuman(StepAction action) {
        return humanGating.requiresHuman(action) || spec.humanGating().requiresHuman(action);
    }

    public boolean requiresHuman() {
        return humanGating.any() || spec.humanGating().any();
    }
}
```

Merge semantics: per-action OR between node-level and spec-level gating. Spec declares type-level gating ("all Gold-tier stages need human deprovision"). Node declares instance-level overrides. Either source can elevate an action to human-gated.

### 3. NodeSpec Changes

```java
public interface NodeSpec {
    default HumanGating humanGating() { return HumanGating.NONE; }
}
```

Replaces `boolean requiresHuman()`. Existing implementations change from `return requiresHuman;` to `return requiresHuman ? HumanGating.ALL : HumanGating.NONE;` (or use specific gating per domain).

### 4. GraphDiff and UpdateNode Changes

`GraphDiff.computeMutations` currently compares only `NodeSpec` equality between current and adapted nodes. It does not detect node-level field changes (`requiresHuman` → `humanGating`). If a GoalCompiler produces a node with the same spec but different `humanGating` (e.g., a pipeline stage promoted to Gold tier where the spec stays equal but gating changes), the change is silently lost.

Additionally, `GraphMutation.UpdateNode` carries only `(NodeId id, NodeSpec newSpec)`, and `ImmutableDesiredStateGraph.withMutation` passes `existing.requiresHuman()` — the overridden method that ORs node-level and spec-level values — into the new `DesiredNode`. This bakes the merged value into the node-level field: if the spec had `requiresHuman()=true` but the node-level field was `false`, UpdateNode permanently elevates the node-level field. This is a latent bug that the `HumanGating` migration fixes by design.

**Changes:**

1. `GraphDiff.computeMutations` — compare full node equality:
   ```java
   } else if (!currentNode.equals(adaptedNode)) {
       mutations.add(new GraphMutation.UpdateNode(id, adaptedNode));
   }
   ```
   Record equality on `DesiredNode` compares all component fields (`id`, `type`, `spec`, `humanGating`), detecting any change.

2. `GraphMutation.UpdateNode` — carry the full adapted node:
   ```java
   record UpdateNode(NodeId id, DesiredNode adaptedNode) implements GraphMutation {}
   ```

3. `ImmutableDesiredStateGraph.withMutation` — replace directly:
   ```java
   case GraphMutation.UpdateNode m -> withNode(m.adaptedNode());
   ```
   The adapted node carries the correct `humanGating` from the GoalCompiler. No field merging, no baked values.

### 5. STE Per-Action Routing

SimpleTransitionExecutor routes each action independently:

```java
// executeProvision:
if (node.requiresHuman(StepAction.PROVISION)) {
    return humanNodeHandler.onProvision(node, context);
}
// ... approval check + provisioner ...

// executeDeprovision:
if (node.requiresHuman(StepAction.DEPROVISION)) {
    return humanNodeHandler.onDeprovision(node, context);
}
// ... approval check + provisioner ...
```

A node with `PROVISION_ONLY` routes provision to the handler and deprovision to the provisioner. No infinite Skipped loops.

**Span attributes:** Replace the blanket boolean with the per-action check and the enum name:
```java
.setAttribute(AttributeKey.stringKey("desiredstate.human.gating"), node.humanGating().name())
.setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman(action))
```
`desiredstate.requires.human` remains a boolean, now scoped to the current action (`StepAction.PROVISION` in `executeProvision`, `StepAction.DEPROVISION` in `executeDeprovision`). `desiredstate.human.gating` adds the full enum value for dashboard filtering. Attribute names are stable — dashboards using `desiredstate.requires.human` continue to work; `desiredstate.human.gating` is additive.

### 6. CTE Per-Action Separation

CaseTransitionExecutor separates human from automated steps per-action:

```java
for (OrderedStep step : plan.removals()) {
    if (step.node().requiresHuman(step.action())) {
        humanRemovals.add(step);
    } else {
        automatedRemovals.add(step);
    }
}
```

`checkApproval` also uses per-action check:
```java
if (step.node().requiresHuman(step.action())) {
    return null; // human nodes handled by case bindings
}
```

`buildOptimisticResult` also updates to per-action check with corrected message:
```java
for (OrderedStep step : plan.removals()) {
    if (step.node().requiresHuman(step.action())) {
        outcomes.put(step.node().id(), new StepOutcome.Skipped("routed to human task binding"));
    } else {
        outcomes.put(step.node().id(), new StepOutcome.Succeeded());
    }
}
```
Message changes from `"routed to WorkItem"` to `"routed to human task binding"` — CTE routes human nodes to `HumanTaskTarget` case bindings, not WorkItems.

### 7. Remove WorkItemHumanNodeHandler (#72)

**Delete:** `WorkItemHumanNodeHandler` and `WorkItemHumanNodeHandlerTest` from work-adapter.

**Keep:**
- `HumanNodeHandler` SPI (api) — valid extension point
- `NoOpHumanNodeHandler` (runtime) — default, returns Skipped for both actions (misconfiguration signal: human-gated action with no handler configured)
- work-adapter module — empty after this change. Retained as the natural home for a future `WorkItemPendingApprovalHandler` implementation (issue #81). The pending approval lifecycle uses WorkItems for approval tracking, and the implementation belongs in this module.

**Update `HumanNodeHandler.onDeprovision` default:** Change message from `"deprovision not handled"` to `"no HumanNodeHandler configured for deprovision"` — with per-action routing, this method is only called when deprovision IS human-gated, so "not handled" is misleading.

**Rationale (Option 2 over work#290 path):** Issue #72's comment references `casehubio/work#290` (relocating HumanTask adapter from engine to work). That issue (now closed) moves the engine's HumanTask scheduling adapter into casehub-work — it does not create a complete lifecycle management path for desired-state nodes. Even with HumanTask API in casehub-work, direct WorkItem creation without case context lacks cancellation semantics, audit trail, and coordination between conflicting lifecycle actions (issue #80). Case-backed orchestration (CTE → engine → casehub-work) provides these guarantees. Human nodes that matter enough to block reconciliation need case-backed orchestration — creating orphaned WorkItems without case lifecycle is not a valid deployment option. The `HumanNodeHandler` SPI remains available if the case-lifecycle requirement is relaxed in the future.

### 8. CTE Case Cancellation (#80)

CTE cancels any previous active transition case before starting a new one for the same tenancy. Cascade: engine cancels case → casehub-work cancels associated WorkItems.

Extend `DesiredStateExecutionRegistry` to track the active case ID per tenancy:

```java
// In CTE.execute():
Optional<UUID> previousCaseId = executionRegistry.getActiveCaseId(tenancyId);
previousCaseId.ifPresent(id -> {
    try {
        caseHubRuntime.cancelCase(id);
    } catch (Exception e) {
        LOG.warnf("Failed to cancel previous case %s for tenancy %s — proceeding", id, tenancyId);
    }
});
// ... build and start new case ...
executionRegistry.setActiveCaseId(tenancyId, newCaseId);
```

**Lifecycle details:**

- **Cancellation failure:** Best-effort. If the previous case already completed or the call fails, log a warning and proceed with the new case. Cancellation failure means the case completed naturally or is unreachable — neither blocks the new transition.

- **Mapping cleanup:** The `tenancyId → caseId` mapping is bounded by tenancy count — each tenancy has at most one entry, overwritten when a new case starts. A `clearActiveCaseId(tenancyId)` method supports explicit cleanup when a tenancy is removed. Full completion-triggered cleanup requires case completion observation (V2 follow-up, tracked by existing issue #80).

- **Concurrency:** Reconciliation per-tenancy is serialized — the reconciliation loop processes one transition plan per tenancy at a time. Concurrent `getActiveCaseId`/`setActiveCaseId` for the same tenancy does not occur in normal operation.

Coordination scenarios:
- Provision case active, node removed → new case → previous cancelled → human provision WorkItems cancelled
- Deprovision case active, node re-added → new case → previous cancelled → human deprovision WorkItems cancelled

### 9. Pipeline Example Update

Update the pipeline example to demonstrate asymmetric gating:

- **Bronze/Silver:** `HumanGating.NONE` — fully automated
- **Gold:** `HumanGating.DEPROVISION_ONLY` — automated deployment (schema validation handles quality), human review before removal (data loss, compliance risk)

This maps to a real data pipeline pattern: deploying a new Gold-tier aggregation is safe, but removing one might break downstream consumers.

## Ripple Assessment

### This repo (mechanical, all in-scope)

| File | Change |
|------|--------|
| `DesiredNode` | `boolean requiresHuman` → `HumanGating humanGating` |
| `NodeSpec` | `requiresHuman()` → `humanGating()` |
| `SimpleTransitionExecutor` | Per-action routing + per-action span attributes |
| `CaseTransitionExecutor` | Per-action separation + case cancellation + `buildOptimisticResult` per-action check and corrected message |
| `DesiredStateExecutionRegistry` | Add `getActiveCaseId`/`setActiveCaseId`/`clearActiveCaseId` |
| `ImmutableDesiredStateGraph` | `withMutation` for UpdateNode uses adapted node directly (fixes latent merged-value bug) |
| `GraphMutation.UpdateNode` | `UpdateNode(NodeId, NodeSpec)` → `UpdateNode(NodeId, DesiredNode)` |
| `NoOpHumanNodeHandler` | No behavioral change (Skipped for both actions) |
| `WorkItemHumanNodeHandler` | Delete |
| `WorkItemHumanNodeHandlerTest` | Delete |
| `CreatureSpec` (dungeon) | `requiresHuman` → `humanGating` |
| `DungeonBlueprint` | `requiresHuman` → `humanGating` |
| `DungeonGoalCompiler` | Use `HumanGating.ALL`/`NONE` |
| Pipeline example specs | Per-action gating (DEPROVISION_ONLY for Gold) |
| All tests | `true`/`false` → `HumanGating.ALL`/`NONE` |
| `GraphDiff` | Full node equality comparison (not just spec); UpdateNode carries adapted DesiredNode |
| `MockHumanNodeHandler` (testing) | No behavioral change |

### Cross-repo (out of scope — tracked)

| Repo | Impact | Issue |
|------|--------|-------|
| `casehub-ops` | GoalCompiler constructs DesiredNodes — constructor change. Any NodeSpec implementations. | #82 |

### No cross-repo WorkItemCreator changes needed

The previous design required `findAllActiveByCallerRefPrefix` on `WorkItemCreator`. Case-lifecycle coordination eliminates this — the engine handles cancellation internally.

## Test Plan

### HumanGating unit tests
- All four states: `requiresHuman(PROVISION)`, `requiresHuman(DEPROVISION)`, `any()`
- `merge()` all 16 combinations (4×4)
- Switch exhaustiveness (compile-time)

### DesiredNode tests
- Node-level + spec-level merge: NONE/NONE→NONE, ALL/NONE→ALL, PROVISION_ONLY/DEPROVISION_ONLY→ALL
- `requiresHuman(action)` for each combination
- `requiresHuman()` convenience method
- Constructor validation (humanGating not null)

### GraphDiff tests
- Spec change only: generates UpdateNode with adapted node
- HumanGating change only (spec unchanged): generates UpdateNode
- Both spec and humanGating change: generates single UpdateNode
- No change (equal node): no mutation generated
- UpdateNode carries full adapted node with correct humanGating (no merged-value baking)

### STE tests
- `PROVISION_ONLY`: provision→handler, deprovision→provisioner
- `DEPROVISION_ONLY`: provision→provisioner, deprovision→handler
- `ALL`: both→handler
- `NONE`: both→provisioner
- Approval check interaction: per-action approval skipped only for the gated action
- Span attributes: `desiredstate.requires.human` reflects per-action check, `desiredstate.human.gating` contains enum name

### CTE tests
- Per-action separation: PROVISION_ONLY node in additions → human binding; same node type in removals → automated
- Mixed graph: ALL, PROVISION_ONLY, DEPROVISION_ONLY, NONE — correct separation in single plan
- `checkApproval`: per-action check (PROVISION_ONLY node in removal → approval check runs)
- `buildOptimisticResult`: per-action check (PROVISION_ONLY node in removals → Succeeded, same node in additions → Skipped with "routed to human task binding")
- Case cancellation: previous case cancelled before new case started
- Case cancellation failure: logged and proceeds with new case
- No previous case: no cancellation attempted

### Pipeline example tests
- Gold-tier nodes with DEPROVISION_ONLY: provision transitions are automated, deprovision transitions route to human path
- Fault escalation interaction: three-tier escalation produces `requiresHuman` nodes with correct gating
- `PipelineGoalCompiler` produces correct HumanGating per tier
