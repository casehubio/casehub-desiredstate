# CTE PendingApproval Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `desiredstate:dispatch` into engine-flow's `CallableDispatchRegistry`, handle PendingApproval lifecycle in workflow steps, and pre-filter approval-gated nodes in CaseTransitionExecutor.

**Architecture:** `DesiredStateDispatch` registers for `desiredstate:dispatch` and handles provisioning with the same check/provision/record/acknowledge pattern as `SimpleTransitionExecutor`. `DesiredStateExecutionRegistry` passes graph+tenancyId context to workflow steps via an executionId embedded in step args. CTE pre-filters approval-gated nodes before building the case definition.

**Tech Stack:** Java 22, Quarkus CDI, casehub-engine-flow (`CallableDispatchRegistry`, `CallableDispatcher`), casehub-desiredstate-api SPIs

## Global Constraints

- Module: `engine-adapter/` — package `io.casehub.desiredstate.engine`
- All tests are plain JUnit 5 + AssertJ (no `@QuarkusTest` — existing pattern)
- `casehub-engine-flow` 0.2-SNAPSHOT already on classpath with `CallableDispatchRegistry` + `CallableDispatcher`
- `casehub-desiredstate-testing` in test scope provides `MockNodeProvisioner` and `MockPendingApprovalHandler`
- Follow existing code patterns: constructor injection, no field injection

---

### Task 1: DesiredStateExecutionContext + DesiredStateExecutionRegistry

**Files:**
- Create: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateExecutionContext.java`
- Create: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateExecutionRegistry.java`
- Create: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateExecutionRegistryTest.java`

**Interfaces:**
- Consumes: `DesiredStateGraph` (from desiredstate-api), `String tenancyId`
- Produces: `DesiredStateExecutionContext` record, `DesiredStateExecutionRegistry` CDI bean with `register(String, DesiredStateGraph, String)`, `get(String)`, `remove(String)`

- [ ] **Step 1: Write the failing tests**

```java
package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesiredStateExecutionRegistryTest {

    private DesiredStateExecutionRegistry registry;
    private DesiredStateGraph graph;

    @BeforeEach
    void setUp() {
        registry = new DesiredStateExecutionRegistry();
        graph = new DefaultDesiredStateGraphFactory().empty();
    }

    @Test
    void registerAndGet() {
        registry.register("exec-1", graph, "tenant-a");

        DesiredStateExecutionContext ctx = registry.get("exec-1");
        assertThat(ctx.graph()).isSameAs(graph);
        assertThat(ctx.tenancyId()).isEqualTo("tenant-a");
    }

    @Test
    void getMissingKeyThrows() {
        assertThatThrownBy(() -> registry.get("nonexistent"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nonexistent");
    }

    @Test
    void removeDeletesEntry() {
        registry.register("exec-2", graph, "tenant-b");
        registry.remove("exec-2");

        assertThatThrownBy(() -> registry.get("exec-2"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removeMissingKeyIsSilent() {
        registry.remove("nonexistent");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl engine-adapter -Dtest=DesiredStateExecutionRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — classes don't exist yet

- [ ] **Step 3: Implement DesiredStateExecutionContext**

```java
package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.DesiredStateGraph;
import java.util.Objects;

public record DesiredStateExecutionContext(DesiredStateGraph graph, String tenancyId) {
    public DesiredStateExecutionContext {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
    }
}
```

- [ ] **Step 4: Implement DesiredStateExecutionRegistry**

```java
package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.DesiredStateGraph;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DesiredStateExecutionRegistry {

    private final ConcurrentHashMap<String, DesiredStateExecutionContext> contexts =
        new ConcurrentHashMap<>();

    public void register(String executionId, DesiredStateGraph graph, String tenancyId) {
        contexts.put(executionId, new DesiredStateExecutionContext(graph, tenancyId));
    }

    public DesiredStateExecutionContext get(String executionId) {
        DesiredStateExecutionContext ctx = contexts.get(executionId);
        if (ctx == null) {
            throw new IllegalStateException(
                "No execution context registered for: " + executionId);
        }
        return ctx;
    }

    public void remove(String executionId) {
        contexts.remove(executionId);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl engine-adapter -Dtest=DesiredStateExecutionRegistryTest`
Expected: 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateExecutionContext.java engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateExecutionRegistry.java engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateExecutionRegistryTest.java
git commit -m "feat(#47): DesiredStateExecutionContext + DesiredStateExecutionRegistry"
```

---

### Task 2: TransitionWorkflowGenerator — executionId in step args

**Files:**
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/TransitionWorkflowGenerator.java`
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/TransitionWorkflowGeneratorTest.java`

**Interfaces:**
- Consumes: `List<OrderedStep>`, `String namespace`, `String name`, `String version`, `String executionId`
- Produces: `Workflow` with `executionId` in each step's `with:` args

- [ ] **Step 1: Update tests — add executionId parameter and assertion**

In `TransitionWorkflowGeneratorTest.java`:

Change `setUp()`:
```java
// no changes needed — generator has no state
```

Change `emptyStepsProduceEmptyWorkflow`:
```java
@Test
void emptyStepsProduceEmptyWorkflow() {
    Workflow workflow = generator.generate(List.of(), "ns", "empty", "1.0.0", "exec-1");

    assertThat(workflow.getDocument()).isNotNull();
    assertThat(workflow.getDocument().getNamespace()).isEqualTo("ns");
    assertThat(workflow.getDocument().getName()).isEqualTo("empty");
    assertThat(workflow.getDocument().getVersion()).isEqualTo("1.0.0");
    assertThat(workflow.getDo()).isEmpty();
}
```

Change `singleProvisionStepProducesOneTask`:
```java
@Test
void singleProvisionStepProducesOneTask() {
    OrderedStep step = new OrderedStep(
        node("web-server", "vm"),
        StepAction.PROVISION
    );

    Workflow workflow = generator.generate(List.of(step),
        "io.casehub.desiredstate", "grow-phase", "1.0.0", "exec-abc");

    assertThat(workflow.getDo()).hasSize(1);
    TaskItem taskItem = workflow.getDo().get(0);
    assertThat(taskItem.getName()).isEqualTo("step-0-provision-web-server");

    CallFunction callFunction = taskItem.getTask().getCallTask().getCallFunction();
    assertThat(callFunction.getCall()).isEqualTo("desiredstate:dispatch");

    Map<String, Object> args = callFunction.getWith().getAdditionalProperties();
    assertThat(args).containsEntry("executionId", "exec-abc");
    assertThat(args).containsEntry("nodeId", "web-server");
    assertThat(args).containsEntry("nodeType", "vm");
    assertThat(args).containsEntry("action", "PROVISION");
}
```

Update all other test methods similarly — pass a dummy executionId string (e.g., `"exec-test"`) to `generate()`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl engine-adapter -Dtest=TransitionWorkflowGeneratorTest`
Expected: compilation failure — signature mismatch (5 params vs 4)

- [ ] **Step 3: Update TransitionWorkflowGenerator**

In `TransitionWorkflowGenerator.java`, change the `generate` method signature and `createTaskItem`:

```java
public Workflow generate(List<OrderedStep> steps, String namespace,
                         String name, String version, String executionId) {
    // ... document creation unchanged ...

    List<TaskItem> taskItems = new ArrayList<>(steps.size());
    for (int i = 0; i < steps.size(); i++) {
        OrderedStep step = steps.get(i);
        taskItems.add(createTaskItem(step, i, executionId));
    }

    return new Workflow(document, taskItems);
}

private TaskItem createTaskItem(OrderedStep step, int index, String executionId) {
    String taskName = "step-" + index + "-" + step.action().name().toLowerCase()
        + "-" + step.node().id().value();

    FunctionArguments args = new FunctionArguments()
        .withAdditionalProperty("executionId", executionId)
        .withAdditionalProperty("nodeId", step.node().id().value())
        .withAdditionalProperty("nodeType", step.node().type().value())
        .withAdditionalProperty("action", step.action().name());

    CallFunction callFunction = new CallFunction()
        .withCall(DISPATCH_FUNCTION)
        .withWith(args);

    Task task = new Task().withCallTask(
        new CallTask().withCallFunction(callFunction)
    );

    return new TaskItem(taskName, task);
}
```

- [ ] **Step 4: Fix CaseTransitionExecutor compilation — pass executionId to generator**

`CaseTransitionExecutor.buildCaseDefinition()` calls `workflowGenerator.generate()`. It now needs an executionId parameter. For now, pass a placeholder — Task 4 will wire it properly:

Change `buildCaseDefinition(TransitionPlan plan)` to `buildCaseDefinition(TransitionPlan plan, String executionId)` and pass executionId through to `workflowGenerator.generate()`:

```java
CaseDefinition buildCaseDefinition(TransitionPlan plan, String executionId) {
    // ... existing code ...

    if (!plan.removals().isEmpty()) {
        Workflow pruneWorkflow = workflowGenerator.generate(
            plan.removals(), NAMESPACE, "prune-phase", CASE_VERSION, executionId
        );
        // ... rest unchanged ...
    }

    // ... automatedAdditions/humanAdditions partitioning unchanged ...

    if (!automatedAdditions.isEmpty()) {
        Workflow growWorkflow = workflowGenerator.generate(
            automatedAdditions, NAMESPACE, "grow-phase", CASE_VERSION, executionId
        );
        // ... rest unchanged ...
    }

    // ... rest unchanged ...
}
```

Update `execute()` to generate executionId and pass it:

```java
@Override
public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
    if (plan.isEmpty()) {
        return Uni.createFrom().item(new TransitionResult(Map.of()));
    }

    return Uni.createFrom().completionStage(() -> {
        String executionId = UUID.randomUUID().toString();
        CaseDefinition caseDefinition = buildCaseDefinition(plan, executionId);
        // ... rest unchanged ...
    });
}
```

Add `import java.util.UUID;` to CaseTransitionExecutor.

Update `CaseTransitionExecutorTest` — all calls to `buildCaseDefinition(plan)` become `buildCaseDefinition(plan, "test-exec-id")`.

- [ ] **Step 5: Run all engine-adapter tests**

Run: `mvn --batch-mode test -pl engine-adapter`
Expected: ALL tests PASS

- [ ] **Step 6: Commit**

```bash
git add engine-adapter/src/main/java/io/casehub/desiredstate/engine/TransitionWorkflowGenerator.java engine-adapter/src/main/java/io/casehub/desiredstate/engine/CaseTransitionExecutor.java engine-adapter/src/test/java/io/casehub/desiredstate/engine/TransitionWorkflowGeneratorTest.java engine-adapter/src/test/java/io/casehub/desiredstate/engine/CaseTransitionExecutorTest.java
git commit -m "feat(#47): TransitionWorkflowGenerator accepts executionId, adds to step args"
```

---

### Task 3: DesiredStateDispatch — dispatch handler with PendingApproval lifecycle

**Files:**
- Create: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateDispatch.java`
- Create: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateDispatchTest.java`

**Interfaces:**
- Consumes: `CallableDispatchRegistry` (engine-flow), `DesiredStateExecutionRegistry` (Task 1), `NodeProvisioner` (api), `PendingApprovalHandler` (api)
- Produces: `DesiredStateDispatch` CDI bean — registers `"desiredstate:dispatch"`, returns `CompletableFuture<Map<String, Object>>` with keys: `nodeId`, `action`, `status` (SUCCESS/FAILED/SKIPPED/REJECTED/PENDING_APPROVAL), optional `reason`/`planReference`

- [ ] **Step 1: Write failing tests — provision paths**

```java
package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import io.casehub.desiredstate.testing.MockPendingApprovalHandler;
import io.casehub.engine.flow.CallableDispatchRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DesiredStateDispatchTest {

    private DesiredStateExecutionRegistry executionRegistry;
    private MockPendingApprovalHandler approvalHandler;
    private CallableDispatchRegistry callRegistry;
    private DesiredStateGraph graph;
    private DesiredNode testNode;

    // Captures context passed to provisioner
    private ProvisionContext capturedProvisionContext;
    private DeprovisionContext capturedDeprovisionContext;
    private NodeProvisioner provisioner;

    @BeforeEach
    void setUp() {
        executionRegistry = new DesiredStateExecutionRegistry();
        approvalHandler = new MockPendingApprovalHandler();
        callRegistry = new CallableDispatchRegistry();

        testNode = new DesiredNode(NodeId.of("app"), new TestSpec(), false);

        DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        graph = factory.of(List.of(testNode), List.of());

        capturedProvisionContext = null;
        capturedDeprovisionContext = null;
        provisioner = new NodeProvisioner() {
            @Override
            public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
                capturedProvisionContext = context;
                return new ProvisionResult.Success();
            }
            @Override
            public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
                capturedDeprovisionContext = context;
                return new DeprovisionResult.Success();
            }
        };

        DesiredStateDispatch dispatch = new DesiredStateDispatch(
            provisioner, approvalHandler, executionRegistry, callRegistry);
        dispatch.register();

        executionRegistry.register("exec-1", graph, "tenant-a");
    }

    @Test
    void provisionSuccess_checkNone() throws Exception {
        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SUCCESS");
        assertThat(result).containsEntry("nodeId", "app");
        assertThat(result).containsEntry("action", "PROVISION");
        assertThat(capturedProvisionContext).isNotNull();
        assertThat(capturedProvisionContext.hasApproval()).isFalse();
    }

    @Test
    void provisionPending_recordsPending() throws Exception {
        provisioner = new NodeProvisioner() {
            @Override
            public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
                return new ProvisionResult.PendingApproval(node.id(), "plan-ref-1");
            }
            @Override
            public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
                return new DeprovisionResult.Success();
            }
        };
        DesiredStateDispatch dispatch = new DesiredStateDispatch(
            provisioner, approvalHandler, executionRegistry, callRegistry);
        // Re-register overwrites — create fresh registry
        CallableDispatchRegistry freshRegistry = new CallableDispatchRegistry();
        dispatch = new DesiredStateDispatch(
            provisioner, approvalHandler, executionRegistry, freshRegistry);
        dispatch.register();

        Map<String, Object> result = freshRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "PENDING_APPROVAL");
        assertThat(result).containsEntry("planReference", "plan-ref-1");
        assertThat(approvalHandler.recorded).hasSize(1);
        assertThat(approvalHandler.recorded.get(0).planReference()).isEqualTo("plan-ref-1");
    }

    @Test
    void provisionSkipped_whenPending() throws Exception {
        approvalHandler.programCheck(
            NodeId.of("app"), StepAction.PROVISION,
            new ApprovalCheckResult.Pending("plan-ref-1"));

        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SKIPPED");
        assertThat((String) result.get("reason")).contains("pending approval");
    }

    @Test
    void provisionWithApproval_passesContext() throws Exception {
        PlanApproval approval = new PlanApproval("plan-ref-1", "admin", Instant.now());
        approvalHandler.programCheck(
            NodeId.of("app"), StepAction.PROVISION,
            new ApprovalCheckResult.Approved(approval));

        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SUCCESS");
        assertThat(capturedProvisionContext.hasApproval()).isTrue();
        assertThat(capturedProvisionContext.approval().planReference())
            .isEqualTo("plan-ref-1");
    }

    @Test
    void provisionRejected_acknowledgesAndReturnsRejected() throws Exception {
        approvalHandler.programCheck(
            NodeId.of("app"), StepAction.PROVISION,
            new ApprovalCheckResult.Rejected("plan-ref-1", "not authorized"));

        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "REJECTED");
        assertThat((String) result.get("reason")).contains("not authorized");
        assertThat(approvalHandler.acknowledgedRejections).hasSize(1);
    }

    @Test
    void provisionFailed_returnsFailure() throws Exception {
        provisioner = new NodeProvisioner() {
            @Override
            public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
                return new ProvisionResult.Failed("out of resources");
            }
            @Override
            public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
                return new DeprovisionResult.Success();
            }
        };
        CallableDispatchRegistry freshRegistry = new CallableDispatchRegistry();
        new DesiredStateDispatch(provisioner, approvalHandler, executionRegistry, freshRegistry)
            .register();

        Map<String, Object> result = freshRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "FAILED");
        assertThat(result).containsEntry("reason", "out of resources");
    }

    @Test
    void deprovisionSuccess() throws Exception {
        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "DEPROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SUCCESS");
        assertThat(result).containsEntry("action", "DEPROVISION");
        assertThat(capturedDeprovisionContext).isNotNull();
        assertThat(capturedDeprovisionContext.hasApproval()).isFalse();
    }

    @Test
    void deprovisionWithApproval_passesContext() throws Exception {
        PlanApproval approval = new PlanApproval("plan-ref-2", "admin", Instant.now());
        approvalHandler.programCheck(
            NodeId.of("app"), StepAction.DEPROVISION,
            new ApprovalCheckResult.Approved(approval));

        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "DEPROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SUCCESS");
        assertThat(capturedDeprovisionContext.hasApproval()).isTrue();
        assertThat(capturedDeprovisionContext.approval().planReference())
            .isEqualTo("plan-ref-2");
    }

    @Test
    void unknownNodeThrows() {
        CompletableFuture<Map<String, Object>> future =
            callRegistry.get("desiredstate:dispatch")
                .dispatch("wf-1", Map.of(
                    "executionId", "exec-1",
                    "nodeId", "nonexistent",
                    "nodeType", "vm",
                    "action", "PROVISION"));

        assertThat(future).isCompletedExceptionally();
    }

    private record TestSpec() implements NodeSpec {}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl engine-adapter -Dtest=DesiredStateDispatchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `DesiredStateDispatch` doesn't exist

- [ ] **Step 3: Implement DesiredStateDispatch**

```java
package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.*;
import io.casehub.engine.flow.CallableDispatchRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class DesiredStateDispatch {

    private final NodeProvisioner provisioner;
    private final PendingApprovalHandler pendingApprovalHandler;
    private final DesiredStateExecutionRegistry executionRegistry;
    private final CallableDispatchRegistry callRegistry;

    @Inject
    public DesiredStateDispatch(NodeProvisioner provisioner,
                                 PendingApprovalHandler pendingApprovalHandler,
                                 DesiredStateExecutionRegistry executionRegistry,
                                 CallableDispatchRegistry callRegistry) {
        this.provisioner = provisioner;
        this.pendingApprovalHandler = pendingApprovalHandler;
        this.executionRegistry = executionRegistry;
        this.callRegistry = callRegistry;
    }

    void register() {
        callRegistry.register("desiredstate:dispatch", this::dispatch);
    }

    @jakarta.annotation.PostConstruct
    void init() {
        register();
    }

    CompletableFuture<Map<String, Object>> dispatch(
            String workflowInstanceId, Map<String, Object> args) {
        try {
            String executionId = requireString(args, "executionId");
            String nodeIdStr = requireString(args, "nodeId");
            String actionStr = requireString(args, "action");

            DesiredStateExecutionContext ctx = executionRegistry.get(executionId);
            NodeId nodeId = NodeId.of(nodeIdStr);
            StepAction action = StepAction.valueOf(actionStr);

            DesiredNode node = ctx.graph().nodes().get(nodeId);
            if (node == null) {
                throw new IllegalArgumentException(
                    "Node not found in graph: " + nodeIdStr);
            }

            Map<String, Object> result = switch (action) {
                case PROVISION -> executeProvision(node, ctx);
                case DEPROVISION -> executeDeprovision(node, ctx);
            };

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private Map<String, Object> executeProvision(DesiredNode node,
                                                   DesiredStateExecutionContext ctx) {
        ProvisionContext context = new ProvisionContext(ctx.tenancyId(), ctx.graph());

        ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(
            node, StepAction.PROVISION, ctx.tenancyId());
        switch (approvalCheck) {
            case ApprovalCheckResult.Pending p ->
                { return resultMap(node, "PROVISION", "SKIPPED",
                    "pending approval: " + p.planReference(), null); }
            case ApprovalCheckResult.Rejected r -> {
                pendingApprovalHandler.acknowledgeRejection(
                    node, StepAction.PROVISION, ctx.tenancyId());
                return resultMap(node, "PROVISION", "REJECTED",
                    "approval rejected: " + r.reason(), null);
            }
            case ApprovalCheckResult.Approved a ->
                context = context.withApproval(a.approval());
            case ApprovalCheckResult.None ignored -> {}
        }

        ProvisionResult result = provisioner.provision(node, context);

        return switch (result) {
            case ProvisionResult.Success ignored ->
                resultMap(node, "PROVISION", "SUCCESS", null, null);
            case ProvisionResult.Failed f ->
                resultMap(node, "PROVISION", "FAILED", f.reason(), null);
            case ProvisionResult.PendingApproval pa -> {
                pendingApprovalHandler.recordPending(
                    node, StepAction.PROVISION, ctx.tenancyId(), pa.planReference());
                yield resultMap(node, "PROVISION", "PENDING_APPROVAL",
                    null, pa.planReference());
            }
        };
    }

    private Map<String, Object> executeDeprovision(DesiredNode node,
                                                     DesiredStateExecutionContext ctx) {
        DeprovisionContext context = new DeprovisionContext(ctx.tenancyId(), ctx.graph());

        ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(
            node, StepAction.DEPROVISION, ctx.tenancyId());
        switch (approvalCheck) {
            case ApprovalCheckResult.Pending p ->
                { return resultMap(node, "DEPROVISION", "SKIPPED",
                    "pending approval: " + p.planReference(), null); }
            case ApprovalCheckResult.Rejected r -> {
                pendingApprovalHandler.acknowledgeRejection(
                    node, StepAction.DEPROVISION, ctx.tenancyId());
                return resultMap(node, "DEPROVISION", "REJECTED",
                    "approval rejected: " + r.reason(), null);
            }
            case ApprovalCheckResult.Approved a ->
                context = context.withApproval(a.approval());
            case ApprovalCheckResult.None ignored -> {}
        }

        DeprovisionResult result = provisioner.deprovision(node, context);

        return switch (result) {
            case DeprovisionResult.Success ignored ->
                resultMap(node, "DEPROVISION", "SUCCESS", null, null);
            case DeprovisionResult.Failed f ->
                resultMap(node, "DEPROVISION", "FAILED", f.reason(), null);
            case DeprovisionResult.PendingApproval pa -> {
                pendingApprovalHandler.recordPending(
                    node, StepAction.DEPROVISION, ctx.tenancyId(), pa.planReference());
                yield resultMap(node, "DEPROVISION", "PENDING_APPROVAL",
                    null, pa.planReference());
            }
        };
    }

    private static Map<String, Object> resultMap(DesiredNode node, String action,
                                                   String status, String reason,
                                                   String planReference) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", node.id().value());
        map.put("action", action);
        map.put("status", status);
        if (reason != null) map.put("reason", reason);
        if (planReference != null) map.put("planReference", planReference);
        return map;
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required arg: " + key);
        }
        return value.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl engine-adapter -Dtest=DesiredStateDispatchTest`
Expected: ALL tests PASS

- [ ] **Step 5: Commit**

```bash
git add engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateDispatch.java engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateDispatchTest.java
git commit -m "feat(#47): DesiredStateDispatch — desiredstate:dispatch handler with PendingApproval lifecycle"
```

---

### Task 4: CaseTransitionExecutor — pre-filtering + registry integration

**Files:**
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/CaseTransitionExecutor.java`
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/CaseTransitionExecutorTest.java`

**Interfaces:**
- Consumes: `PendingApprovalHandler` (api), `DesiredStateExecutionRegistry` (Task 1), `TransitionWorkflowGenerator` (Task 2), `CaseHubRuntime` (engine-api)
- Produces: `CaseTransitionExecutor` with pre-filtered approval outcomes, executionId context registration, context cleanup after case start

- [ ] **Step 1: Write failing tests — pre-filter scenarios**

Add these tests to `CaseTransitionExecutorTest.java`. First update the setup to inject the new dependencies:

```java
private MockPendingApprovalHandler approvalHandler;
private DesiredStateExecutionRegistry executionRegistry;

@BeforeEach
void setUp() {
    workflowGenerator = new TransitionWorkflowGenerator();
    approvalHandler = new MockPendingApprovalHandler();
    executionRegistry = new DesiredStateExecutionRegistry();
    executor = new CaseTransitionExecutor(
        workflowGenerator, new StubCaseHubRuntime(),
        approvalHandler, executionRegistry);
}
```

Add import for `MockPendingApprovalHandler` and `DesiredStateExecutionRegistry`.

New tests:

```java
@Test
void pendingApproval_nodeSkipped() {
    DesiredNode node = new DesiredNode(NodeId.of("gated"), new TestSpec(), false);

    approvalHandler.programCheck(
        NodeId.of("gated"), StepAction.PROVISION,
        new ApprovalCheckResult.Pending("plan-ref-1"));

    DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    DesiredStateGraph graph = factory.of(List.of(node), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(node, StepAction.PROVISION)),
        graph, graph);

    TransitionResult result = executor.execute(plan, "tenant-a")
        .await().indefinitely();

    assertThat(result.outcomes().get(NodeId.of("gated")))
        .isInstanceOf(StepOutcome.Skipped.class);
    assertThat(((StepOutcome.Skipped) result.outcomes().get(NodeId.of("gated"))).reason())
        .contains("pending approval");
}

@Test
void rejectedApproval_nodeRejectedAndAcknowledged() {
    DesiredNode node = new DesiredNode(NodeId.of("rejected"), new TestSpec(), false);

    approvalHandler.programCheck(
        NodeId.of("rejected"), StepAction.PROVISION,
        new ApprovalCheckResult.Rejected("plan-ref-1", "policy violation"));

    DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    DesiredStateGraph graph = factory.of(List.of(node), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(node, StepAction.PROVISION)),
        graph, graph);

    TransitionResult result = executor.execute(plan, "tenant-a")
        .await().indefinitely();

    assertThat(result.outcomes().get(NodeId.of("rejected")))
        .isInstanceOf(StepOutcome.Rejected.class);
    assertThat(approvalHandler.acknowledgedRejections).hasSize(1);
}

@Test
void allNodesFiltered_noCaseStarted() {
    DesiredNode node = new DesiredNode(NodeId.of("pending"), new TestSpec(), false);

    approvalHandler.programCheck(
        NodeId.of("pending"), StepAction.PROVISION,
        new ApprovalCheckResult.Pending("plan-ref-1"));

    DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    DesiredStateGraph graph = factory.of(List.of(node), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(node, StepAction.PROVISION)),
        graph, graph);

    StubCaseHubRuntime runtime = new StubCaseHubRuntime();
    CaseTransitionExecutor exec = new CaseTransitionExecutor(
        workflowGenerator, runtime, approvalHandler, executionRegistry);

    TransitionResult result = exec.execute(plan, "tenant-a")
        .await().indefinitely();

    assertThat(result.outcomes()).containsKey(NodeId.of("pending"));
    assertThat(runtime.casesStarted).isZero();
}

@Test
void mixedPlan_filteredAndAutomated() {
    DesiredNode pendingNode = new DesiredNode(NodeId.of("gated"), new TestSpec(), false);
    DesiredNode autoNode = new DesiredNode(NodeId.of("auto"), new TestSpec(), false);

    approvalHandler.programCheck(
        NodeId.of("gated"), StepAction.PROVISION,
        new ApprovalCheckResult.Pending("plan-ref-1"));

    DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    DesiredStateGraph graph = factory.of(List.of(pendingNode, autoNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(
            new OrderedStep(pendingNode, StepAction.PROVISION),
            new OrderedStep(autoNode, StepAction.PROVISION)),
        graph, graph);

    TransitionResult result = executor.execute(plan, "tenant-a")
        .await().indefinitely();

    assertThat(result.outcomes().get(NodeId.of("gated")))
        .isInstanceOf(StepOutcome.Skipped.class);
    assertThat(result.outcomes().get(NodeId.of("auto")))
        .isInstanceOf(StepOutcome.Succeeded.class);
}
```

Also update `StubCaseHubRuntime` to track case starts:

```java
static class StubCaseHubRuntime implements CaseHubRuntime {
    int casesStarted = 0;

    @Override public CompletionStage<UUID> startCase(CaseDefinition definition) {
        casesStarted++;
        return CompletableFuture.completedFuture(UUID.randomUUID());
    }
    @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData) {
        casesStarted++;
        return CompletableFuture.completedFuture(UUID.randomUUID());
    }
    // ... rest unchanged, add casesStarted++ to all startCase overloads ...
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl engine-adapter -Dtest=CaseTransitionExecutorTest`
Expected: compilation failure — constructor signature mismatch

- [ ] **Step 3: Implement CTE modifications**

Modify `CaseTransitionExecutor`:

```java
@ApplicationScoped
public class CaseTransitionExecutor implements TransitionExecutor {

    private static final Logger LOG = Logger.getLogger(CaseTransitionExecutor.class);

    static final String NAMESPACE = "io.casehub.desiredstate";
    static final String CASE_VERSION = "1.0.0";

    private final TransitionWorkflowGenerator workflowGenerator;
    private final CaseHubRuntime caseHubRuntime;
    private final PendingApprovalHandler pendingApprovalHandler;
    private final DesiredStateExecutionRegistry executionRegistry;

    @Inject
    public CaseTransitionExecutor(TransitionWorkflowGenerator workflowGenerator,
                                  CaseHubRuntime caseHubRuntime,
                                  PendingApprovalHandler pendingApprovalHandler,
                                  DesiredStateExecutionRegistry executionRegistry) {
        this.workflowGenerator = workflowGenerator;
        this.caseHubRuntime = caseHubRuntime;
        this.pendingApprovalHandler = pendingApprovalHandler;
        this.executionRegistry = executionRegistry;
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
        if (plan.isEmpty()) {
            return Uni.createFrom().item(new TransitionResult(Map.of()));
        }

        Map<NodeId, StepOutcome> preFilteredOutcomes = new LinkedHashMap<>();
        List<OrderedStep> runnableRemovals = new ArrayList<>();
        List<OrderedStep> runnableAdditions = new ArrayList<>();

        for (OrderedStep step : plan.removals()) {
            StepOutcome filtered = checkApproval(step, tenancyId);
            if (filtered != null) {
                preFilteredOutcomes.put(step.node().id(), filtered);
            } else {
                runnableRemovals.add(step);
            }
        }

        for (OrderedStep step : plan.additions()) {
            StepOutcome filtered = checkApproval(step, tenancyId);
            if (filtered != null) {
                preFilteredOutcomes.put(step.node().id(), filtered);
            } else {
                runnableAdditions.add(step);
            }
        }

        // If everything was filtered, return immediately — no case needed
        if (runnableRemovals.isEmpty() && runnableAdditions.isEmpty()) {
            return Uni.createFrom().item(new TransitionResult(preFilteredOutcomes));
        }

        TransitionPlan runnablePlan = new TransitionPlan(
            runnableRemovals, runnableAdditions, plan.before(), plan.after());

        String executionId = UUID.randomUUID().toString();
        executionRegistry.register(executionId, plan.after(), tenancyId);

        return Uni.createFrom().completionStage(() -> {
            CaseDefinition caseDefinition = buildCaseDefinition(runnablePlan, executionId);

            Map<String, Object> inputData = Map.of(
                "removals", runnablePlan.removals().size(),
                "additions", runnablePlan.additions().size(),
                "graphVersion", runnablePlan.after().version()
            );

            return caseHubRuntime.startCase(caseDefinition, inputData);
        }).map(caseId -> {
            LOG.infof("Started desired-state transition case %s (removals=%d, additions=%d)",
                caseId, runnableRemovals.size(), runnableAdditions.size());

            executionRegistry.remove(executionId);

            Map<NodeId, StepOutcome> allOutcomes = new LinkedHashMap<>(preFilteredOutcomes);
            allOutcomes.putAll(buildOptimisticResult(runnablePlan, caseId).outcomes());
            return new TransitionResult(allOutcomes);
        });
    }

    private StepOutcome checkApproval(OrderedStep step, String tenancyId) {
        if (step.node().requiresHuman()) {
            return null; // human nodes handled separately in buildCaseDefinition
        }
        ApprovalCheckResult check = pendingApprovalHandler.check(
            step.node(), step.action(), tenancyId);
        return switch (check) {
            case ApprovalCheckResult.Pending p ->
                new StepOutcome.Skipped("pending approval: " + p.planReference());
            case ApprovalCheckResult.Rejected r -> {
                pendingApprovalHandler.acknowledgeRejection(
                    step.node(), step.action(), tenancyId);
                yield new StepOutcome.Rejected("approval rejected: " + r.reason());
            }
            case ApprovalCheckResult.Approved ignored -> null; // include in case
            case ApprovalCheckResult.None ignored -> null; // include in case
        };
    }

    // buildCaseDefinition and buildOptimisticResult unchanged from Task 2
    // (already updated with executionId parameter)
}
```

Add imports: `java.util.UUID`, `java.util.ArrayList`, `java.util.LinkedHashMap`, `io.casehub.desiredstate.api.PendingApprovalHandler`, `io.casehub.desiredstate.api.ApprovalCheckResult`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl engine-adapter -Dtest=CaseTransitionExecutorTest`
Expected: ALL tests PASS

- [ ] **Step 5: Run full module tests**

Run: `mvn --batch-mode test -pl engine-adapter`
Expected: ALL tests PASS (CaseTransitionExecutorTest + TransitionWorkflowGeneratorTest + DesiredStateDispatchTest + DesiredStateExecutionRegistryTest)

- [ ] **Step 6: Commit**

```bash
git add engine-adapter/src/main/java/io/casehub/desiredstate/engine/CaseTransitionExecutor.java engine-adapter/src/test/java/io/casehub/desiredstate/engine/CaseTransitionExecutorTest.java
git commit -m "feat(#47): CTE pre-filters approval-gated nodes, registers execution context"
```

---

### Task 5: Delete DesiredStateWorkerFunction + full build

**Files:**
- Delete: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateWorkerFunction.java`

**Interfaces:**
- Consumes: nothing
- Produces: nothing (removal)

- [ ] **Step 1: Verify no references to DesiredStateWorkerFunction**

Use IntelliJ `ide_find_references` for `DesiredStateWorkerFunction` — confirm zero callers (already verified during exploration).

- [ ] **Step 2: Delete the file**

```bash
rm engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateWorkerFunction.java
```

- [ ] **Step 3: Full project build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules compile, all tests pass

- [ ] **Step 4: Commit**

```bash
git add -u engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateWorkerFunction.java
git commit -m "refactor(#47): delete DesiredStateWorkerFunction — logic moved to DesiredStateDispatch"
```

---
