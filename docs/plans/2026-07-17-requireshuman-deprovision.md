# requiresHuman Deprovision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #54 — requiresHuman gating for deprovision
**Issue group:** #54

**Goal:** Make `requiresHuman` gate both provision and deprovision symmetrically,
so human nodes are routed to `HumanNodeHandler` in both directions.

**Architecture:** Default method `onDeprovision` on `HumanNodeHandler` SPI.
`SimpleTransitionExecutor.executeDeprovision` gains the `requiresHuman` check
symmetric with `executeProvision`. `NoOpHumanNodeHandler` overrides with
misconfiguration message. `WorkItemHumanNodeHandler` overrides with WorkItem
creation. `CaseTransitionExecutor` separates human removals into `humanTask`
bindings. CallerRef format gains action suffix. CTE binding names become
action-namespaced.

**Tech Stack:** Java 21, Quarkus 3.32.2, Mutiny, CDI (Arc), JUnit 5, AssertJ

**Spec:** `docs/specs/2026-07-17-requireshuman-deprovision-design.md`

## Global Constraints

- `StepOutcome.Skipped` is the return convention for human nodes
- Hand-written mocks in tests (no Mockito — matches project style)
- Every commit references issue #54
- Pre-release: breaking changes are free, fix the design
- CallerRef format: `desiredstate:<tenancyId>:<nodeId>:<action>`
- CTE binding names: `human-provision-<nodeId>` / `human-deprovision-<nodeId>`

---

## File Structure

**api/ module:**
| File | Action | Responsibility |
|------|--------|---------------|
| `api/.../api/HumanNodeHandler.java` | Modify | Add default `onDeprovision`, update javadoc |
| `api/.../api/DesiredNode.java` | Modify | Update javadoc only |

**runtime/ module:**
| File | Action | Responsibility |
|------|--------|---------------|
| `runtime/.../runtime/NoOpHumanNodeHandler.java` | Modify | Override `onDeprovision` → Skipped |
| `runtime/.../runtime/NoOpHumanNodeHandlerTest.java` | Modify | Add deprovision test |
| `runtime/.../runtime/SimpleTransitionExecutor.java` | Modify | Add `requiresHuman` gate + span attr in `executeDeprovision` |
| `runtime/.../runtime/SimpleTransitionExecutorTest.java` | Modify | Add deprovision human-node tests |

**work-adapter/ module:**
| File | Action | Responsibility |
|------|--------|---------------|
| `work-adapter/.../work/WorkItemHumanNodeHandler.java` | Modify | Override `onDeprovision`, update callerRef format |
| `work-adapter/.../work/WorkItemHumanNodeHandlerTest.java` | Modify | Add deprovision tests, update provision callerRef assertions |

**engine-adapter/ module:**
| File | Action | Responsibility |
|------|--------|---------------|
| `engine-adapter/.../engine/CaseTransitionExecutor.java` | Modify | Separate human removals, action-namespace bindings |
| `engine-adapter/.../engine/CaseTransitionExecutorTest.java` | Modify | Add human removal tests, update binding name assertions |

---

### Task 1: HumanNodeHandler SPI + DesiredNode javadoc

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/HumanNodeHandler.java` (lines 7-13)
- Modify: `api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java` (lines 1-21)

**Interfaces:**
- Produces: `HumanNodeHandler.onDeprovision(DesiredNode, DeprovisionContext) → StepOutcome` (default method)

- [ ] **Step 1: Add default `onDeprovision` to HumanNodeHandler**

Use `ide_edit_member` to replace the interface declaration with updated javadoc and the new default method:

```java
/**
 * Handles lifecycle actions for nodes marked with {@code requiresHuman = true}.
 * Called by SimpleTransitionExecutor for both provision and deprovision phases.
 */
public interface HumanNodeHandler {
    /**
     * Handle provisioning of a human-required node.
     * Returns Skipped if the node cannot be provisioned yet, or Succeeded/Failed based on outcome.
     */
    StepOutcome onProvision(DesiredNode node, ProvisionContext context);

    /**
     * Handle deprovisioning of a human-required node.
     * Default returns Skipped — override in implementations that handle human-gated deprovision.
     */
    default StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
        return new StepOutcome.Skipped("deprovision not handled");
    }
}
```

- [ ] **Step 2: Update DesiredNode javadoc**

Use `ide_edit_member` on `DesiredNode` (member = `DesiredNode`) to update the record javadoc from "whether provisioning requires human approval" to "whether this node's lifecycle actions require human handling":

```java
/**
 * A node in the desired-state graph: what should exist, its type, its specification,
 * and whether this node's lifecycle actions require human handling.
 */
public record DesiredNode(NodeId id, NodeType type, NodeSpec spec, boolean requiresHuman) {
    // ... body unchanged
}
```

- [ ] **Step 3: Verify compilation**

Run: `ide_diagnostics` on `HumanNodeHandler.java` and `DesiredNode.java`
Expected: no errors (default method preserves SAM type, all existing callers compile)

- [ ] **Step 4: Build api module**

Run: `mvn --batch-mode -pl api compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/casehub/desiredstate/api/HumanNodeHandler.java
git add api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java
git commit -m "feat(#54): add default onDeprovision to HumanNodeHandler SPI"
```

---

### Task 2: NoOpHumanNodeHandler — override onDeprovision

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandler.java` (lines 9-14)
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandlerTest.java` (lines 10-30)

**Interfaces:**
- Consumes: `HumanNodeHandler.onDeprovision(DesiredNode, DeprovisionContext)` from Task 1
- Produces: `NoOpHumanNodeHandler.onDeprovision()` → `Skipped("requires human — no HumanNodeHandler configured")`

- [ ] **Step 1: Write the failing test**

Use `ide_insert_member` in `NoOpHumanNodeHandlerTest`, position `after` anchor `returnsSkippedWithConfigurationMessage`:

```java
@Test
void deprovision_returnsSkippedWithConfigurationMessage() {
    NoOpHumanNodeHandler handler = new NoOpHumanNodeHandler();
    DesiredNode node = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), true);
    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
        .of(List.of(node), List.of());
    DeprovisionContext context = new DeprovisionContext("tenant1", graph);

    StepOutcome outcome = handler.onDeprovision(node, context);

    assertInstanceOf(StepOutcome.Skipped.class, outcome);
    assertEquals("requires human — no HumanNodeHandler configured",
        ((StepOutcome.Skipped) outcome).reason());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -pl runtime test -Dtest=NoOpHumanNodeHandlerTest#deprovision_returnsSkippedWithConfigurationMessage`
Expected: FAIL — default method returns "deprovision not handled", not the NoOp message

- [ ] **Step 3: Implement — override onDeprovision in NoOpHumanNodeHandler**

Use `ide_insert_member` in `NoOpHumanNodeHandler`, position `after` anchor `onProvision`:

```java
@Override
public StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
    return new StepOutcome.Skipped("requires human — no HumanNodeHandler configured");
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode -pl runtime test -Dtest=NoOpHumanNodeHandlerTest`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandler.java
git add runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandlerTest.java
git commit -m "feat(#54): NoOpHumanNodeHandler overrides onDeprovision"
```

---

### Task 3: SimpleTransitionExecutor — requiresHuman gate for deprovision

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java` (lines 106-143)
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java`

**Interfaces:**
- Consumes: `HumanNodeHandler.onDeprovision(DesiredNode, DeprovisionContext)` from Task 1
- Consumes: `NoOpHumanNodeHandler.onDeprovision()` from Task 2 (used in tests)

- [ ] **Step 1: Write failing test — deprovision delegates to HumanNodeHandler**

Use `ide_insert_member` in `SimpleTransitionExecutorTest`, position `after` anchor `requiresHuman_takesPrecedence_overPendingApprovalHandler`:

```java
@Test
void deprovision_requiresHuman_delegatesToHandler() {
    HumanNodeHandler handler = new HumanNodeHandler() {
        @Override
        public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
            return new StepOutcome.Skipped("not under test");
        }
        @Override
        public StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
            return new StepOutcome.Skipped("test deprovision handler: " + node.id().value());
        }
    };

    var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
    SimpleTransitionExecutor handlerExecutor =
        new SimpleTransitionExecutor(router, handler, new NoOpPendingApprovalHandler());

    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec("human"), true);

    DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(new OrderedStep(humanNode, StepAction.DEPROVISION)),
        List.of(),
        graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem()
        .getItem();

    StepOutcome outcome = result.outcomes().get(NodeId.of("h1"));
    assertInstanceOf(StepOutcome.Skipped.class, outcome);
    assertEquals("test deprovision handler: h1",
        ((StepOutcome.Skipped) outcome).reason());

    assertTrue(mockProvisioner.callOrder.isEmpty(),
        "Provisioner should NOT be called for requiresHuman deprovision");
}
```

- [ ] **Step 2: Write failing test — deprovision handler receives correct context**

Use `ide_insert_member` after the previous test:

```java
@Test
void deprovision_handlerReceivesCorrectDeprovisionContext() {
    String[] capturedTenancyId = {null};
    DesiredStateGraph[] capturedGraph = {null};

    HumanNodeHandler capturingHandler = new HumanNodeHandler() {
        @Override
        public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
            return new StepOutcome.Skipped("not under test");
        }
        @Override
        public StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
            capturedTenancyId[0] = context.tenancyId();
            capturedGraph[0] = context.graph();
            return new StepOutcome.Skipped("captured");
        }
    };

    var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
    SimpleTransitionExecutor capturingExecutor =
        new SimpleTransitionExecutor(router, capturingHandler, new NoOpPendingApprovalHandler());

    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec("human"), true);

    DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(new OrderedStep(humanNode, StepAction.DEPROVISION)),
        List.of(),
        graph, graph
    );

    capturingExecutor.execute(plan, "my-tenant")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem();

    assertEquals("my-tenant", capturedTenancyId[0]);
    assertNotNull(capturedGraph[0]);
    assertTrue(capturedGraph[0].nodes().containsKey(NodeId.of("h1")));
}
```

- [ ] **Step 3: Write failing test — requiresHuman takes precedence over PendingApproval for deprovision**

Use `ide_insert_member` after the previous test:

```java
@Test
void deprovision_requiresHuman_takesPrecedence_overPendingApprovalHandler() {
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

    var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
    SimpleTransitionExecutor handlerExecutor = new SimpleTransitionExecutor(
        router, new NoOpHumanNodeHandler(), handler);

    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec("human"), true);
    DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());
    TransitionPlan plan = new TransitionPlan(
        List.of(new OrderedStep(humanNode, StepAction.DEPROVISION)),
        List.of(),
        graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    assertInstanceOf(StepOutcome.Skipped.class, result.outcomes().get(NodeId.of("h1")));
    assertTrue(((StepOutcome.Skipped) result.outcomes().get(NodeId.of("h1"))).reason()
        .contains("requires human"));
    assertTrue(mockProvisioner.callOrder.isEmpty(),
        "Provisioner should NOT be called when requiresHuman overrides approval");
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn --batch-mode -pl runtime test -Dtest="SimpleTransitionExecutorTest#deprovision_requiresHuman_delegatesToHandler+deprovision_handlerReceivesCorrectDeprovisionContext+deprovision_requiresHuman_takesPrecedence_overPendingApprovalHandler"`
Expected: FAIL — `executeDeprovision` doesn't check `requiresHuman`, so deprovision goes to provisioner

- [ ] **Step 5: Implement — update executeDeprovision**

Use `ide_replace_member` on `SimpleTransitionExecutor`, member `executeDeprovision`, to replace the method body:

```java
DeprovisionContext context = new DeprovisionContext(tenancyId, graph);

if (node.requiresHuman()) {
    return humanNodeHandler.onDeprovision(node, context);
}

ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(node, StepAction.DEPROVISION, tenancyId);
switch (approvalCheck) {
    case ApprovalCheckResult.Pending p ->
        { return new StepOutcome.Skipped("pending approval: " + p.planReference()); }
    case ApprovalCheckResult.Rejected r -> {
        pendingApprovalHandler.acknowledgeRejection(node, StepAction.DEPROVISION, tenancyId);
        span.setStatus(StatusCode.ERROR, "approval rejected: " + r.reason());
        return new StepOutcome.Rejected("approval rejected: " + r.reason());
    }
    case ApprovalCheckResult.Approved a ->
        context = context.withApproval(a.approval());
    case ApprovalCheckResult.None ignored -> {}
}

DeprovisionResult result = router.deprovision(node, context);

return switch (result) {
    case DeprovisionResult.Success ignored -> new StepOutcome.Succeeded();
    case DeprovisionResult.Failed f -> {
        span.setStatus(StatusCode.ERROR, f.reason());
        yield new StepOutcome.Failed(f.reason());
    }
    case DeprovisionResult.PendingApproval pa ->
        pendingApprovalHandler.recordPending(node, StepAction.DEPROVISION, tenancyId, pa.planReference());
};
```

Also use `ide_edit_member` on `executeDeprovision` to update the full method signature with the span attribute addition. The span builder must include `.setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman())` and the comment `// no requiresHuman for deprovision` must be removed from the approval check comment.

Full method:

```java
private StepOutcome executeDeprovision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
    Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("deprovision")
            .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
            .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
            .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman())
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
        DeprovisionContext context = new DeprovisionContext(tenancyId, graph);

        if (node.requiresHuman()) {
            return humanNodeHandler.onDeprovision(node, context);
        }

        ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(node, StepAction.DEPROVISION, tenancyId);
        switch (approvalCheck) {
            case ApprovalCheckResult.Pending p ->
                { return new StepOutcome.Skipped("pending approval: " + p.planReference()); }
            case ApprovalCheckResult.Rejected r -> {
                pendingApprovalHandler.acknowledgeRejection(node, StepAction.DEPROVISION, tenancyId);
                span.setStatus(StatusCode.ERROR, "approval rejected: " + r.reason());
                return new StepOutcome.Rejected("approval rejected: " + r.reason());
            }
            case ApprovalCheckResult.Approved a ->
                context = context.withApproval(a.approval());
            case ApprovalCheckResult.None ignored -> {}
        }

        DeprovisionResult result = router.deprovision(node, context);

        return switch (result) {
            case DeprovisionResult.Success ignored -> new StepOutcome.Succeeded();
            case DeprovisionResult.Failed f -> {
                span.setStatus(StatusCode.ERROR, f.reason());
                yield new StepOutcome.Failed(f.reason());
            }
            case DeprovisionResult.PendingApproval pa ->
                pendingApprovalHandler.recordPending(node, StepAction.DEPROVISION, tenancyId, pa.planReference());
        };
    } finally {
        span.end();
    }
}
```

- [ ] **Step 6: Run all SimpleTransitionExecutorTest tests**

Run: `mvn --batch-mode -pl runtime test -Dtest=SimpleTransitionExecutorTest`
Expected: ALL PASS (new tests + existing tests)

- [ ] **Step 7: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java
git add runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java
git commit -m "feat(#54): add requiresHuman gate to executeDeprovision in SimpleTransitionExecutor"
```

---

### Task 4: WorkItemHumanNodeHandler — onDeprovision + callerRef format

**Files:**
- Modify: `work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandler.java` (lines 13-45)
- Modify: `work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandlerTest.java` (lines 16-173)

**Interfaces:**
- Consumes: `HumanNodeHandler.onDeprovision(DesiredNode, DeprovisionContext)` from Task 1
- Consumes: `WorkItemCreator.create()`, `WorkItemCreator.findActiveByCallerRef()` from casehub-work-api

- [ ] **Step 1: Write failing test — deprovision creates WorkItem**

Use `ide_insert_member` in `WorkItemHumanNodeHandlerTest`, position `after` anchor `differentNodeId_differentCallerRef`:

```java
@Test
void deprovision_firstCall_createsWorkItem_returnsSkipped() {
    DesiredNode node = new DesiredNode(NodeId.of("thermo-1"), new TestSpec("uninstall"), true);
    DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
    DeprovisionContext context = new DeprovisionContext("tenant1", graph);

    StepOutcome outcome = handler.onDeprovision(node, context);

    assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
    assertThat(((StepOutcome.Skipped) outcome).reason())
        .startsWith("pending human action: WorkItem ");

    assertThat(mockCreator.lastCreateRequest).isNotNull();
    assertThat(mockCreator.lastCreateRequest.title).isEqualTo("Deprovision: thermo-1");
    assertThat(mockCreator.lastCreateRequest.description)
        .isEqualTo("Human deprovision required for node thermo-1 (type: iot-device)");
    assertThat(mockCreator.lastCreateRequest.types).containsExactly("desiredstate-deprovision");
    assertThat(mockCreator.lastCreateRequest.callerRef)
        .isEqualTo("desiredstate:tenant1:thermo-1:deprovision");
    assertThat(mockCreator.lastCreateRequest.priority).isEqualTo(WorkItemPriority.MEDIUM);
    assertThat(mockCreator.lastCreateRequest.createdBy).isEqualTo("desiredstate");
}
```

- [ ] **Step 2: Write failing test — deprovision finds active WorkItem**

Use `ide_insert_member` after previous test:

```java
@Test
void deprovision_subsequentCall_findsActiveWorkItem_doesNotCreateDuplicate() {
    UUID existingId = UUID.randomUUID();
    mockCreator.activeRef = new WorkItemRef(
        existingId, WorkItemStatus.PENDING, "desiredstate:tenant1:thermo-1:deprovision",
        null, null, null, null, "tenant1", null
    );

    DesiredNode node = new DesiredNode(NodeId.of("thermo-1"), new TestSpec("uninstall"), true);
    DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
    DeprovisionContext context = new DeprovisionContext("tenant1", graph);

    StepOutcome outcome = handler.onDeprovision(node, context);

    assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
    assertThat(((StepOutcome.Skipped) outcome).reason())
        .isEqualTo("pending human action: WorkItem " + existingId);

    assertThat(mockCreator.lastCreateRequest).isNull();
}
```

- [ ] **Step 3: Write failing test — provision callerRef includes action suffix**

Use `ide_insert_member` after previous test:

```java
@Test
void provision_callerRef_includesActionSuffix() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), true);
    DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
    ProvisionContext context = new ProvisionContext("tenant1", graph);

    handler.onProvision(node, context);

    assertThat(mockCreator.lastCreateRequest.callerRef)
        .isEqualTo("desiredstate:tenant1:n1:provision");
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn --batch-mode -pl work-adapter test -Dtest="WorkItemHumanNodeHandlerTest#deprovision_firstCall_createsWorkItem_returnsSkipped+deprovision_subsequentCall_findsActiveWorkItem_doesNotCreateDuplicate+provision_callerRef_includesActionSuffix"`
Expected: FAIL — `onDeprovision` not overridden (returns default Skipped), provision callerRef has old format

- [ ] **Step 5: Implement — update onProvision callerRef and add onDeprovision**

Use `ide_edit_member` to replace `onProvision` with the updated callerRef format:

```java
@Override
public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
    String callerRef = "desiredstate:" + context.tenancyId() + ":" + node.id().value() + ":provision";

    Optional<WorkItemRef> active = workItemCreator.findActiveByCallerRef(callerRef);
    if (active.isPresent()) {
        return new StepOutcome.Skipped(
            "pending human action: WorkItem " + active.get().id());
    }

    WorkItemRef created = workItemCreator.create(WorkItemCreateRequest.builder()
        .title("Provision: " + node.id().value())
        .description("Human provisioning required for node "
            + node.id().value() + " (type: " + node.type().value() + ")")
        .types(List.of("desiredstate-provision"))
        .callerRef(callerRef)
        .priority(WorkItemPriority.MEDIUM)
        .createdBy("desiredstate")
        .build());

    return new StepOutcome.Skipped(
        "pending human action: WorkItem " + created.id());
}
```

Then use `ide_insert_member` to add `onDeprovision` after `onProvision`:

```java
@Override
public StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
    String callerRef = "desiredstate:" + context.tenancyId() + ":" + node.id().value() + ":deprovision";

    Optional<WorkItemRef> active = workItemCreator.findActiveByCallerRef(callerRef);
    if (active.isPresent()) {
        return new StepOutcome.Skipped(
            "pending human action: WorkItem " + active.get().id());
    }

    WorkItemRef created = workItemCreator.create(WorkItemCreateRequest.builder()
        .title("Deprovision: " + node.id().value())
        .description("Human deprovision required for node "
            + node.id().value() + " (type: " + node.type().value() + ")")
        .types(List.of("desiredstate-deprovision"))
        .callerRef(callerRef)
        .priority(WorkItemPriority.MEDIUM)
        .createdBy("desiredstate")
        .build());

    return new StepOutcome.Skipped(
        "pending human action: WorkItem " + created.id());
}
```

- [ ] **Step 6: Update existing provision test assertions for new callerRef format**

The following existing tests assert `desiredstate:tenant1:thermo-1` or similar patterns for callerRef. Update them to `desiredstate:tenant1:thermo-1:provision`:

1. `firstCall_createsWorkItem_returnsSkippedWithId` (line 51): change `"desiredstate:tenant1:thermo-1"` → `"desiredstate:tenant1:thermo-1:provision"`
2. `subsequentCall_findsActiveWorkItem_doesNotCreateDuplicate` (line 60): change the `WorkItemRef` callerRef to `"desiredstate:tenant1:thermo-1:provision"`
3. `reProvisionAfterCompletion_noActiveWorkItem_createsNew` (line 97-98): change `"desiredstate:tenant1:thermo-1"` → `"desiredstate:tenant1:thermo-1:provision"`
4. `differentTenancy_differentCallerRef` (line 109, 114): change `"desiredstate:tenantA:n1"` → `"desiredstate:tenantA:n1:provision"` and `"desiredstate:tenantB:n1"` → `"desiredstate:tenantB:n1:provision"`
5. `differentNodeId_differentCallerRef` (line 133, 138): change `"desiredstate:tenant1:a"` → `"desiredstate:tenant1:a:provision"` and `"desiredstate:tenant1:b"` → `"desiredstate:tenant1:b:provision"`

Use `ide_replace_text_in_file` with `searchText: "desiredstate:tenant1:thermo-1\"` → `replaceText: "desiredstate:tenant1:thermo-1:provision\"` etc. for each distinct pattern.

- [ ] **Step 7: Run all WorkItemHumanNodeHandlerTest tests**

Run: `mvn --batch-mode -pl work-adapter test -Dtest=WorkItemHumanNodeHandlerTest`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandler.java
git add work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandlerTest.java
git commit -m "feat(#54): WorkItemHumanNodeHandler onDeprovision + action-qualified callerRef"
```

---

### Task 5: CaseTransitionExecutor — human removal separation + binding names

**Files:**
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/CaseTransitionExecutor.java` (lines 150-253)
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/CaseTransitionExecutorTest.java`

**Interfaces:**
- Consumes: Existing `DesiredNode.requiresHuman()`, `HumanTaskTarget`, `Binding`

- [ ] **Step 1: Write failing test — human removals get humanTask bindings**

Use `ide_insert_member` in `CaseTransitionExecutorTest`, position `after` anchor `mixedPlan_filteredAndAutomated`:

```java
@Test
void humanRemovals_getHumanTaskBindings() {
    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec(), true);
    DesiredNode normalNode = new DesiredNode(NodeId.of("n1"), new TestSpec(), false);

    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
        .of(List.of(humanNode, normalNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(
            new OrderedStep(humanNode, StepAction.DEPROVISION),
            new OrderedStep(normalNode, StepAction.DEPROVISION)
        ),
        List.of(),
        graph, graph
    );

    CaseDefinition caseDef = executor.buildCaseDefinition(plan, "exec-1");

    boolean hasHumanBinding = caseDef.bindings().stream()
        .anyMatch(b -> b.name().equals("human-deprovision-h1") && b.humanTask() != null);
    assertTrue(hasHumanBinding, "Should have humanTask binding for human removal");
}
```

- [ ] **Step 2: Write failing test — human removals excluded from prune workflow**

Use `ide_insert_member` after previous test:

```java
@Test
void humanRemovals_excludedFromPruneWorkflow() {
    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec(), true);
    DesiredNode normalNode = new DesiredNode(NodeId.of("n1"), new TestSpec(), false);

    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
        .of(List.of(humanNode, normalNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(
            new OrderedStep(humanNode, StepAction.DEPROVISION),
            new OrderedStep(normalNode, StepAction.DEPROVISION)
        ),
        List.of(),
        graph, graph
    );

    CaseDefinition caseDef = executor.buildCaseDefinition(plan, "exec-1");

    boolean hasPruneWorker = caseDef.workers().stream()
        .anyMatch(w -> w.name().equals("prune"));
    assertTrue(hasPruneWorker, "Prune worker should exist for normal removals");
}
```

- [ ] **Step 3: Write failing test — human removals marked as Skipped in optimistic result**

Use `ide_insert_member` after previous test:

```java
@Test
void humanRemovals_markedAsSkippedInResult() {
    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec(), true);

    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
        .of(List.of(humanNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(new OrderedStep(humanNode, StepAction.DEPROVISION)),
        List.of(),
        graph, graph
    );

    TransitionResult result = executor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem().getItem();

    StepOutcome outcome = result.outcomes().get(NodeId.of("h1"));
    assertInstanceOf(StepOutcome.Skipped.class, outcome);
    assertEquals("routed to WorkItem", ((StepOutcome.Skipped) outcome).reason());
}
```

- [ ] **Step 4: Write failing test — provision bindings use action-namespaced names**

Use `ide_insert_member` after previous test:

```java
@Test
void humanAdditions_useActionNamespacedBindingNames() {
    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec(), true);

    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
        .of(List.of(humanNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
        graph, graph
    );

    CaseDefinition caseDef = executor.buildCaseDefinition(plan, "exec-1");

    boolean hasNamespacedBinding = caseDef.bindings().stream()
        .anyMatch(b -> b.name().equals("human-provision-h1") && b.humanTask() != null);
    assertTrue(hasNamespacedBinding,
        "Human addition binding should use 'human-provision-<nodeId>' format");
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `mvn --batch-mode -pl engine-adapter test -Dtest="CaseTransitionExecutorTest#humanRemovals_getHumanTaskBindings+humanRemovals_excludedFromPruneWorkflow+humanRemovals_markedAsSkippedInResult+humanAdditions_useActionNamespacedBindingNames"`
Expected: FAIL

- [ ] **Step 6: Implement — update buildCaseDefinition**

Use `ide_edit_member` on `CaseTransitionExecutor`, member `buildCaseDefinition`. Full method:

```java
CaseDefinition buildCaseDefinition(TransitionPlan plan, String executionId) {
    List<Worker> workers = new ArrayList<>(2);
    List<Binding> bindings = new ArrayList<>(2);

    Capability dispatchCapability = Capability.builder()
        .name("desiredstate-dispatch")
        .inputSchema("{}")
        .outputSchema("{}")
        .description("Dispatches desired-state node provision/deprovision actions")
        .build();

    List<OrderedStep> automatedRemovals = new ArrayList<>();
    List<OrderedStep> humanRemovals = new ArrayList<>();
    for (OrderedStep step : plan.removals()) {
        if (step.node().requiresHuman()) {
            humanRemovals.add(step);
        } else {
            automatedRemovals.add(step);
        }
    }

    if (!automatedRemovals.isEmpty()) {
        Workflow pruneWorkflow = workflowGenerator.generate(
            automatedRemovals, NAMESPACE, "prune-phase", CASE_VERSION, executionId
        );

        Worker pruneWorker = Worker.builder()
            .name("prune")
            .capabilityName(dispatchCapability.name())
            .function(new FlowWorkerFunction(pruneWorkflow))
            .description("Removes nodes no longer in the desired state (leaves before roots)")
            .build();

        workers.add(pruneWorker);
        bindings.add(buildBinding("prune-binding", dispatchCapability));
    }

    List<OrderedStep> automatedAdditions = new ArrayList<>();
    List<OrderedStep> humanAdditions = new ArrayList<>();
    for (OrderedStep step : plan.additions()) {
        if (step.node().requiresHuman()) {
            humanAdditions.add(step);
        } else {
            automatedAdditions.add(step);
        }
    }

    if (!automatedAdditions.isEmpty()) {
        Workflow growWorkflow = workflowGenerator.generate(
            automatedAdditions, NAMESPACE, "grow-phase", CASE_VERSION, executionId
        );

        Worker growWorker = Worker.builder()
            .name("grow")
            .capabilityName(dispatchCapability.name())
            .function(new FlowWorkerFunction(growWorkflow))
            .description("Provisions new nodes in the desired state (roots before leaves)")
            .build();

        workers.add(growWorker);
        if (automatedRemovals.isEmpty()) {
            bindings.add(buildBinding("grow-binding", dispatchCapability));
        }
    }

    for (OrderedStep step : humanRemovals) {
        HumanTaskTarget humanTask = HumanTaskTarget.inline()
            .title("Review removal: " + step.node().id().value())
            .build();

        bindings.add(Binding.builder()
            .name("human-deprovision-" + step.node().id().value())
            .humanTask(humanTask)
            .on(new ContextChangeTrigger("."))
            .build());
    }

    for (OrderedStep step : humanAdditions) {
        HumanTaskTarget humanTask = HumanTaskTarget.inline()
            .title("Review: " + step.node().id().value())
            .build();

        bindings.add(Binding.builder()
            .name("human-provision-" + step.node().id().value())
            .humanTask(humanTask)
            .on(new ContextChangeTrigger("."))
            .build());
    }

    return CaseDefinition.builder()
        .namespace(NAMESPACE)
        .name("desired-state-transition")
        .version(CASE_VERSION)
        .title("Desired State Transition")
        .summary("Automated desired-state transition: " + automatedRemovals.size()
            + " removals, " + automatedAdditions.size() + " additions, "
            + humanRemovals.size() + " human removals, "
            + humanAdditions.size() + " human additions")
        .workers(workers)
        .bindings(bindings)
        .build();
}
```

- [ ] **Step 7: Implement — update buildOptimisticResult**

Use `ide_edit_member` on `CaseTransitionExecutor`, member `buildOptimisticResult`:

```java
private TransitionResult buildOptimisticResult(TransitionPlan plan, UUID caseId) {
    Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

    for (OrderedStep step : plan.removals()) {
        if (step.node().requiresHuman()) {
            outcomes.put(step.node().id(), new StepOutcome.Skipped("routed to WorkItem"));
        } else {
            outcomes.put(step.node().id(), new StepOutcome.Succeeded());
        }
    }
    for (OrderedStep step : plan.additions()) {
        if (step.node().requiresHuman()) {
            outcomes.put(step.node().id(), new StepOutcome.Skipped("routed to WorkItem"));
        } else {
            outcomes.put(step.node().id(), new StepOutcome.Succeeded());
        }
    }

    return new TransitionResult(outcomes);
}
```

- [ ] **Step 8: Update existing test assertions for action-namespaced binding names**

The existing test `humanNodes_getHumanTaskBindings` (line 90) checks for binding name `"human-h1"`. Update to `"human-provision-h1"`. Use `ide_replace_text_in_file`:
- `searchText: "human-h1"` → `replaceText: "human-provision-h1"`
- `searchText: "human-h2"` → `replaceText: "human-provision-h2"` (if present)

Check all binding name references in the test file and update.

- [ ] **Step 9: Run all CaseTransitionExecutorTest tests**

Run: `mvn --batch-mode -pl engine-adapter test -Dtest=CaseTransitionExecutorTest`
Expected: ALL PASS

- [ ] **Step 10: Commit**

```bash
git add engine-adapter/src/main/java/io/casehub/desiredstate/engine/CaseTransitionExecutor.java
git add engine-adapter/src/test/java/io/casehub/desiredstate/engine/CaseTransitionExecutorTest.java
git commit -m "feat(#54): CaseTransitionExecutor separates human removals + action-namespaced bindings"
```

---

### Task 6: Full build verification

- [ ] **Step 1: Run full project build with tests**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules compile, all tests pass

- [ ] **Step 2: Verify no regressions in example modules**

The dungeon example uses `requiresHuman`. Verify it still compiles:

Run: `mvn --batch-mode -pl examples/dungeon test`
Expected: PASS

- [ ] **Step 3: Final commit if any fixes needed**

If the full build surfaces any issues, fix and commit with:
```bash
git commit -m "fix(#54): address build issues from requiresHuman deprovision changes"
```
