# CTE PendingApproval Integration — Design Spec

**Date:** 2026-06-29
**Issue:** casehubio/casehub-desiredstate#47
**Blocker:** casehubio/engine#590 (CallableDispatchRegistry)
**Branch:** issue-47-cte-pending-approval

## Problem

Three interrelated failures in CaseTransitionExecutor:

1. **Dispatch mechanism non-functional.** `TransitionWorkflowGenerator` emits `call: desiredstate:dispatch` steps. `CasehubCallableTaskBuilder` (engine-flow) only handles `casehub:dispatch` and throws on anything else. Workflow steps cannot execute.

2. **PendingApproval silently swallowed.** `DesiredStateWorkerFunction` maps `PendingApproval` to `Map.of("status", "PENDING_APPROVAL", ...)` — nobody reads it. No approval WorkItem is created. Next reconciliation cycle: ABSENT → plan → provisioner called again without approval context → PendingApproval → infinite loop.

3. **Optimistic result hides all failures.** `buildOptimisticResult()` marks all automated nodes as `Succeeded` immediately after `startCase()`. Fault feedback in the reconciliation loop never fires because there are no Failed/Rejected outcomes.

## Approach

Fix the dispatch mechanism via a registry-based extensible call dispatch in engine-flow (cross-repo). Then wire PendingApproval handling into the dispatch handler, mirroring SimpleTransitionExecutor's check/provision/record/acknowledge pattern. CTE pre-filters approval-gated nodes before building the case.

### Why keep the workflow (not a plain WorkerFunction)

Worker(Workflow) provides per-step observability in the case plan, per-step retry/timeout (Serverless Workflow spec), declarative parallel fork/join for independent nodes, crash recovery (resume from last completed step), standard format (CNCF Serverless Workflow 1.0), and composability. The reconciliation loop provides cycle-level retry; the workflow provides step-level retry. These are complementary, not redundant.

The dispatch mechanism is broken because it was V1. That's a fixable implementation gap, not evidence that workflows are wrong for this problem.

## Cross-Repo Prerequisite: engine#590

### CallableDispatchRegistry (casehub-engine-flow)

**`CallableDispatcher`** — functional interface in engine-flow:

```java
@FunctionalInterface
public interface CallableDispatcher {
    CompletableFuture<Map<String, Object>> dispatch(
        String workflowInstanceId, Map<String, Object> args);
}
```

- `workflowInstanceId` — from `workflowContext.instanceData().id()`, for correlation via `FlowExecutionRegistry`
- `args` — full `FunctionArguments.getAdditionalProperties()` from the step's `with:` block

**`CallableDispatchRegistry`** — `@ApplicationScoped` singleton:

```java
@ApplicationScoped
public class CallableDispatchRegistry {
    private final ConcurrentHashMap<String, CallableDispatcher> dispatchers
        = new ConcurrentHashMap<>();

    public void register(String callName, CallableDispatcher dispatcher) {
        CallableDispatcher existing = dispatchers.putIfAbsent(callName, dispatcher);
        if (existing != null) {
            throw new IllegalStateException(
                "Dispatcher already registered for: " + callName);
        }
    }

    public CallableDispatcher get(String callName) {
        CallableDispatcher d = dispatchers.get(callName);
        if (d == null) {
            throw new UnsupportedOperationException(
                "No dispatcher registered for call name: " + callName);
        }
        return d;
    }
}
```

**Refactored `CasehubCallableTaskBuilder`:**

- `init()` stores call name + full args map in ThreadLocals (no CDI access — ServiceLoader creates builder early)
- `build()` captures both, lambda delegates to `registry.get(callName).dispatch(instanceId, args)`
- No validation in `init()` — registry throws at dispatch time
- Full args forwarded to dispatcher (current code only extracts `capability`)

**`CasehubDispatch` self-registration:**

```java
@PostConstruct
void register() {
    registry.register("casehub:dispatch", (instanceId, args) -> {
        String capability = (String) args.get("capability");
        return dispatch(instanceId, capability);
    });
}
```

## engine-adapter Changes (this issue)

### DesiredStateDispatch

`@ApplicationScoped` CDI bean. Registers for `desiredstate:dispatch` at `@PostConstruct`. Handles the full PendingApproval lifecycle within workflow steps.

```java
@ApplicationScoped
public class DesiredStateDispatch {

    private final NodeProvisioner provisioner;
    private final PendingApprovalHandler pendingApprovalHandler;
    private final DesiredStateExecutionRegistry executionRegistry;
    private final CallableDispatchRegistry callRegistry;

    @PostConstruct
    void register() {
        callRegistry.register("desiredstate:dispatch", this::dispatch);
    }

    CompletableFuture<Map<String, Object>> dispatch(
            String workflowInstanceId, Map<String, Object> args) {
        String executionId = (String) args.get("executionId");
        String nodeIdStr = (String) args.get("nodeId");
        String actionStr = (String) args.get("action");

        DesiredStateExecutionContext ctx = executionRegistry.get(executionId);
        DesiredNode node = ctx.graph().nodes().get(NodeId.of(nodeIdStr));
        StepAction action = StepAction.valueOf(actionStr);

        return CompletableFuture.completedFuture(
            switch (action) {
                case PROVISION -> executeProvision(node, ctx);
                case DEPROVISION -> executeDeprovision(node, ctx);
            }
        );
    }
}
```

**Provision lifecycle** (mirrors SimpleTransitionExecutor exactly):

```
1. check(node, PROVISION, tenancyId)
   → Pending(planRef)  → return {status: SKIPPED, reason: "pending approval: <planRef>"}
   → Rejected(ref, r)  → acknowledgeRejection() → return {status: REJECTED, reason: r}
   → Approved(approval) → context = context.withApproval(approval)
   → None              → proceed

2. provisioner.provision(node, context)
   → Success       → return {status: SUCCESS}
   → Failed(r)     → return {status: FAILED, reason: r}
   → PendingApproval(id, ref) → recordPending() → return {status: PENDING_APPROVAL, planReference: ref}
```

Same pattern for deprovision with `DeprovisionContext`.

### DesiredStateExecutionRegistry

`@ApplicationScoped` CDI bean. Holds `(DesiredStateGraph, tenancyId)` keyed by `executionId`.

```java
@ApplicationScoped
public class DesiredStateExecutionRegistry {
    private final ConcurrentHashMap<String, DesiredStateExecutionContext> contexts
        = new ConcurrentHashMap<>();

    public void register(String executionId, DesiredStateGraph graph, String tenancyId) {
        contexts.put(executionId, new DesiredStateExecutionContext(graph, tenancyId));
    }

    public DesiredStateExecutionContext get(String executionId) {
        DesiredStateExecutionContext ctx = contexts.get(executionId);
        if (ctx == null) {
            throw new IllegalStateException(
                "No execution context for: " + executionId);
        }
        return ctx;
    }

    public void remove(String executionId) {
        contexts.remove(executionId);
    }
}

public record DesiredStateExecutionContext(DesiredStateGraph graph, String tenancyId) {}
```

### Context passing — no race condition

1. CTE generates `executionId = UUID.randomUUID().toString()`
2. CTE registers `(executionId, graph, tenancyId)` in `DesiredStateExecutionRegistry`
3. CTE passes `executionId` to `TransitionWorkflowGenerator` which embeds it in every step's `with:` args
4. CTE calls `startCase()` — the workflow definition already contains the executionId
5. When dispatch runs, it reads `executionId` from args and looks up context

Registration happens BEFORE `startCase()`, so the context is always available.

**Cleanup:** CTE removes the context in its `.map()` callback after `startCase()` completes and the optimistic result is built. The context is only needed during workflow execution, which happens synchronously within the `startCase()` call chain (FlowWorkerFunctionHandler runs the workflow on a virtual thread and completes the CompletionStage). If case execution becomes truly asynchronous in a future version, cleanup moves to a case completion observer.

### CaseTransitionExecutor modifications

**Inject PendingApprovalHandler and DesiredStateExecutionRegistry.**

**Pre-filter approval-gated nodes** in `execute()`:

```java
// For each step in the plan:
ApprovalCheckResult check = pendingApprovalHandler.check(
    step.node(), step.action(), tenancyId);

switch (check) {
    case Pending p -> {
        outcomes.put(step.node().id(),
            new StepOutcome.Skipped("pending approval: " + p.planReference()));
        // exclude from case
    }
    case Rejected r -> {
        pendingApprovalHandler.acknowledgeRejection(
            step.node(), step.action(), tenancyId);
        outcomes.put(step.node().id(),
            new StepOutcome.Rejected("approval rejected: " + r.reason()));
        // exclude from case
    }
    case Approved a -> {
        // include in case — PlanApproval available via check() in DesiredStateDispatch
        runnableSteps.add(step);
    }
    case None ignored -> {
        runnableSteps.add(step);
    }
}
```

**If all steps are pre-filtered** (nothing runnable), return immediately without starting a case.

**Generate executionId and register context** before `startCase()`.

**Report real outcomes** for pre-filtered nodes. Case-executed nodes remain optimistic in V1 (proper case completion observation is a follow-up).

### TransitionWorkflowGenerator modifications

Accept `executionId` parameter. Add to each step's args:

```java
FunctionArguments args = new FunctionArguments()
    .withAdditionalProperty("executionId", executionId)
    .withAdditionalProperty("nodeId", step.node().id().value())
    .withAdditionalProperty("nodeType", step.node().type().value())
    .withAdditionalProperty("action", step.action().name());
```

### DesiredStateWorkerFunction — refactored

Logic moves to `DesiredStateDispatch`. DSWF is either deleted or retained as a thin delegate if useful for testing. The standalone `execute(Map, Graph, String)` signature is replaced by the dispatcher's `dispatch(instanceId, args)`.

## Reconciliation loop interaction

```
Cycle N:  ABSENT → plan → CTE check(): None → start case
          → DesiredStateDispatch → provisioner → PendingApproval → recordPending()
Cycle N+1: ABSENT → plan → CTE check(): Pending → Skipped (no case started)
...
Cycle N+K: Human approves WorkItem
Cycle N+K+1: ABSENT → plan → CTE check(): Approved → start case with PlanApproval
             → DesiredStateDispatch check(): Approved → provisioner(context.withApproval()) → Success
Cycle N+K+2: PRESENT → no plan → done
```

Rejection path:
```
Cycle N+K: Human rejects WorkItem
Cycle N+K+1: ABSENT → plan → CTE check(): Rejected → acknowledgeRejection()
             → StepOutcome.Rejected → faultFeedback → FaultType.APPROVAL_REJECTED
             → FaultPolicy evaluates → graph mutation (remove node, escalate, etc.)
```

## Scope

| File | Module | Action |
|---|---|---|
| `DesiredStateDispatch.java` | engine-adapter | Create |
| `DesiredStateExecutionRegistry.java` | engine-adapter | Create |
| `DesiredStateExecutionContext.java` | engine-adapter | Create |
| `CaseTransitionExecutor.java` | engine-adapter | Modify |
| `TransitionWorkflowGenerator.java` | engine-adapter | Modify |
| `DesiredStateWorkerFunction.java` | engine-adapter | Refactor/delete |
| `CaseTransitionExecutorTest.java` | engine-adapter | Extend |
| `DesiredStateDispatchTest.java` | engine-adapter | Create |
| `TransitionWorkflowGeneratorTest.java` | engine-adapter | Update |

## Dependencies

- **Blocked by:** casehubio/engine#590 — `CallableDispatchRegistry` must ship first
- **Blocks:** nothing — downstream consumers benefit automatically
- **Related:** casehubio/work#281, casehubio/work#282 — gate the WorkItemPendingApprovalHandler in work-adapter (separate issue, not blocked by this)

## Design decisions

**Why pre-filter in CTE AND handle in DesiredStateDispatch?** CTE pre-filter is an optimization — avoids starting a case when all nodes are pending/rejected. DesiredStateDispatch is the correctness mechanism — handles first-time PendingApproval (when check() returns None and the provisioner returns PendingApproval at runtime). Both are needed.

**Why executionId in workflow args, not caseId?** `startCase()` returns caseId asynchronously. Registering context by caseId creates a race condition — the workflow may start executing before registration completes. `executionId` is generated before `startCase()` and embedded in the workflow definition, so context is always available.

**Why not use `casehub:dispatch`?** Circular dispatch — the workflow step would dispatch to the worker executing the workflow. `desiredstate:dispatch` calls the provisioner directly, not through the engine's WorkOrchestrator.

**Why keep the workflow instead of a plain WorkerFunction?** Per-step observability, per-step retry, parallel fork/join (future), crash recovery, standard Serverless Workflow format, composability. The simplicity of a loop avoids work; it doesn't serve the architecture.
