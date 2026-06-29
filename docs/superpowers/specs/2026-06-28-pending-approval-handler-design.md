# PendingApprovalHandler SPI — Design Spec

**Issue:** #14 — ReconciliationLoop PendingApproval workflow
**Date:** 2026-06-28
**Status:** Draft

## Problem

The reconciliation loop has no concept of in-flight transitions. When a provisioner returns `ProvisionResult.PendingApproval(nodeId, planReference)`, `SimpleTransitionExecutor` maps it to `StepOutcome.Skipped` — a dead end. No WorkItem is created, no approval is tracked, and no mechanism exists to re-call the provisioner with approval context once a human approves.

On the next reconciliation cycle, `ActualStateAdapter` reports the node as ABSENT (it was never provisioned), `TransitionPlanner` creates a new PROVISION step, and the provisioner is called again — with no knowledge that it already requested approval.

Three pieces are missing:

1. **State tracking** — something must know "we already asked for approval on this node"
2. **Approval flow** — something must create a WorkItem and detect completion
3. **Re-provision context** — `ProvisionContext` must carry `PlanApproval` so the provisioner proceeds

## Approach

New `PendingApprovalHandler` SPI, parallel to `HumanNodeHandler` but with different calling semantics:

- `HumanNodeHandler` **replaces** the provisioner — called instead of `provisioner.provision()`. The human provisions the node externally.
- `PendingApprovalHandler` **wraps** the provisioner — called before (to check approval status) and after (to record pending approval). The machine provisions after human approval.

`NodeStatus` stays pure — PRESENT, ABSENT, DRIFTED, UNKNOWN remain environment observations. Approval is workflow state, not environment state, and is tracked by the handler.

## New API Types

### PlanApproval

```java
package io.casehub.desiredstate.api;

public record PlanApproval(String planReference, String approvedBy, Instant approvedAt) {
    public PlanApproval {
        Objects.requireNonNull(planReference);
        Objects.requireNonNull(approvedBy);
        Objects.requireNonNull(approvedAt);
    }
}
```

### ApprovalCheckResult

```java
package io.casehub.desiredstate.api;

public sealed interface ApprovalCheckResult {
    record None() implements ApprovalCheckResult {}
    record Pending(String planReference) implements ApprovalCheckResult {}
    record Approved(PlanApproval approval) implements ApprovalCheckResult {}
    record Rejected(String planReference, String reason) implements ApprovalCheckResult {}
}
```

### PendingApprovalHandler

```java
package io.casehub.desiredstate.api;

public interface PendingApprovalHandler {
    ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId);
    StepOutcome recordPending(DesiredNode node, StepAction action, String tenancyId, String planReference);
    void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId);
}
```

Takes `DesiredNode` + `StepAction` + `tenancyId` rather than full context types. The handler only needs identity info for lookup/creation.

`acknowledgeRejection()` consumes a rejection so it doesn't block future cycles. Called by the executor after `check()` returns `Rejected`, before returning `StepOutcome.Rejected`. Without this, the REJECTED WorkItem persists at the same callerRef and `check()` returns `Rejected` every cycle — an infinite fault-event loop with no recovery path.

## Changes to Existing API Types

### ProvisionContext

Gains optional `PlanApproval`:

```java
public record ProvisionContext(String tenancyId, DesiredStateGraph graph, PlanApproval approval) {
    public ProvisionContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }

    public ProvisionContext(String tenancyId, DesiredStateGraph graph) {
        this(tenancyId, graph, null);
    }

    public boolean hasApproval() {
        return approval != null;
    }

    public ProvisionContext withApproval(PlanApproval approval) {
        return new ProvisionContext(this.tenancyId, this.graph, approval);
    }
}
```

### DeprovisionContext

Same treatment:

```java
public record DeprovisionContext(String tenancyId, DesiredStateGraph graph, PlanApproval approval) {
    public DeprovisionContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }

    public DeprovisionContext(String tenancyId, DesiredStateGraph graph) {
        this(tenancyId, graph, null);
    }

    public boolean hasApproval() {
        return approval != null;
    }

    public DeprovisionContext withApproval(PlanApproval approval) {
        return new DeprovisionContext(this.tenancyId, this.graph, approval);
    }
}
```

### FaultType

Add `APPROVAL_REJECTED`:

```java
public enum FaultType {
    NODE_DESTROYED,
    NODE_DEGRADED,
    PROVISION_FAILED,
    DEPROVISION_FAILED,
    HUMAN_NODE_TIMEOUT,
    DEPENDENCY_UNAVAILABLE,
    APPROVAL_REJECTED
}
```

### StepOutcome

Add `Rejected` variant for explicit approval rejection — semantically distinct from technical failure:

```java
public sealed interface StepOutcome {
    record Succeeded() implements StepOutcome {}
    record Failed(String reason) implements StepOutcome {}
    record Skipped(String reason) implements StepOutcome {}
    record Rejected(String reason) implements StepOutcome {}
}
```

Rejection is a human decision, not a system error. Keeping it separate from `Failed` means:
- The sealed interface has a distinct case — exhaustive `switch` forces explicit handling
- `faultFeedback()` pattern-matches `Rejected` → `APPROVAL_REJECTED` without FaultType leaking into the executor SPI
- `Failed` stays unchanged — no nullable field pollution

## Runtime Changes

### NoOpPendingApprovalHandler

`@DefaultBean @ApplicationScoped` in `runtime/`. Always returns `None` — system functions without work-adapter.

```java
@DefaultBean
@ApplicationScoped
public class NoOpPendingApprovalHandler implements PendingApprovalHandler {

    @Override
    public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
        return new ApprovalCheckResult.None();
    }

    @Override
    public StepOutcome recordPending(DesiredNode node, StepAction action,
                                      String tenancyId, String planReference) {
        return new StepOutcome.Failed(
            "pending approval: " + planReference + " — no PendingApprovalHandler configured");
    }

    @Override
    public void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId) {
        // no-op — no WorkItem to consume
    }
}
```

### SimpleTransitionExecutor

Modified `executeProvision()` flow:

**Design invariant:** `requiresHuman` takes precedence — `PendingApprovalHandler` is only invoked for automated nodes. A `requiresHuman` node delegates to `HumanNodeHandler`, which replaces the provisioner entirely. Since PendingApprovalHandler wraps the provisioner, there is no provisioner to wrap when `requiresHuman=true`.

1. If `node.requiresHuman()` → delegate to `humanNodeHandler` (unchanged)
2. `check = pendingApprovalHandler.check(node, PROVISION, tenancyId)`
3. Switch on check result:
   - `None` → create `ProvisionContext(tenancyId, graph)`, call provisioner
   - `Pending` → return `Skipped("pending approval: " + planRef)`
   - `Approved` → create `ProvisionContext(tenancyId, graph, approval)`, call provisioner
   - `Rejected` → call `handler.acknowledgeRejection(node, action, tenancyId)` (consumes the rejection so the next cycle returns `None`) → return `Rejected("approval rejected: " + reason)`
4. If provisioner returns `PendingApproval` → call `handler.recordPending()`

`executeDeprovision()` follows the same pattern with `StepAction.DEPROVISION` and `DeprovisionContext`. Note: the deprovision path intentionally has no `requiresHuman` check — deprovision is always automated (see `2026-06-26-workitem-human-node-handler-design.md` constraints). The `PendingApprovalHandler` check IS added to deprovision because approval-gated deprovision is a distinct concern: a provisioner may require approval before decommissioning a production resource, regardless of whether the node required human action to provision.

### ReconciliationLoop.faultFeedback()

**Early-exit guard update:** the existing guard checks only for `Failed` — must be extended to include `Rejected`:

```java
boolean hasFaultyOutcomes = result.outcomes().values().stream()
        .anyMatch(o -> o instanceof StepOutcome.Failed || o instanceof StepOutcome.Rejected);
if (!hasFaultyOutcomes) {
    return;
}
```

Without this, a cycle with only `Rejected` outcomes (no `Failed`) silently skips the fault pipeline — the rejection is acknowledged but the `APPROVAL_REJECTED` fault event is never created.

Pattern-matches two outcome types inside the loop:
- `StepOutcome.Rejected` → creates `FaultEvent` with `FaultType.APPROVAL_REJECTED`
- `StepOutcome.Failed` → uses existing classification (PROVISION_FAILED / DEPROVISION_FAILED based on removal-set membership)

`Failed` is unchanged — no FaultType field. The classification logic stays entirely within `faultFeedback()` where it belongs.

## Cross-Repo Prerequisite: casehubio/work#281

`WorkItemRef` — the type returned by `WorkItemCreator.findByCallerRef()` and `findActiveByCallerRef()` — has no `payload` field. The PendingApprovalHandler needs to round-trip `planReference` through the WorkItem lifecycle: stored in `WorkItemCreateRequest.payload` during `recordPending()`, extracted from the WorkItem during `check()` to construct `PlanApproval` and `ApprovalCheckResult.Rejected`.

work#281 adds `payload` as the 9th field on `WorkItemRef`:

```java
public record WorkItemRef(UUID id, WorkItemStatus status, String callerRef,
    String assigneeId, String resolution, String candidateGroups,
    String outcome, String tenancyId, String payload) {}
```

`WorkItemSpiAdapter.toRef()` updated to include `wi.payload`. Backwards-compatible addition — existing callers that destructure the record will get a compile error (desirable: forces them to handle the new field).

This prerequisite must ship before `WorkItemPendingApprovalHandler` can be implemented. Without it, the Approved and Rejected paths in `check()` cannot construct `PlanApproval` or `ApprovalCheckResult.Rejected` — both require `planReference`.

## Cross-Repo Prerequisite: casehubio/work#282

`WorkItemCreator` has no method to transition a WorkItem to OBSOLETE. The `acknowledgeRejection()` implementation needs to mark a REJECTED WorkItem as OBSOLETE so it no longer blocks the callerRef.

work#282 adds `obsoleteByCallerRef(String callerRef)` to `WorkItemCreator`:

```java
void obsoleteByCallerRef(String callerRef);
```

Marks the most recent terminal WorkItem with the given callerRef as OBSOLETE (terminal→OBSOLETE transition). `WorkItemService` allows this transition — OBSOLETE means "superseded/no longer relevant."

## Work-Adapter Changes

### WorkItemPendingApprovalHandler

`@ApplicationScoped` in `work-adapter/`, displaces `NoOpPendingApprovalHandler` by classpath presence.

**CallerRef convention:** `desiredstate-approval:<tenancyId>:<nodeId>:<action>` — distinct from HumanNodeHandler's `desiredstate:` prefix.

**check() logic:**
1. `findActiveByCallerRef(callerRef)` → if active WorkItem exists → return `Pending(ref.payload())` (planReference round-tripped via payload — requires work#281)
2. `findByCallerRef(callerRef)` → if terminal WorkItem exists, exhaustive switch on status:
   - `COMPLETED`:
     - outcome = `"Approve"` → return `Approved(PlanApproval)` where planReference = `ref.payload()`
     - otherwise (null, unrecognized) → log warning, return `None` (anomalous completion — force re-evaluation)
   - `REJECTED` → return `Rejected(ref.payload(), reason)` where:
     - `reason` — `Objects.requireNonNullElse(ref.resolution(), "rejected")`. Note: `WorkItemService.reject()` does not store the rejection reason on the item's `resolution` field — the `reason` parameter goes to audit/lifecycle events only. Detailed rejection reasons are in casehub-work's audit trail. The `reason` field exists for forward compatibility — if casehub-work evolves to store rejection reasons on WorkItemRef, the field is ready. For now, `APPROVAL_REJECTED` FaultType is what matters for fault policy routing.
   - `EXPIRED`, `CANCELLED`, `OBSOLETE` → return `None` (fresh start — provisioner may request approval again)
   - `FAULTED` → return `None` (system error during WorkItem processing — allow retry with new WorkItem)
   - `ESCALATED` → return `None` (terminal in the status enum — the escalation target manages the approval outside this WorkItem lifecycle; a new WorkItem is created if the provisioner requests approval again)
3. No WorkItem found → return `None`

The COMPLETED branch is effectively an approve-only guard. Since permitted outcomes are `Approve` and `Reject`, the human rejection path is `WorkItemService.reject()` → `REJECTED` status (not `COMPLETED + "Reject"` outcome). The COMPLETED branch confirms the outcome is `"Approve"` before returning `Approved`; any other COMPLETED outcome is anomalous.

ASSUMPTION: `findByCallerRef()` returns the most recently created WorkItem with the given callerRef. If a callerRef has multiple terminal WorkItems (e.g., first expired, second completed), the most recent must be returned. **Tracked as casehubio/work#280** — either document this as an API guarantee or add `findLatestByCallerRef()`.

**recordPending() logic:**
1. Idempotent check: `findActiveByCallerRef()` — if active, return Skipped with existing ID
2. Create WorkItem via `WorkItemCreator.create()`:
   - Title: `"Approve: <action> <nodeId>"`
   - Description: `"Approval required for <action> of node <nodeId> (type: <nodeType>) in tenancy <tenancyId>"`
   - Category: `desiredstate-approval`
   - CallerRef: `desiredstate-approval:<tenancyId>:<nodeId>:<action>`
   - Priority: HIGH
   - CreatedBy: `"desiredstate"`
   - TenancyId: `tenancyId` (multi-tenant routing)
   - PermittedOutcomes: Approve, Reject
   - Payload: planReference (for round-trip)
3. Return `Skipped("pending approval: WorkItem " + created.id())`

**acknowledgeRejection() logic:**
1. Build callerRef: `desiredstate-approval:<tenancyId>:<nodeId>:<action>`
2. Call `workItemCreator.obsoleteByCallerRef(callerRef)` — marks the REJECTED WorkItem as OBSOLETE (requires work#282)
3. Next cycle: `findByCallerRef()` returns the now-OBSOLETE WorkItem → maps to `None` → provisioner called → can request new approval

**PlanApproval population:**
- `planReference` — `ref.payload()` (round-tripped from provisioner via WorkItemCreateRequest.payload → WorkItemRef.payload; requires work#281)
- `approvedBy` — `Objects.requireNonNullElse(WorkItemRef.assigneeId(), "system")`. Normally the person who completed the WorkItem; falls back to `"system"` for system completions or bulk operations where no assignee is recorded. `PlanApproval.approvedBy` remains non-null for audit trail integrity.
- `approvedAt` — `Instant.now()` (observation time — WorkItemRef carries no completion timestamp)

## Testing Module

### MockPendingApprovalHandler

Programmable mock in `testing/`:

- `programCheck(NodeId, StepAction, ApprovalCheckResult)` — program check results
- `programRecordPending(NodeId, StepAction, StepOutcome)` — program recordPending results
- `check()` — returns programmed result or `None`
- `recordPending()` — records the call, returns programmed result or `Skipped`
- `acknowledgeRejection()` — records the call (no-op otherwise)
- `recorded()` — returns list of recorded pending approvals
- `acknowledgedRejections()` — returns list of acknowledged rejections

Both methods are programmable — consumers can test error paths (e.g., `programRecordPending(nodeId, PROVISION, new StepOutcome.Failed("creation error"))`) and verify their domain code handles failures correctly.

## CaseTransitionExecutor

No changes in this spec. `CaseTransitionExecutor` delegates provisioning to `DesiredStateWorkerFunction`, which calls `NodeProvisioner.provision()` with a plain `ProvisionContext` (no `PlanApproval`). If a provisioner returns `PendingApproval` under CTE:

1. `DesiredStateWorkerFunction` maps it to `Map.of("status", "PENDING_APPROVAL", ...)` — the engine has no mechanism to intercept this and create an approval gate.
2. `buildOptimisticResult()` reports all automated additions as `Succeeded`.
3. Next reconciliation cycle: `ActualStateAdapter` reports ABSENT → planner creates a new PROVISION step → provisioner called again without approval context.

This is a known gap — **tracked as #47**. The `PendingApprovalHandler` SPI is designed for polling (check each cycle), which fits `SimpleTransitionExecutor`. CTE's case-based execution model requires a different integration — potentially translating `PendingApproval` into the engine's HITL infrastructure (`HumanTaskTarget` bindings) or a case signal. Until resolved, provisioners under `CaseTransitionExecutor` must not return `PendingApproval`.

## Reconciliation Cycle Walkthrough

**Cycle 1 — Provisioner discovers approval needed:**
ActualStateAdapter reports ABSENT → planner creates PROVISION step → handler.check() returns None → provisioner returns PendingApproval → handler.recordPending() creates WorkItem → returns Skipped.

**Cycles 2..N — Waiting:**
ActualStateAdapter reports ABSENT → planner creates PROVISION step → handler.check() returns Pending → returns Skipped immediately. Provisioner not called.

**Cycle N+1 — Approved:**
handler.check() returns Approved → context enriched with PlanApproval → provisioner called with approval context → provisioner proceeds → Success. Next cycle: PRESENT, converged.

**Alternative — Rejected:**
handler.check() returns Rejected → executor calls handler.acknowledgeRejection() (marks REJECTED WorkItem as OBSOLETE) → returns Rejected("approval rejected: ...") → faultFeedback pattern-matches Rejected, creates FaultEvent with APPROVAL_REJECTED → FaultPolicyEngine evaluates → domain policy decides response. **Next cycle:** check() finds OBSOLETE WorkItem → None → provisioner called → can request new approval or fail based on updated context.

**Alternative — Expired/Cancelled:**
handler.check() returns None (terminal non-decision) → provisioner called fresh → may return PendingApproval again → new WorkItem cycle.

## Design Decisions

1. **Separate SPI from HumanNodeHandler** — different calling semantics (wrapping vs replacement). Shared infrastructure (WorkItemCreator, callerRef) stays at the implementation level in work-adapter/.

2. **NodeStatus unchanged** — PENDING_APPROVAL is workflow state, not environment state. Adding it would be a category error that muddies the ActualState abstraction.

3. **FaultType.APPROVAL_REJECTED** — rejection is semantically different from technical failure. Domain fault policies should distinguish "provisioner couldn't" from "human said no."

4. **StepOutcome.Rejected instead of Failed+FaultType** — rejection is a human decision, not a system error. A new sealed variant keeps layer concerns separate: the executor reports *what happened* (Rejected), the reconciliation loop classifies *how to handle it* (APPROVAL_REJECTED FaultType). No FaultType leaks into the executor SPI, and exhaustive `switch` on StepOutcome forces callers to handle the new case at compile time.

5. **Handler takes (DesiredNode, StepAction, tenancyId)** — minimal parameter set. Handler doesn't need the full graph or context, just identity info for lookup/creation.

6. **Observation time for approvedAt** — WorkItemRef has no completedAt field. Using Instant.now() is pragmatically correct: the reconciliation loop observed the approval at this instant. Exact human-click time is audit data (in casehub-work's own records), not reconciliation data.

7. **NoOpPendingApprovalHandler.recordPending() returns Failed, not Skipped** — a provisioner returning PendingApproval without a configured handler is a misconfiguration. `Failed` creates a fault event surfaced through the fault pipeline. `Skipped` would silently eat the problem, causing infinite re-invocation with no diagnostic.

8. **Plan staleness is the provisioner's responsibility** — when a provisioner receives `PlanApproval("plan-v1")` but the underlying desired spec has evolved, only the provisioner can judge plan freshness. If stale, it returns a new `PendingApproval("plan-v2")`. The handler/runtime cannot assess plan validity. There is no mechanism to proactively cancel stale WorkItems when desired state changes — a new WorkItem is created when the provisioner requests approval again after the stale WorkItem's approval is consumed.

9. **Deprovision path: no requiresHuman, yes PendingApprovalHandler** — deprovision is always automated for human nodes (see `2026-06-26-workitem-human-node-handler-design.md`). PendingApproval for deprovision is a distinct concern: approval before decommissioning a production resource, regardless of whether the node required human action for provisioning.

10. **requiresHuman takes precedence over PendingApprovalHandler** — `HumanNodeHandler` replaces the provisioner; `PendingApprovalHandler` wraps the provisioner. When `requiresHuman=true`, there is no provisioner to wrap, so PendingApprovalHandler is never consulted. A node cannot be both human-provisioned and approval-gated through this SPI.

11. **COMPLETED is an approve-only guard; REJECTED is the rejection path** — `WorkItemService.reject()` produces `REJECTED` status. `WorkItemService.complete()` produces `COMPLETED` status. Since permitted outcomes are `Approve` and `Reject`, COMPLETED should only appear with outcome `"Approve"`. Any other COMPLETED outcome is anomalous and maps to `None` with a warning log.

12. **planReference round-trips via WorkItemRef.payload()** — `WorkItemCreateRequest.payload` stores the provisioner's `planReference` during `recordPending()`. `WorkItemRef.payload()` (after work#281 ships) exposes it during `check()`. This is the only data the handler needs to round-trip — all other PlanApproval fields (`approvedBy`, `approvedAt`) are derived from WorkItemRef fields or observation time. Alternatives considered: embedding in callerRef (breaks lookup — check() doesn't know planReference before calling provisioner), overloading resolution field (semantic conflict — resolution is overwritten by `complete()` and used for rejection reason).

13. **Rejection is consumed on detection via acknowledgeRejection()** — without consumption, a REJECTED WorkItem blocks the callerRef permanently: every cycle, `check()` finds the same REJECTED WorkItem, returns `Rejected`, and the provisioner is never called again. The executor calls `acknowledgeRejection()` after `check()` returns `Rejected`, before returning `StepOutcome.Rejected`. The work-adapter implementation marks the WorkItem as OBSOLETE via `workItemCreator.obsoleteByCallerRef()` (requires work#282). Next cycle: OBSOLETE → `None` → provisioner called → can request new approval. The fault event fires exactly once per rejection — no infinite loop, and the provisioner gets a fresh start.

14. **Rejected reason is forward-compatible, not currently functional** — `WorkItemService.reject()` does not store the rejection reason on `WorkItem.resolution` — the `reason` parameter goes to audit/lifecycle events only. With current casehub-work, `ApprovalCheckResult.Rejected.reason()` will always be the default `"rejected"`. The field exists to model the domain correctly (a rejection has a reason) and for forward compatibility if casehub-work evolves. The `APPROVAL_REJECTED` FaultType — not the reason string — is what drives fault policy routing.

## NodeProvisioner SPI Documentation

The `NodeProvisioner` interface Javadoc must document the re-entry protocol introduced by this spec:

- `provision()` may return `PendingApproval(nodeId, planReference)` to request human approval before proceeding
- If approval is granted, `provision()` will be called again with `context.approval()` non-null, carrying the `PlanApproval` (planReference, approvedBy, approvedAt)
- Provisioners should check `context.hasApproval()` and behave accordingly: proceed with the approved plan, or return a new `PendingApproval` if the plan is stale
- The `planReference` returned in `PendingApproval` is opaque to the runtime — it is round-tripped back to the provisioner unchanged

Same protocol applies to `deprovision()` via `DeprovisionContext.approval()`.

## Out-of-Scope

- **PLATFORM.md update** — tracked as #45
- **Pipeline example enhancement** — tracked as #46
- **WorkItemRef timestamp enrichment** — casehub-work concern, not desiredstate
- **CaseTransitionExecutor PendingApproval integration** — tracked as #47
- **ARC42STORIES.MD placement** — tracked as #48
- **findByCallerRef ordering semantics** — tracked as casehubio/work#280
- **WorkItemRef payload field** — prerequisite, tracked as casehubio/work#281
- **WorkItemCreator.obsoleteByCallerRef** — prerequisite, tracked as casehubio/work#282
