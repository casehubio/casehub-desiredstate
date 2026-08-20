# WorkItemPendingApprovalHandler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #81 — WorkItemPendingApprovalHandler in work-adapter module
**Issue group:** #81, #82

**Goal:** Implement a WorkItem-backed PendingApprovalHandler in work-adapter that
delegates approval lifecycle to casehub-work's WorkItemCreator SPI, and close #82
(casehub-ops DesiredNode constructor migration — already done).

**Architecture:** Stateless delegator pattern — `WorkItemPendingApprovalHandler`
injects `WorkItemCreator` and maps its API directly onto the `PendingApprovalHandler`
SPI. No internal state, thread-safe by construction. Classpath-activated via
`@ApplicationScoped`, displacing `NoOpPendingApprovalHandler` (`@DefaultBean`).

**Tech Stack:** Java 21, Quarkus CDI, casehub-desiredstate-api, casehub-work-api

## Global Constraints

- CallerRef format: `desiredstate-approval:<tenancyId>:<nodeId>:<action>`
- `check()` uses `findByCallerRef` (not `findActiveByCallerRef`) — must see COMPLETED and REJECTED
- `check()` does NOT self-clean on COMPLETED — idempotent across cycles for transient failure retry
- `acknowledgeRejection()` calls `obsoleteByCallerRef` — prevents permanent callerRef blocking
- `recordPending()` calls `obsoleteByCallerRef` before `create` — clears stale prior WorkItem
- `PlanApproval.approvedBy` falls back to `"system"` when `assigneeId` is null
- Rejection reason includes `WorkItemRef.outcome()` when non-null

---

### Task 1: WorkItemPendingApprovalHandler — TDD implementation

**Files:**
- Create: `work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandler.java`
- Create: `work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandlerTest.java`
- Modify: `work-adapter/pom.xml` (description only)

**Interfaces:**
- Consumes: `PendingApprovalHandler` SPI (api), `WorkItemCreator` SPI (casehub-work-api)
- Produces: `WorkItemPendingApprovalHandler` CDI bean — no downstream tasks depend on this

- [ ] **Step 1: Update pom.xml description**

Use the Edit tool on `work-adapter/pom.xml`. Replace the stale description:

Old:
```
WorkItem-backed HumanNodeHandler — creates WorkItems for requiresHuman
        nodes via the WorkItemCreator SPI. Displaces NoOpHumanNodeHandler by classpath
        presence when casehub-work is deployed.
```

New:
```
WorkItem-backed PendingApprovalHandler — creates approval WorkItems via
        the WorkItemCreator SPI. Displaces NoOpPendingApprovalHandler by classpath
        presence when casehub-work is deployed.
```

- [ ] **Step 2: Create the test class with InMemoryWorkItemCreator**

Use `ide_create_file` to create `work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandlerTest.java`:

```java
package io.casehub.desiredstate.work;

import io.casehub.desiredstate.api.*;
import io.casehub.work.api.*;
import io.casehub.work.api.spi.WorkItemCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemPendingApprovalHandlerTest {

    private static final NodeId NODE_1 = NodeId.of("node-1");
    private static final NodeType TYPE = NodeType.of("test");
    private static final NodeSpec SPEC = new StubSpec();
    private static final String TENANCY = "tenant-1";
    private static final String PLAN_REF = "plan-abc-123";

    private InMemoryWorkItemCreator creator;
    private WorkItemPendingApprovalHandler handler;

    @BeforeEach
    void setUp() {
        creator = new InMemoryWorkItemCreator();
        handler = new WorkItemPendingApprovalHandler(creator);
    }

    @Test
    void check_noWorkItem_returnsNone() {
        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.None.class);
    }

    @Test
    void check_activeWorkItem_returnsPending() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Pending.class);
        assertThat(((ApprovalCheckResult.Pending) result).planReference()).isEqualTo(PLAN_REF);
    }

    @Test
    void check_completedWorkItem_returnsApprovedAndDoesNotObsolete() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatusAndAssignee(callerRef, WorkItemStatus.COMPLETED, "alice");

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Approved.class);
        var approved = (ApprovalCheckResult.Approved) result;
        assertThat(approved.approval().planReference()).isEqualTo(PLAN_REF);
        assertThat(approved.approval().approvedBy()).isEqualTo("alice");
        assertThat(approved.approval().approvedAt()).isNotNull();

        // Verify NOT obsoleted — second check should still return Approved
        var secondCheck = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(secondCheck).isInstanceOf(ApprovalCheckResult.Approved.class);
    }

    @Test
    void check_completedWorkItem_nullAssignee_usesSystemFallback() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatus(callerRef, WorkItemStatus.COMPLETED);

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Approved.class);
        assertThat(((ApprovalCheckResult.Approved) result).approval().approvedBy()).isEqualTo("system");
    }

    @Test
    void check_rejectedWorkItem_returnsRejectedWithOutcome() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatusAndOutcome(callerRef, WorkItemStatus.REJECTED, "policy-violation");

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Rejected.class);
        var rejected = (ApprovalCheckResult.Rejected) result;
        assertThat(rejected.planReference()).isEqualTo(PLAN_REF);
        assertThat(rejected.reason()).contains("rejected");
        assertThat(rejected.reason()).contains("policy-violation");
    }

    @Test
    void check_obsoleteWorkItem_returnsNone() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatus(callerRef, WorkItemStatus.OBSOLETE);

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.None.class);
    }

    @Test
    void recordPending_createsWorkItemWithCorrectCallerRefAndPayload() {
        var outcome = handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);

        assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) outcome).reason()).contains(PLAN_REF);

        assertThat(creator.created).hasSize(1);
        var request = creator.created.get(0);
        assertThat(request.callerRef).isEqualTo(
                "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION");
        assertThat(request.payload).isEqualTo(PLAN_REF);
        assertThat(request.tenancyId).isEqualTo(TENANCY);
        assertThat(request.title).isEqualTo("Approve provision: node-1");
        assertThat(request.types).containsExactly("desiredstate-approval");
        assertThat(request.permittedOutcomes).hasSize(2);
    }

    @Test
    void acknowledgeRejection_obsoletesCallerRef() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatus(callerRef, WorkItemStatus.REJECTED);

        handler.acknowledgeRejection(node(), StepAction.PROVISION, TENANCY);

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.None.class);
    }

    // --- helpers ---

    private DesiredNode node() {
        return new DesiredNode(NODE_1, SPEC, HumanGating.NONE);
    }

    private record StubSpec() implements NodeSpec {}

    // --- in-memory WorkItemCreator for testing ---

    static class InMemoryWorkItemCreator implements WorkItemCreator {
        final List<WorkItemCreateRequest> created = new ArrayList<>();
        private final ConcurrentHashMap<String, WorkItemRef> byCallerRef = new ConcurrentHashMap<>();

        @Override
        public WorkItemRef create(WorkItemCreateRequest request) {
            created.add(request);
            var ref = new WorkItemRef(UUID.randomUUID(), WorkItemStatus.PENDING,
                    request.callerRef, null, null, request.candidateGroups,
                    null, request.tenancyId, request.payload);
            byCallerRef.put(request.callerRef, ref);
            return ref;
        }

        @Override
        public Optional<WorkItemRef> findByCallerRef(String callerRef) {
            return Optional.ofNullable(byCallerRef.get(callerRef));
        }

        @Override
        public Optional<WorkItemRef> findActiveByCallerRef(String callerRef) {
            return findByCallerRef(callerRef).filter(r -> r.status().isActive());
        }

        @Override
        public void obsoleteByCallerRef(String callerRef) {
            byCallerRef.computeIfPresent(callerRef, (k, ref) ->
                    new WorkItemRef(ref.id(), WorkItemStatus.OBSOLETE, ref.callerRef(),
                            ref.assigneeId(), ref.resolution(), ref.candidateGroups(),
                            ref.outcome(), ref.tenancyId(), ref.payload()));
        }

        void setStatus(String callerRef, WorkItemStatus status) {
            byCallerRef.computeIfPresent(callerRef, (k, ref) ->
                    new WorkItemRef(ref.id(), status, ref.callerRef(),
                            ref.assigneeId(), ref.resolution(), ref.candidateGroups(),
                            ref.outcome(), ref.tenancyId(), ref.payload()));
        }

        void setStatusAndAssignee(String callerRef, WorkItemStatus status, String assigneeId) {
            byCallerRef.computeIfPresent(callerRef, (k, ref) ->
                    new WorkItemRef(ref.id(), status, ref.callerRef(),
                            assigneeId, ref.resolution(), ref.candidateGroups(),
                            ref.outcome(), ref.tenancyId(), ref.payload()));
        }

        void setStatusAndOutcome(String callerRef, WorkItemStatus status, String outcome) {
            byCallerRef.computeIfPresent(callerRef, (k, ref) ->
                    new WorkItemRef(ref.id(), status, ref.callerRef(),
                            ref.assigneeId(), ref.resolution(), ref.candidateGroups(),
                            outcome, ref.tenancyId(), ref.payload()));
        }
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl work-adapter -Dtest=WorkItemPendingApprovalHandlerTest`
Expected: COMPILATION ERROR — `WorkItemPendingApprovalHandler` does not exist yet.

- [ ] **Step 4: Create WorkItemPendingApprovalHandler**

Use `ide_create_file` to create `work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandler.java`:

```java
package io.casehub.desiredstate.work;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.PlanApproval;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.work.api.Outcome;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class WorkItemPendingApprovalHandler implements PendingApprovalHandler {

    private final WorkItemCreator workItemCreator;

    @Inject
    public WorkItemPendingApprovalHandler(WorkItemCreator workItemCreator) {
        this.workItemCreator = workItemCreator;
    }

    @Override
    public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
        String callerRef = callerRef(node.id(), action, tenancyId);
        return workItemCreator.findByCallerRef(callerRef)
                .map(ref -> mapStatus(ref, node.id()))
                .orElse(new ApprovalCheckResult.None());
    }

    @Override
    public StepOutcome recordPending(DesiredNode node, StepAction action,
                                      String tenancyId, String planReference) {
        String callerRef = callerRef(node.id(), action, tenancyId);
        workItemCreator.obsoleteByCallerRef(callerRef);
        workItemCreator.create(WorkItemCreateRequest.builder()
                .title("Approve " + action.name().toLowerCase() + ": " + node.id().value())
                .callerRef(callerRef)
                .payload(planReference)
                .tenancyId(tenancyId)
                .types(List.of("desiredstate-approval"))
                .permittedOutcomes(List.of(
                        new Outcome("approve", "Approve", null),
                        new Outcome("reject", "Reject", null)))
                .build());
        return new StepOutcome.Skipped("pending approval: " + planReference);
    }

    @Override
    public void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId) {
        workItemCreator.obsoleteByCallerRef(callerRef(node.id(), action, tenancyId));
    }

    private ApprovalCheckResult mapStatus(WorkItemRef ref, NodeId nodeId) {
        return switch (ref.status()) {
            case COMPLETED -> new ApprovalCheckResult.Approved(new PlanApproval(
                    ref.payload() != null ? ref.payload() : "",
                    ref.assigneeId() != null ? ref.assigneeId() : "system",
                    Instant.now()));
            case REJECTED, CANCELLED, EXPIRED, FAULTED, ESCALATED -> {
                String reason = "approval " + ref.status().name().toLowerCase()
                        + " for node " + nodeId.value();
                if (ref.outcome() != null && !ref.outcome().isBlank()) {
                    reason += ": " + ref.outcome();
                }
                yield new ApprovalCheckResult.Rejected(
                        ref.payload() != null ? ref.payload() : "", reason);
            }
            case OBSOLETE -> new ApprovalCheckResult.None();
            default -> new ApprovalCheckResult.Pending(
                    ref.payload() != null ? ref.payload() : "");
        };
    }

    private String callerRef(NodeId nodeId, StepAction action, String tenancyId) {
        return "desiredstate-approval:" + tenancyId + ":" + nodeId.value()
                + ":" + action.name();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl work-adapter -Dtest=WorkItemPendingApprovalHandlerTest`
Expected: 8 tests PASS.

- [ ] **Step 6: Verify with IntelliJ diagnostics**

Run: `ide_diagnostics` on both files. Expected: 0 errors.
Run: `ide_build_project` on desiredstate. Expected: build success.

- [ ] **Step 7: Commit**

```bash
git add work-adapter/pom.xml \
  work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandler.java \
  work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandlerTest.java
git commit -m "feat(#81): WorkItemPendingApprovalHandler — WorkItem-backed approval lifecycle"
```

---

### Task 2: ARC42STORIES update + close #82

**Files:**
- Modify: `ARC42STORIES.MD` (lines 501, 511, 971, 979, 982, 986-987)

**Interfaces:**
- Consumes: Task 1 (handler exists and tests pass)
- Produces: None

- [ ] **Step 1: Update ARC42STORIES.MD**

Six edits needed using the Edit tool:

1. **Line 501** — Check off WorkItem-backed approval handler, remove blocker:
   - Old: `- 🔲 WorkItem-backed approval handler (work-adapter) — blocked on work#281/282`
   - New: `- ✅ WorkItem-backed approval handler (work-adapter)`

2. **Line 511** — Update layer impact status:
   - Old: `| L7 Work Adapter | Medium — 🔲 WorkItemPendingApprovalHandler (blocked on work#281/282) |`
   - New: `| L7 Work Adapter | Medium — ✅ WorkItemPendingApprovalHandler |`

3. **Line 971** — Mark layer completed:
   - Old: `**Completed:** 🔲 (blocked on casehubio/work#281, casehubio/work#282)`
   - New: `**Completed:** ✅`

4. **Line 979** — Check off WorkItemPendingApprovalHandler, update callerRef and remove GE ref:
   - Old: `- 🔲 **WorkItemPendingApprovalHandler** — creates a WorkItem via `WorkItemCreator` SPI when provisioner returns PendingApproval; polls `findByCallerRef()` each cycle; maps COMPLETED→Approved, REJECTED→Rejected (with `obsoleteByCallerRef` cleanup — GE-20260629-45f4be); displaces `NoOpPendingApprovalHandler` by classpath presence`
   - New: `- ✅ **WorkItemPendingApprovalHandler** — creates a WorkItem via `WorkItemCreator` SPI when provisioner returns PendingApproval; polls `findByCallerRef()` each cycle; maps COMPLETED→Approved, REJECTED→Rejected (with `obsoleteByCallerRef` cleanup); displaces `NoOpPendingApprovalHandler` by classpath presence`

5. **Lines 982** — Remove blocker note:
   - Old: `Not closed here: blocked on casehubio/work#281 (`WorkItemRef.payload` field for data round-trip) and casehubio/work#282 (`WorkItemCreator.obsoleteByCallerRef` for rejection consumption).`
   - New: (delete this line)

6. **Lines 986-987** — Update key files (WorkItemHumanNodeHandler was removed in #72):
   - Old:
     ```
     - `work-adapter/src/.../WorkItemHumanNodeHandler.java` — existing, creates WorkItems for requiresHuman nodes (provision and deprovision)
     - 🔲 `work-adapter/src/.../WorkItemPendingApprovalHandler.java` — pending implementation
     ```
   - New:
     ```
     - ✅ `work-adapter/src/.../WorkItemPendingApprovalHandler.java` — WorkItem-backed approval lifecycle
     ```

- [ ] **Step 2: Commit ARC42STORIES update**

```bash
git add ARC42STORIES.MD
git commit -m "docs(#81): update ARC42STORIES — L7 WorkItemPendingApprovalHandler complete"
```

- [ ] **Step 3: Close #82**

```bash
gh issue close 82 --repo casehubio/casehub-desiredstate --comment "Already done — all casehub-ops GoalCompilers use 4-arg DesiredNode with HumanGating. Verified: Deployment (NONE), App (NONE), Infra (NONE), IoT (ALL/NONE), Compliance (dynamic). Build passes clean."
```

- [ ] **Step 4: Update CLAUDE.md**

Update `## What's Left` and `## What's Next` references if #81/#82 appear there.
Update `work-adapter/` module description in the Module Structure table — the current
description says "Future home of WorkItemPendingApprovalHandler (issue #81)". Update to
reflect it's implemented.
