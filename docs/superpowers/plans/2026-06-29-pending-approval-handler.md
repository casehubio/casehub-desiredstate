# PendingApprovalHandler SPI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the PendingApprovalHandler SPI so that when a provisioner returns `PendingApproval`, the runtime creates a WorkItem, tracks approval status across cycles, and re-calls the provisioner with `PlanApproval` context once approved.

**Architecture:** New SPI in api/ (`PendingApprovalHandler`) wraps the provisioner — called before (to check approval status) and after (to record pending). `NoOpPendingApprovalHandler` @DefaultBean in runtime/. `WorkItemPendingApprovalHandler` in work-adapter/. New sealed variant `StepOutcome.Rejected` for human-decision rejections. `FaultType.APPROVAL_REJECTED` for fault policy routing.

**Tech Stack:** Java 22, Quarkus 3.32, Mutiny, JUnit 5, AssertJ, casehub-work-api

**Spec:** `docs/superpowers/specs/2026-06-28-pending-approval-handler-design.md`

## Global Constraints

- All new SPI types go in `api/src/main/java/io/casehub/desiredstate/api/` — pure Java, no Quarkus runtime deps
- `@DefaultBean` no-op implementations go in `runtime/src/main/java/io/casehub/desiredstate/runtime/`
- Testing mocks go in `testing/src/main/java/io/casehub/desiredstate/testing/`
- Work-adapter code goes in `work-adapter/src/main/java/io/casehub/desiredstate/work/`
- Task 5 (work-adapter) is BLOCKED on casehubio/work#281 (WorkItemRef.payload) and work#282 (obsoleteByCallerRef) — implement after those prerequisites ship
- Tests follow existing conventions: JUnit 5, AssertJ assertions, inner mock classes, `UniAssertSubscriber` for Mutiny
- No Mockito — all mocks are hand-written implementations

---

### Task 1: API types — PlanApproval, ApprovalCheckResult, PendingApprovalHandler, StepOutcome.Rejected, FaultType.APPROVAL_REJECTED, context changes

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/PlanApproval.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ApprovalCheckResult.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/PendingApprovalHandler.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/StepOutcome.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/FaultType.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/ProvisionContext.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/DeprovisionContext.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/NodeProvisioner.java`
- Modify: `api/src/test/java/io/casehub/desiredstate/api/TypesTest.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/TypesTest.java`

**Interfaces:**
- Produces: `PlanApproval(String planReference, String approvedBy, Instant approvedAt)` — used by Tasks 2-5
- Produces: `ApprovalCheckResult` sealed with None/Pending/Approved/Rejected — used by Tasks 2-5
- Produces: `PendingApprovalHandler` interface with `check()`, `recordPending()`, `acknowledgeRejection()` — used by Tasks 2-5
- Produces: `StepOutcome.Rejected(String reason)` — used by Tasks 2, 3
- Produces: `FaultType.APPROVAL_REJECTED` — used by Task 3
- Produces: `ProvisionContext.withApproval(PlanApproval)` — used by Tasks 2, 5
- Produces: `DeprovisionContext.withApproval(PlanApproval)` — used by Tasks 2, 5

- [ ] **Step 1: Write failing tests for new types**

Add tests to `api/src/test/java/io/casehub/desiredstate/api/TypesTest.java`:

```java
@Test void planApproval_fields() {
    var approval = new PlanApproval("plan-42", "jane", Instant.parse("2026-06-28T14:30:00Z"));
    assertThat(approval.planReference()).isEqualTo("plan-42");
    assertThat(approval.approvedBy()).isEqualTo("jane");
    assertThat(approval.approvedAt()).isEqualTo(Instant.parse("2026-06-28T14:30:00Z"));
}

@Test void planApproval_rejectsNulls() {
    assertThatThrownBy(() -> new PlanApproval(null, "jane", Instant.now()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PlanApproval("plan", null, Instant.now()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PlanApproval("plan", "jane", null))
        .isInstanceOf(NullPointerException.class);
}

@Test void approvalCheckResult_sealedExhaustive() {
    ApprovalCheckResult result = new ApprovalCheckResult.None();
    String out = switch (result) {
        case ApprovalCheckResult.None n -> "none";
        case ApprovalCheckResult.Pending p -> "pending:" + p.planReference();
        case ApprovalCheckResult.Approved a -> "approved:" + a.approval().approvedBy();
        case ApprovalCheckResult.Rejected r -> "rejected:" + r.reason();
    };
    assertThat(out).isEqualTo("none");
}

@Test void stepOutcome_rejected() {
    StepOutcome outcome = new StepOutcome.Rejected("human said no");
    assertThat(outcome).isInstanceOf(StepOutcome.Rejected.class);
    assertThat(((StepOutcome.Rejected) outcome).reason()).isEqualTo("human said no");
}

@Test void provisionContext_withApproval() {
    var graph = new ImmutableDesiredStateGraph(Map.of(), Map.of(), 0);
    var ctx = new ProvisionContext("t1", graph);
    assertThat(ctx.hasApproval()).isFalse();
    assertThat(ctx.approval()).isNull();

    var approval = new PlanApproval("plan-1", "jane", Instant.now());
    var enriched = ctx.withApproval(approval);
    assertThat(enriched.hasApproval()).isTrue();
    assertThat(enriched.approval()).isEqualTo(approval);
    assertThat(enriched.tenancyId()).isEqualTo("t1");
    assertThat(enriched.graph()).isSameAs(graph);
}

@Test void deprovisionContext_withApproval() {
    var graph = new ImmutableDesiredStateGraph(Map.of(), Map.of(), 0);
    var ctx = new DeprovisionContext("t1", graph);
    assertThat(ctx.hasApproval()).isFalse();

    var approval = new PlanApproval("plan-1", "jane", Instant.now());
    var enriched = ctx.withApproval(approval);
    assertThat(enriched.hasApproval()).isTrue();
    assertThat(enriched.approval()).isEqualTo(approval);
}
```

Update the existing `stepOutcome_sealed` test to include `Rejected`:

```java
@Test void stepOutcome_sealed() {
    StepOutcome outcome = new StepOutcome.Failed("boom");
    String result = switch (outcome) {
        case StepOutcome.Succeeded s -> "ok";
        case StepOutcome.Failed f -> "fail:" + f.reason();
        case StepOutcome.Skipped s -> "skip:" + s.reason();
        case StepOutcome.Rejected r -> "reject:" + r.reason();
    };
    assertThat(result).isEqualTo("fail:boom");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=TypesTest`
Expected: compilation errors — `PlanApproval`, `ApprovalCheckResult`, `StepOutcome.Rejected` don't exist yet.

- [ ] **Step 3: Create PlanApproval.java**

```java
package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;

public record PlanApproval(String planReference, String approvedBy, Instant approvedAt) {
    public PlanApproval {
        Objects.requireNonNull(planReference, "planReference must not be null");
        Objects.requireNonNull(approvedBy, "approvedBy must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");
    }
}
```

- [ ] **Step 4: Create ApprovalCheckResult.java**

```java
package io.casehub.desiredstate.api;

public sealed interface ApprovalCheckResult {
    record None() implements ApprovalCheckResult {}
    record Pending(String planReference) implements ApprovalCheckResult {}
    record Approved(PlanApproval approval) implements ApprovalCheckResult {}
    record Rejected(String planReference, String reason) implements ApprovalCheckResult {}
}
```

- [ ] **Step 5: Create PendingApprovalHandler.java**

```java
package io.casehub.desiredstate.api;

/**
 * Handles approval lifecycle for nodes whose provisioner returns PendingApproval.
 * Wraps the provisioner — called before (check) and after (recordPending) provisioner.provision().
 *
 * <p>Contrast with {@link HumanNodeHandler} which replaces the provisioner entirely.
 * PendingApprovalHandler is for automated nodes that need human approval before the machine provisions.
 */
public interface PendingApprovalHandler {
    ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId);
    StepOutcome recordPending(DesiredNode node, StepAction action, String tenancyId, String planReference);
    void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId);
}
```

- [ ] **Step 6: Add Rejected to StepOutcome**

Replace the contents of `api/src/main/java/io/casehub/desiredstate/api/StepOutcome.java`:

```java
package io.casehub.desiredstate.api;

public sealed interface StepOutcome {
    record Succeeded() implements StepOutcome {}
    record Failed(String reason) implements StepOutcome {}
    record Skipped(String reason) implements StepOutcome {}
    record Rejected(String reason) implements StepOutcome {}
}
```

- [ ] **Step 7: Add APPROVAL_REJECTED to FaultType**

Replace the contents of `api/src/main/java/io/casehub/desiredstate/api/FaultType.java`:

```java
package io.casehub.desiredstate.api;

public enum FaultType {
    NODE_DESTROYED, NODE_DEGRADED, PROVISION_FAILED, DEPROVISION_FAILED,
    HUMAN_NODE_TIMEOUT, DEPENDENCY_UNAVAILABLE, APPROVAL_REJECTED
}
```

- [ ] **Step 8: Update ProvisionContext with PlanApproval**

Replace the contents of `api/src/main/java/io/casehub/desiredstate/api/ProvisionContext.java`:

```java
package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * Context passed to a provisioner when provisioning a node.
 * Carries tenancy identity, the full desired-state graph for reference,
 * and optional approval context when re-entering after a PendingApproval cycle.
 */
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

- [ ] **Step 9: Update DeprovisionContext with PlanApproval**

Replace the contents of `api/src/main/java/io/casehub/desiredstate/api/DeprovisionContext.java`:

```java
package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * Context passed to a provisioner when deprovisioning a node.
 * Carries tenancy identity, the full desired-state graph for reference,
 * and optional approval context when re-entering after a PendingApproval cycle.
 */
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

- [ ] **Step 10: Update NodeProvisioner Javadoc**

Replace the contents of `api/src/main/java/io/casehub/desiredstate/api/NodeProvisioner.java`:

```java
package io.casehub.desiredstate.api;

/**
 * SPI for provisioning and deprovisioning nodes in the desired-state graph.
 *
 * <p><b>Re-entry protocol for PendingApproval:</b>
 * <ul>
 *   <li>{@code provision()} may return {@code PendingApproval(nodeId, planReference)}
 *       to request human approval before proceeding.</li>
 *   <li>If approval is granted, {@code provision()} will be called again with
 *       {@code context.approval()} non-null, carrying the {@link PlanApproval}
 *       (planReference, approvedBy, approvedAt).</li>
 *   <li>Provisioners should check {@code context.hasApproval()} and behave accordingly:
 *       proceed with the approved plan, or return a new {@code PendingApproval} if
 *       the plan is stale.</li>
 *   <li>The {@code planReference} returned in {@code PendingApproval} is opaque to the
 *       runtime — it is round-tripped back to the provisioner unchanged.</li>
 * </ul>
 *
 * <p>Same protocol applies to {@code deprovision()} via
 * {@link DeprovisionContext#approval()}.
 */
public interface NodeProvisioner {
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}
```

- [ ] **Step 11: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=TypesTest`
Expected: all tests PASS. The `ImmutableDesiredStateGraph` import may need adding to TypesTest. Add `import io.casehub.desiredstate.runtime.ImmutableDesiredStateGraph;` — but that creates a cross-module dependency from api test to runtime. Instead, use the factory:

The `provisionContext_withApproval` and `deprovisionContext_withApproval` tests need a graph. Since api tests can't depend on runtime, use a minimal approach — create the graph via a test-local stub or pass `null` (but graph is non-null enforced). Solution: add `casehub-desiredstate-testing` as test dep in api/pom.xml (it's already a testing module), or create a minimal inline implementation. Check if api/ tests already have a graph available.

Looking at existing TypesTest: it doesn't test ProvisionContext at all currently. The new tests need a DesiredStateGraph instance. The cleanest approach: add `casehub-desiredstate` (runtime) as a test-scope dependency to api/ (not ideal for tier rules). Better: use `casehub-desiredstate-testing` which already depends on api/. But testing/ doesn't provide a graph factory either.

Pragmatic solution: create a minimal `TestGraph` inner class in TypesTest that implements `DesiredStateGraph` for testing purposes, or add the runtime as test-scope (it's already done in the existing testing/pom.xml pattern).

Actually, looking at api/pom.xml — it has no test dep on runtime. The cleanest approach: in the test, just verify the record behavior without needing a real graph. Use a test-double graph:

```java
// In TypesTest, add a minimal graph test double:
private static final DesiredStateGraph EMPTY_GRAPH = new DesiredStateGraph() {
    // implement required methods with empty defaults
};
```

Or simpler: since `ProvisionContext` just stores the graph reference, any graph will do. The api/ tests can create a graph from the factory — but `DefaultDesiredStateGraphFactory` is in runtime/. 

Best option: add `casehub-desiredstate-testing` as test-scope dep to api/pom.xml (the testing module depends only on api/ — no circular dep). Then use `MockActualStateAdapter` or create a trivial graph. Actually, testing/ doesn't export a graph factory either.

Final approach: add `casehub-desiredstate` (runtime) as test-scope dep to api/pom.xml. This is the same pattern work-adapter/ uses. Then use `DefaultDesiredStateGraphFactory` in tests.

- [ ] **Step 12: Commit**

```
feat(#14): api types — PlanApproval, ApprovalCheckResult, PendingApprovalHandler, StepOutcome.Rejected
```

---

### Task 2: Runtime — NoOpPendingApprovalHandler + SimpleTransitionExecutor approval flow

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpPendingApprovalHandler.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java`

**Interfaces:**
- Consumes: `PendingApprovalHandler.check()`, `PendingApprovalHandler.recordPending()`, `PendingApprovalHandler.acknowledgeRejection()` from Task 1
- Consumes: `ApprovalCheckResult` sealed variants from Task 1
- Consumes: `ProvisionContext.withApproval()`, `DeprovisionContext.withApproval()` from Task 1
- Consumes: `StepOutcome.Rejected` from Task 1
- Produces: `NoOpPendingApprovalHandler` @DefaultBean — used by downstream tests
- Produces: Modified `SimpleTransitionExecutor` — executor now delegates to handler

- [ ] **Step 1: Write failing tests for approval flow**

Add to `runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java`:

```java
@Test
void pendingApproval_noHandler_returnsFailed() {
    // NoOp handler returns Failed when provisioner returns PendingApproval
    DesiredNode node = new DesiredNode(
        NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
    );
    mockProvisioner.shouldReturnPendingApproval = true;

    DesiredStateGraph graph = factory.of(List.of(node), List.of());
    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(node, StepAction.PROVISION)),
        graph, graph
    );

    TransitionResult result = executor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    StepOutcome outcome = result.outcomes().get(NodeId.of("db-prod"));
    assertInstanceOf(StepOutcome.Failed.class, outcome);
    assertTrue(((StepOutcome.Failed) outcome).reason().contains("no PendingApprovalHandler configured"));
}

@Test
void pendingApproval_handlerCheckReturnsPending_skipsProvisioner() {
    PendingApprovalHandler handler = new PendingApprovalHandler() {
        public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
            return new ApprovalCheckResult.Pending("plan-42");
        }
        public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
            return new StepOutcome.Skipped("pending");
        }
        public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
    };

    SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
        mockProvisioner, new NoOpHumanNodeHandler(), handler);

    DesiredNode node = new DesiredNode(
        NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
    );
    DesiredStateGraph graph = factory.of(List.of(node), List.of());
    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(node, StepAction.PROVISION)),
        graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("db-prod")));
    assertTrue(mockProvisioner.callOrder.isEmpty(), "Provisioner should NOT be called when pending");
}

@Test
void pendingApproval_handlerCheckReturnsApproved_callsProvisionerWithApproval() {
    var approval = new PlanApproval("plan-42", "jane", Instant.parse("2026-06-28T14:30:00Z"));
    ProvisionContext[] capturedContext = {null};

    NodeProvisioner capturingProvisioner = new NodeProvisioner() {
        public ProvisionResult provision(DesiredNode n, ProvisionContext ctx) {
            capturedContext[0] = ctx;
            return new ProvisionResult.Success();
        }
        public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext ctx) {
            return new DeprovisionResult.Success();
        }
    };

    PendingApprovalHandler handler = new PendingApprovalHandler() {
        public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
            return new ApprovalCheckResult.Approved(approval);
        }
        public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
            return new StepOutcome.Skipped("pending");
        }
        public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
    };

    SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
        capturingProvisioner, new NoOpHumanNodeHandler(), handler);

    DesiredNode node = new DesiredNode(
        NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
    );
    DesiredStateGraph graph = factory.of(List.of(node), List.of());
    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(node, StepAction.PROVISION)),
        graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    assertInstanceOf(StepOutcome.Succeeded.class, result.outcomes().get(NodeId.of("db-prod")));
    assertNotNull(capturedContext[0].approval());
    assertEquals("plan-42", capturedContext[0].approval().planReference());
    assertEquals("jane", capturedContext[0].approval().approvedBy());
}

@Test
void pendingApproval_handlerCheckReturnsRejected_returnsRejectedAndAcknowledges() {
    boolean[] acknowledged = {false};

    PendingApprovalHandler handler = new PendingApprovalHandler() {
        public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
            return new ApprovalCheckResult.Rejected("plan-42", "risk too high");
        }
        public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
            return new StepOutcome.Skipped("pending");
        }
        public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {
            acknowledged[0] = true;
        }
    };

    SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
        mockProvisioner, new NoOpHumanNodeHandler(), handler);

    DesiredNode node = new DesiredNode(
        NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
    );
    DesiredStateGraph graph = factory.of(List.of(node), List.of());
    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(node, StepAction.PROVISION)),
        graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    assertInstanceOf(StepOutcome.Rejected.class, result.outcomes().get(NodeId.of("db-prod")));
    assertEquals("approval rejected: risk too high",
        ((StepOutcome.Rejected) result.outcomes().get(NodeId.of("db-prod"))).reason());
    assertTrue(acknowledged[0], "acknowledgeRejection should be called");
    assertTrue(mockProvisioner.callOrder.isEmpty(), "Provisioner should NOT be called on rejection");
}

@Test
void pendingApproval_provisionerReturnsPendingApproval_callsRecordPending() {
    String[] recordedPlanRef = {null};

    PendingApprovalHandler handler = new PendingApprovalHandler() {
        public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
            return new ApprovalCheckResult.None();
        }
        public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String planRef) {
            recordedPlanRef[0] = planRef;
            return new StepOutcome.Skipped("pending approval: WorkItem xyz");
        }
        public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
    };

    // Provisioner returns PendingApproval
    NodeProvisioner pendingProvisioner = new NodeProvisioner() {
        public ProvisionResult provision(DesiredNode n, ProvisionContext ctx) {
            return new ProvisionResult.PendingApproval(n.id(), "plan-42");
        }
        public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext ctx) {
            return new DeprovisionResult.Success();
        }
    };

    SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
        pendingProvisioner, new NoOpHumanNodeHandler(), handler);

    DesiredNode node = new DesiredNode(
        NodeId.of("db-prod"), NodeType.of("database"), new TestSpec("pg"), false
    );
    DesiredStateGraph graph = factory.of(List.of(node), List.of());
    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(node, StepAction.PROVISION)),
        graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("db-prod")));
    assertEquals("plan-42", recordedPlanRef[0]);
}

@Test
void deprovision_pendingApproval_handlerCheckReturnsPending_skipsProvisioner() {
    PendingApprovalHandler handler = new PendingApprovalHandler() {
        public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
            return new ApprovalCheckResult.Pending("depro-plan");
        }
        public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
            return new StepOutcome.Skipped("pending");
        }
        public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
    };

    SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
        mockProvisioner, new NoOpHumanNodeHandler(), handler);

    DesiredNode node = new DesiredNode(
        NodeId.of("old-db"), NodeType.of("database"), new TestSpec("pg"), false
    );
    DesiredStateGraph graph = factory.empty();
    TransitionPlan plan = new TransitionPlan(
        List.of(new OrderedStep(node, StepAction.DEPROVISION)),
        List.of(), graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("old-db")));
    assertTrue(mockProvisioner.callOrder.isEmpty());
}

@Test
void requiresHuman_takesPrecedence_overPendingApprovalHandler() {
    // Even with a handler that would return Approved, requiresHuman delegates to HumanNodeHandler
    PendingApprovalHandler handler = new PendingApprovalHandler() {
        public ApprovalCheckResult check(DesiredNode n, StepAction a, String t) {
            return new ApprovalCheckResult.Approved(
                new PlanApproval("plan", "jane", Instant.now()));
        }
        public StepOutcome recordPending(DesiredNode n, StepAction a, String t, String p) {
            return new StepOutcome.Skipped("pending");
        }
        public void acknowledgeRejection(DesiredNode n, StepAction a, String t) {}
    };

    SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
        mockProvisioner, new NoOpHumanNodeHandler(), handler);

    DesiredNode humanNode = new DesiredNode(
        NodeId.of("h1"), NodeType.of("test"), new TestSpec("human"), true
    );
    DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());
    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
        graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    // Should be Skipped from NoOpHumanNodeHandler, not Succeeded from the approval
    assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("h1")));
    assertTrue(((StepOutcome.Skipped) result.outcomes().get(NodeId.of("h1"))).reason()
        .contains("requires human"));
}
```

Update `MockNodeProvisioner` inner class to support PendingApproval:

```java
static class MockNodeProvisioner implements NodeProvisioner {
    List<String> callOrder = new java.util.ArrayList<>();
    boolean shouldFail = false;
    boolean shouldReturnPendingApproval = false;

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        callOrder.add("provision:" + node.id().value());
        if (shouldFail) return new ProvisionResult.Failed("mock failure");
        if (shouldReturnPendingApproval) return new ProvisionResult.PendingApproval(node.id(), "mock-plan");
        return new ProvisionResult.Success();
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        callOrder.add("deprovision:" + node.id().value());
        if (shouldFail) return new DeprovisionResult.Failed("mock failure");
        return new DeprovisionResult.Success();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=SimpleTransitionExecutorTest`
Expected: compilation errors — `SimpleTransitionExecutor` constructor doesn't accept `PendingApprovalHandler` yet.

- [ ] **Step 3: Create NoOpPendingApprovalHandler**

Create `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpPendingApprovalHandler.java`:

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

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
    }
}
```

- [ ] **Step 4: Modify SimpleTransitionExecutor**

Update constructor to accept `PendingApprovalHandler`. Update `executeProvision()` and `executeDeprovision()` with the approval check/record flow per spec.

Key changes:
- Constructor: add `PendingApprovalHandler pendingApprovalHandler` parameter
- `executeProvision()`: after `requiresHuman` check, call `handler.check()`, switch on result, then call provisioner, then handle PendingApproval result via `handler.recordPending()`
- `executeDeprovision()`: add `handler.check()` before provisioner call (no requiresHuman check — deprovision is always automated), handle PendingApproval result via `handler.recordPending()`

- [ ] **Step 5: Fix existing tests that construct SimpleTransitionExecutor**

Update `setUp()` in SimpleTransitionExecutorTest:
```java
executor = new SimpleTransitionExecutor(mockProvisioner, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());
```

Update all inline executor constructions (e.g., `delegatesHumanNodesToHandler`, `handlerReceivesCorrectProvisionContext`) to include the third parameter.

Also update `ReconciliationTracingTest` if it constructs `SimpleTransitionExecutor` directly.

- [ ] **Step 6: Run all tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime`
Expected: all tests PASS.

- [ ] **Step 7: Commit**

```
feat(#14): NoOpPendingApprovalHandler + SimpleTransitionExecutor approval flow
```

---

### Task 3: ReconciliationLoop — faultFeedback guard + Rejected pattern match

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopTest.java`

**Interfaces:**
- Consumes: `StepOutcome.Rejected` from Task 1
- Consumes: `FaultType.APPROVAL_REJECTED` from Task 1

- [ ] **Step 1: Write failing test for Rejected → APPROVAL_REJECTED fault**

Add to `ReconciliationLoopTest.java`:

```java
@Test
void rejectedOutcome_producesApprovalRejectedFaultType() {
    DesiredNode nodeA = node("a");
    DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
    actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.ABSENT));

    // Configure executor to return Rejected for node "a"
    testExecutor.rejectNodes.add(NodeId.of("a"));

    List<FaultEvent> capturedEvents = new CopyOnWriteArrayList<>();
    FaultPolicy capturingPolicy = (event, current) -> {
        capturedEvents.add(event);
        return List.of();
    };
    faultEngine = new FaultPolicyEngine(List.of(capturingPolicy));

    loop = new ReconciliationLoop(
        planner, testExecutor, actualAdapter, faultEngine, testEventSource,
        TEST_DEBOUNCE, TEST_RESYNC);

    loop.start("test-tenant", desired);

    await().atMost(Duration.ofSeconds(2)).until(() -> !capturedEvents.isEmpty());

    assertEquals(1, capturedEvents.size());
    FaultEvent event = capturedEvents.get(0);
    assertEquals(NodeId.of("a"), event.node());
    assertEquals(FaultType.APPROVAL_REJECTED, event.type());
}
```

Update `TestTransitionExecutor` to support Rejected:

```java
final Set<NodeId> rejectNodes = ConcurrentHashMap.newKeySet();

// In the execute method, within the additions loop:
if (rejectNodes.contains(step.node().id())) {
    outcomes.put(step.node().id(), new StepOutcome.Rejected("test rejection"));
} else if (failNodes.contains(step.node().id())) {
    ...
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=ReconciliationLoopTest#rejectedOutcome_producesApprovalRejectedFaultType`
Expected: FAIL — faultFeedback guard skips Rejected outcomes.

- [ ] **Step 3: Update faultFeedback in ReconciliationLoop**

Two changes in `ReconciliationLoop.java`:

1. Update the early-exit guard (line 321-323):
```java
boolean hasFaultyOutcomes = result.outcomes().values().stream()
        .anyMatch(o -> o instanceof StepOutcome.Failed || o instanceof StepOutcome.Rejected);
if (!hasFaultyOutcomes) {
    return;
}
```

2. Add `Rejected` pattern match inside the loop (after the existing `Failed` check):
```java
if (entry.getValue() instanceof StepOutcome.Failed failed) {
    faultCount++;
    FaultType faultType = removalNodeIds.contains(entry.getKey())
            ? FaultType.DEPROVISION_FAILED
            : FaultType.PROVISION_FAILED;
    FaultEvent faultEvent = new FaultEvent(
            entry.getKey(), faultType, failed.reason());
    List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, mutated);
    mutationCount += mutations.size();
    for (GraphMutation mutation : mutations) {
        mutated = mutated.withMutation(mutation);
    }
} else if (entry.getValue() instanceof StepOutcome.Rejected rejected) {
    faultCount++;
    FaultEvent faultEvent = new FaultEvent(
            entry.getKey(), FaultType.APPROVAL_REJECTED, rejected.reason());
    List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, mutated);
    mutationCount += mutations.size();
    for (GraphMutation mutation : mutations) {
        mutated = mutated.withMutation(mutation);
    }
}
```

- [ ] **Step 4: Run all tests**

Run: `mvn --batch-mode test -pl runtime`
Expected: all tests PASS including the new rejection test.

- [ ] **Step 5: Commit**

```
feat(#14): faultFeedback — Rejected outcomes produce APPROVAL_REJECTED fault events
```

---

### Task 4: Testing module — MockPendingApprovalHandler

**Files:**
- Create: `testing/src/main/java/io/casehub/desiredstate/testing/MockPendingApprovalHandler.java`

**Interfaces:**
- Consumes: `PendingApprovalHandler`, `ApprovalCheckResult`, `StepOutcome`, `DesiredNode`, `StepAction`, `NodeId` from Task 1
- Produces: `MockPendingApprovalHandler` — programmable mock for consumer tests

- [ ] **Step 1: Create MockPendingApprovalHandler**

```java
package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Programmable mock PendingApprovalHandler for testing.
 * Records all calls. Results configurable per node+action.
 */
public class MockPendingApprovalHandler implements PendingApprovalHandler {

    public final CopyOnWriteArrayList<RecordedPending> recorded = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<RecordedRejection> acknowledgedRejections = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<String, ApprovalCheckResult> checkResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StepOutcome> recordPendingResults = new ConcurrentHashMap<>();

    @Override
    public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
        return checkResults.getOrDefault(key(node.id(), action), new ApprovalCheckResult.None());
    }

    @Override
    public StepOutcome recordPending(DesiredNode node, StepAction action,
                                      String tenancyId, String planReference) {
        recorded.add(new RecordedPending(node.id(), action, tenancyId, planReference));
        return recordPendingResults.getOrDefault(
            key(node.id(), action),
            new StepOutcome.Skipped("pending approval: " + planReference));
    }

    @Override
    public void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId) {
        acknowledgedRejections.add(new RecordedRejection(node.id(), action, tenancyId));
    }

    public void programCheck(NodeId nodeId, StepAction action, ApprovalCheckResult result) {
        checkResults.put(key(nodeId, action), result);
    }

    public void programRecordPending(NodeId nodeId, StepAction action, StepOutcome result) {
        recordPendingResults.put(key(nodeId, action), result);
    }

    public void clear() {
        recorded.clear();
        acknowledgedRejections.clear();
        checkResults.clear();
        recordPendingResults.clear();
    }

    private String key(NodeId nodeId, StepAction action) {
        return nodeId.value() + ":" + action.name();
    }

    public record RecordedPending(NodeId nodeId, StepAction action, String tenancyId, String planReference) {}
    public record RecordedRejection(NodeId nodeId, StepAction action, String tenancyId) {}
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `mvn --batch-mode compile -pl testing`
Expected: PASS.

- [ ] **Step 3: Commit**

```
feat(#14): MockPendingApprovalHandler in testing module
```

---

### Task 5: Work-adapter — WorkItemPendingApprovalHandler (BLOCKED on work#281, work#282)

> **BLOCKED:** This task requires casehubio/work#281 (WorkItemRef.payload field) and casehubio/work#282 (WorkItemCreator.obsoleteByCallerRef). Implement after those prerequisites ship. Tasks 1-4 are independently shippable.

**Files:**
- Create: `work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandler.java`
- Create: `work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandlerTest.java`

**Interfaces:**
- Consumes: `PendingApprovalHandler`, `ApprovalCheckResult`, `PlanApproval`, `StepOutcome`, `DesiredNode`, `StepAction`, `NodeId` from Task 1
- Consumes: `WorkItemCreator.create()`, `.findActiveByCallerRef()`, `.findByCallerRef()`, `.obsoleteByCallerRef()` from casehub-work-api (after work#281, #282)
- Consumes: `WorkItemCreateRequest`, `WorkItemRef`, `WorkItemStatus`, `WorkItemPriority`, `Outcome` from casehub-work-api

- [ ] **Step 1: Write failing tests**

Create `work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandlerTest.java` with tests for:
- `check_noWorkItem_returnsNone`
- `check_activeWorkItem_returnsPending`
- `check_completedApprove_returnsApproved`
- `check_completedUnexpectedOutcome_returnsNoneWithWarning`
- `check_rejected_returnsRejected`
- `check_expired_returnsNone`
- `check_cancelled_returnsNone`
- `check_faulted_returnsNone`
- `check_escalated_returnsNone`
- `check_obsolete_returnsNone`
- `recordPending_createsWorkItem_returnsSkipped`
- `recordPending_idempotent_doesNotCreateDuplicate`
- `recordPending_callerRefFormat`
- `recordPending_setsPermittedOutcomes`
- `recordPending_setsPayload`
- `acknowledgeRejection_callsObsoleteByCallerRef`
- `differentAction_differentCallerRef` (PROVISION vs DEPROVISION for same node)

Each test follows the pattern from `WorkItemHumanNodeHandlerTest` — inner `MockWorkItemCreator` class, `@BeforeEach` setup, AssertJ assertions.

- [ ] **Step 2: Implement WorkItemPendingApprovalHandler**

Follow the spec's check(), recordPending(), and acknowledgeRejection() logic exactly. CallerRef convention: `desiredstate-approval:<tenancyId>:<nodeId>:<action>`.

- [ ] **Step 3: Run tests and verify**

Run: `mvn --batch-mode test -pl work-adapter`
Expected: all tests PASS.

- [ ] **Step 4: Commit**

```
feat(#14): WorkItemPendingApprovalHandler — WorkItem-backed approval lifecycle
```

---

### Task 6: Full build verification + compile-break fixups

**Files:**
- Potentially modify: any file with an exhaustive `switch` on `StepOutcome` or `FaultType`
- Potentially modify: `ReconciliationTracingTest.java` (if it constructs `SimpleTransitionExecutor`)
- Potentially modify: engine-adapter tests

**Interfaces:**
- Consumes: all prior tasks

- [ ] **Step 1: Full build**

Run: `mvn --batch-mode install`

If any compilation errors from exhaustive switches on StepOutcome (adding Rejected breaks them), fix each one by adding the `case StepOutcome.Rejected r ->` branch.

Known switch sites from the codebase search:
- `api/src/test/java/io/casehub/desiredstate/api/TypesTest.java:55-58` — already updated in Task 1
- `runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java` — no exhaustive switch on StepOutcome (it switches on ProvisionResult/DeprovisionResult)
- No other exhaustive switches on StepOutcome found in production code

Fix any remaining issues. The `instanceof` checks (e.g., `o instanceof StepOutcome.Failed`) are not exhaustive and won't break.

- [ ] **Step 2: Verify all tests pass**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 3: Commit fixups if any**

```
fix(#14): compile-break fixups for StepOutcome.Rejected across modules
```
