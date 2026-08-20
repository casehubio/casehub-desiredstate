# PendingApproval Gate, Lifecycle Transitions, and Planner-Backed GoalCompiler — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #46 — feat: pipeline example — PendingApproval gate in three-tier fault escalation
**Issue group:** #46, #58, #56

**Goal:** Evolve the GoalCompiler SPI to support lifecycle phases with completion conditions,
add PendingApproval gating to the pipeline example, and create a new expansion example
demonstrating a planner-backed GoalCompiler with lifecycle transitions.

**Architecture:** GoalCompiler.compile() returns `CompilationResult` (sealed: `SingleGraph` |
`Lifecycle`). New `LifecycleManager` in runtime orchestrates phase transitions via
`ReconciliationListener` and CAS-based graph swaps. Pipeline example gains per-stage approval
gates. New expansion example demonstrates HTN planner behind GoalCompiler with build→defend
lifecycle.

**Tech Stack:** Java 21, Mutiny, CDI (Quarkus), JUnit 5, AssertJ

## Global Constraints

- All new API types go in `api/src/main/java/io/casehub/desiredstate/api/`
- All new runtime types go in `runtime/src/main/java/io/casehub/desiredstate/runtime/`
- `@DefaultBean @ApplicationScoped` for no-op SPI defaults in runtime
- Immutable records with defensive copies (`List.copyOf()`)
- No backward-compatibility shims — callers pattern-match on sealed types explicitly
- `CompletionCondition` evaluated against original phase graph, not the current mutated graph
- ReconciliationListener fires unconditionally (including empty-plan cycles)
- Phase transitions use CAS via `compareAndSetDesired()`

---

### Task 1: API Types — CompilationResult, Phase, CompletionCondition

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/CompilationResult.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/Phase.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/CompletionCondition.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/CompilationResultTest.java`

**Interfaces:**
- Consumes: `DesiredStateGraph`, `ActualState`, `NodeStatus` (existing API types)
- Produces: `CompilationResult` (sealed: `SingleGraph`, `Lifecycle`), `Phase`, `CompletionCondition`
  (used by Tasks 2, 3, 4, 5, 6, 7)

- [ ] **Step 1: Write failing tests for CompilationResult, Phase, CompletionCondition**

```java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CompilationResultTest {

    private final DesiredStateGraph emptyGraph = new TestGraph();
    private final DesiredStateGraph otherGraph = new TestGraph();

    @Test
    void singleGraph_wrapsGraph() {
        CompilationResult result = CompilationResult.single(emptyGraph);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        assertThat(((CompilationResult.SingleGraph) result).graph()).isSameAs(emptyGraph);
    }

    @Test
    void lifecycle_requiresNonEmptyPhases() {
        assertThatThrownBy(() -> CompilationResult.lifecycle(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifecycle_defensiveCopy() {
        var phases = new java.util.ArrayList<>(List.of(
            new Phase("build", emptyGraph, CompletionCondition.allPresent()),
            new Phase("defend", otherGraph, CompletionCondition.never())
        ));
        CompilationResult.Lifecycle lifecycle = (CompilationResult.Lifecycle) CompilationResult.lifecycle(phases);
        phases.clear();
        assertThat(lifecycle.phases()).hasSize(2);
    }

    @Test
    void phase_nullIdThrows() {
        assertThatThrownBy(() -> new Phase(null, emptyGraph, CompletionCondition.never()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void phase_isTerminal() {
        Phase terminal = new Phase("t", emptyGraph, CompletionCondition.never());
        Phase nonTerminal = new Phase("nt", emptyGraph, CompletionCondition.allPresent());
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(nonTerminal.isTerminal()).isFalse();
    }

    @Test
    void allPresent_allNodesPresent_returnsTrue() {
        var graph = new TestGraph(Map.of(
            NodeId.of("a"), new DesiredNode(NodeId.of("a"), new TestSpec(), false),
            NodeId.of("b"), new DesiredNode(NodeId.of("b"), new TestSpec(), false)
        ));
        var actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.PRESENT,
            NodeId.of("b"), NodeStatus.PRESENT
        ));
        assertThat(CompletionCondition.allPresent().isComplete(graph, actual)).isTrue();
    }

    @Test
    void allPresent_someAbsent_returnsFalse() {
        var graph = new TestGraph(Map.of(
            NodeId.of("a"), new DesiredNode(NodeId.of("a"), new TestSpec(), false)
        ));
        var actual = new ActualState(Map.of(NodeId.of("a"), NodeStatus.ABSENT));
        assertThat(CompletionCondition.allPresent().isComplete(graph, actual)).isFalse();
    }

    @Test
    void allPresent_unknownStatus_returnsFalse() {
        var graph = new TestGraph(Map.of(
            NodeId.of("a"), new DesiredNode(NodeId.of("a"), new TestSpec(), false)
        ));
        var actual = new ActualState(Map.of());
        assertThat(CompletionCondition.allPresent().isComplete(graph, actual)).isFalse();
    }

    @Test
    void never_alwaysReturnsFalse() {
        assertThat(CompletionCondition.never().isComplete(emptyGraph, new ActualState(Map.of()))).isFalse();
    }

    @Test
    void patternMatch_exhaustive() {
        CompilationResult single = CompilationResult.single(emptyGraph);
        String type = switch (single) {
            case CompilationResult.SingleGraph sg -> "single";
            case CompilationResult.Lifecycle lc -> "lifecycle";
        };
        assertThat(type).isEqualTo("single");
    }

    // Minimal test graph — enough for CompilationResult tests
    private record TestSpec() implements NodeSpec {}

    private static class TestGraph implements DesiredStateGraph {
        private final Map<NodeId, DesiredNode> nodes;
        TestGraph() { this(Map.of()); }
        TestGraph(Map<NodeId, DesiredNode> nodes) { this.nodes = Map.copyOf(nodes); }
        @Override public Map<NodeId, DesiredNode> nodes() { return nodes; }
        @Override public java.util.Set<Dependency> dependencies() { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> dependenciesOf(NodeId node) { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> dependentsOf(NodeId node) { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> roots() { return nodes.keySet(); }
        @Override public java.util.Set<NodeId> leaves() { return nodes.keySet(); }
        @Override public int version() { return 0; }
        @Override public boolean isEmpty() { return nodes.isEmpty(); }
        @Override public DesiredStateGraph withNode(DesiredNode node) { return this; }
        @Override public DesiredStateGraph withoutNode(NodeId id) { return this; }
        @Override public DesiredStateGraph withDependency(Dependency dep) { return this; }
        @Override public DesiredStateGraph withoutDependency(Dependency dep) { return this; }
        @Override public DesiredStateGraph withMutation(GraphMutation mutation) { return this; }
        @Override public DesiredStateGraph overlay(DesiredStateGraph other) { return this; }
        @Override public DesiredStateGraph connect(DesiredStateGraph other) { return this; }
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=CompilationResultTest`
Expected: compilation failure — `CompilationResult`, `Phase`, `CompletionCondition` not found

- [ ] **Step 3: Implement CompletionCondition**

Create `api/src/main/java/io/casehub/desiredstate/api/CompletionCondition.java`:

```java
package io.casehub.desiredstate.api;

@FunctionalInterface
public interface CompletionCondition {
    boolean isComplete(DesiredStateGraph desired, ActualState actual);

    static CompletionCondition allPresent() {
        return (desired, actual) -> desired.nodes().keySet().stream()
            .allMatch(id -> actual.statuses().getOrDefault(id, NodeStatus.UNKNOWN) == NodeStatus.PRESENT);
    }

    static CompletionCondition never() { return new Never(); }

    record Never() implements CompletionCondition {
        @Override
        public boolean isComplete(DesiredStateGraph desired, ActualState actual) { return false; }
    }
}
```

- [ ] **Step 4: Implement Phase**

Create `api/src/main/java/io/casehub/desiredstate/api/Phase.java`:

```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record Phase(String id, DesiredStateGraph graph, CompletionCondition completionCondition) {
    public Phase {
        Objects.requireNonNull(id, "Phase id must not be null");
        Objects.requireNonNull(graph, "Phase graph must not be null");
        Objects.requireNonNull(completionCondition, "Phase completionCondition must not be null");
    }

    public boolean isTerminal() {
        return completionCondition instanceof CompletionCondition.Never;
    }
}
```

- [ ] **Step 5: Implement CompilationResult**

Create `api/src/main/java/io/casehub/desiredstate/api/CompilationResult.java`:

```java
package io.casehub.desiredstate.api;

import java.util.List;

public sealed interface CompilationResult {

    record SingleGraph(DesiredStateGraph graph) implements CompilationResult {}

    record Lifecycle(List<Phase> phases) implements CompilationResult {}

    static CompilationResult single(DesiredStateGraph graph) {
        return new SingleGraph(graph);
    }

    static CompilationResult lifecycle(List<Phase> phases) {
        if (phases.isEmpty()) throw new IllegalArgumentException("Lifecycle must have at least one phase");
        return new Lifecycle(List.copyOf(phases));
    }
}
```

- [ ] **Step 6: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=CompilationResultTest`
Expected: all tests PASS

- [ ] **Step 7: Commit**

```
feat(#58): add CompilationResult, Phase, CompletionCondition API types
```

---

### Task 2: GoalCompiler and SituationRecompiler SPI Changes + Migration

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/GoalCompiler.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/SituationRecompiler.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpSituationRecompiler.java`
- Modify: `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/DungeonGoalCompiler.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineGoalCompiler.java`
- Modify: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/attack/AttackGoalCompiler.java`
- Modify: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/defense/DefenseGoalCompiler.java`
- Modify: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/distribution/DistributionGoalCompiler.java`
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatch.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpSituationRecompilerTest.java`
- Test: existing test suites must continue to pass after migration

**Interfaces:**
- Consumes: `CompilationResult`, `Phase`, `CompletionCondition` (from Task 1)
- Produces: Updated `GoalCompiler<G>` and `SituationRecompiler` SPIs (used by all GoalCompiler
  callers and SituationRecompiler callers across the repo)

- [ ] **Step 1: Change GoalCompiler return type**

Modify `api/src/main/java/io/casehub/desiredstate/api/GoalCompiler.java`:

```java
package io.casehub.desiredstate.api;

public interface GoalCompiler<G> {
    CompilationResult compile(G goals, DesiredStateGraphFactory factory);
}
```

- [ ] **Step 2: Change SituationRecompiler return type**

Modify `api/src/main/java/io/casehub/desiredstate/api/SituationRecompiler.java` — change
`Optional<DesiredStateGraph>` to `Optional<CompilationResult>`:

```java
package io.casehub.desiredstate.api;

import io.casehub.ras.api.ActiveSituation;
import java.util.Optional;

public interface SituationRecompiler {
    Optional<CompilationResult> recompile(
        DesiredStateGraph current,
        ActiveSituation situation,
        DesiredStateGraphFactory factory);
}
```

- [ ] **Step 3: Update NoOpSituationRecompiler**

Modify `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpSituationRecompiler.java` —
change return type to `Optional<CompilationResult>`:

```java
@Override
public Optional<CompilationResult> recompile(
        DesiredStateGraph current,
        ActiveSituation situation,
        DesiredStateGraphFactory factory) {
    return Optional.empty();
}
```

Update the import from `DesiredStateGraph` to `CompilationResult`.

- [ ] **Step 4: Migrate all GoalCompiler implementations**

Each one wraps its return in `CompilationResult.single()`. For each file, the change is:

**DungeonGoalCompiler** — line 47, change `return factory.of(nodes, dependencies);` to:
```java
return CompilationResult.single(factory.of(nodes, dependencies));
```

**PipelineGoalCompiler** — line 132, change `return graph;` (after `MedallionLayerConstraint.validate(graph)`) to:
```java
return CompilationResult.single(graph);
```

**AttackGoalCompiler** — wrap final `return` in `CompilationResult.single()`.

**DefenseGoalCompiler** — wrap final `return` in `CompilationResult.single()`.

**DistributionGoalCompiler** — wrap final `return` in `CompilationResult.single()`.

Add `import io.casehub.desiredstate.api.CompilationResult;` to each file.

- [ ] **Step 5: Update DesiredStateReplanDispatch**

Modify `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatch.java`:

Change `replan()` method — `recompile()` now returns `Optional<CompilationResult>`. For now,
extract the graph from the result using pattern matching (LifecycleManager integration is Task 4):

```java
Optional<CompilationResult> newResult = situationRecompiler.recompile(
    current, situation, graphFactory);

Map<String, Object> result = new LinkedHashMap<>();
result.put("situationId", situationId);

if (newResult.isPresent()) {
    DesiredStateGraph newGraph = switch (newResult.get()) {
        case CompilationResult.SingleGraph sg -> sg.graph();
        case CompilationResult.Lifecycle lc -> lc.phases().getFirst().graph();
    };
    reconciliationLoop.updateDesired(tenancyId, newGraph);
    result.put("status", "REPLANNED");
} else {
    result.put("status", "NO_CHANGE");
}
```

Update imports: add `CompilationResult`, remove now-unused `DesiredStateGraph` import if
applicable (check — it's still used for `current` parameter).

- [ ] **Step 6: Fix any remaining callers that extract DesiredStateGraph from compile()**

Search for all callers of `.compile(` across the repo. Each must handle `CompilationResult`.
In test files, the typical pattern is:

```java
// Before:
DesiredStateGraph graph = compiler.compile(blueprint, factory);
// After:
CompilationResult result = compiler.compile(blueprint, factory);
DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
```

Use IntelliJ `ide_find_references` on `GoalCompiler.compile` to find all call sites.

- [ ] **Step 7: Run full build to verify all migrations**

Run: `mvn --batch-mode test`
Expected: full build passes — all existing tests green

- [ ] **Step 8: Commit**

```
feat(#58): evolve GoalCompiler and SituationRecompiler to return CompilationResult
```

---

### Task 3: ReconciliationListener, compareAndSetDesired, and Listener Hook

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/ReconciliationListener.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopLifecycleTest.java`

**Interfaces:**
- Consumes: `DesiredStateGraph`, `ActualState` (existing API types)
- Produces: `ReconciliationListener` (functional interface), `ReconciliationLoop.start(String, DesiredStateGraph, ReconciliationListener)`, `ReconciliationLoop.setListener(String, ReconciliationListener)`, `ReconciliationLoop.compareAndSetDesired(String, DesiredStateGraph, DesiredStateGraph)` (used by Task 4)

- [ ] **Step 1: Write failing tests**

Create `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopLifecycleTest.java`:

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.smallrye.mutiny.Multi;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class ReconciliationLoopLifecycleTest {

    private TransitionPlanner planner;
    private TestActualStateAdapter adapter;
    private FaultPolicyEngine faultPolicyEngine;
    private ReconciliationLoop loop;
    private List<ListenerCall> listenerCalls;

    record ListenerCall(String tenancyId, DesiredStateGraph desired, ActualState actual) {}

    @BeforeEach
    void setUp() {
        planner = new TransitionPlanner();
        adapter = new TestActualStateAdapter();
        faultPolicyEngine = new FaultPolicyEngine(List.of());
        listenerCalls = new CopyOnWriteArrayList<>();
    }

    @Test
    void listenerFiresOnReconciliationCycle() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), new TestSpec(), false);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.ABSENT);

        CountDownLatch latch = new CountDownLatch(1);
        ReconciliationListener listener = (tid, d, a) -> {
            listenerCalls.add(new ListenerCall(tid, d, a));
            latch.countDown();
        };

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofSeconds(60));
        loop.start("t1", graph, listener);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listenerCalls).isNotEmpty();
        assertThat(listenerCalls.get(0).tenancyId()).isEqualTo("t1");
        loop.stop("t1");
    }

    @Test
    void listenerFiresOnEmptyPlanCycles() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), new TestSpec(), false);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        CountDownLatch latch = new CountDownLatch(1);
        ReconciliationListener listener = (tid, d, a) -> {
            listenerCalls.add(new ListenerCall(tid, d, a));
            latch.countDown();
        };

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofSeconds(60));
        loop.start("t1", graph, listener);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listenerCalls).isNotEmpty();
        loop.stop("t1");
    }

    @Test
    void compareAndSetDesired_succeedsWhenExpectedMatches() {
        DesiredStateGraph graph1 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("a"), new TestSpec(), false));
        DesiredStateGraph graph2 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("b"), new TestSpec(), false));

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofSeconds(60));
        loop.start("t1", graph1);

        boolean swapped = loop.compareAndSetDesired("t1", graph1, graph2);
        assertThat(swapped).isTrue();
        assertThat(loop.getDesired("t1")).isSameAs(graph2);
        loop.stop("t1");
    }

    @Test
    void compareAndSetDesired_failsWhenExpectedDoesNotMatch() {
        DesiredStateGraph graph1 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("a"), new TestSpec(), false));
        DesiredStateGraph graph2 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("b"), new TestSpec(), false));
        DesiredStateGraph graph3 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("c"), new TestSpec(), false));

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofSeconds(60));
        loop.start("t1", graph1);
        loop.updateDesired("t1", graph2);

        boolean swapped = loop.compareAndSetDesired("t1", graph1, graph3);
        assertThat(swapped).isFalse();
        assertThat(loop.getDesired("t1")).isSameAs(graph2);
        loop.stop("t1");
    }

    @Test
    void setListener_onRunningTenant() throws Exception {
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("a"), new TestSpec(), false));
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofMillis(200));
        loop.start("t1", graph);

        CountDownLatch latch = new CountDownLatch(1);
        loop.setListener("t1", (tid, d, a) -> {
            listenerCalls.add(new ListenerCall(tid, d, a));
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listenerCalls).isNotEmpty();
        loop.stop("t1");
    }

    private record TestSpec() implements NodeSpec {}

    private static class TestActualStateAdapter implements ActualStateAdapter {
        private final Map<NodeId, NodeStatus> statuses = new HashMap<>();
        void setStatus(NodeId id, NodeStatus status) { statuses.put(id, status); }
        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return new ActualState(Map.copyOf(statuses));
        }
    }

    private static class SucceedingExecutor implements TransitionExecutor {
        @Override
        public io.smallrye.mutiny.Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
            for (OrderedStep step : plan.removals()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            for (OrderedStep step : plan.additions()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            return io.smallrye.mutiny.Uni.createFrom().item(new TransitionResult(outcomes));
        }
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=ReconciliationLoopLifecycleTest`
Expected: compilation failure — `start(String, DesiredStateGraph, ReconciliationListener)`,
`compareAndSetDesired()`, `setListener()` not found

- [ ] **Step 3: Create ReconciliationListener in api/**

Create `api/src/main/java/io/casehub/desiredstate/api/ReconciliationListener.java`:

```java
package io.casehub.desiredstate.api;

@FunctionalInterface
public interface ReconciliationListener {
    void onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual);
}
```

- [ ] **Step 4: Add start() overload, setListener(), compareAndSetDesired() to ReconciliationLoop**

Modify `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`:

Add to `ReconciliationLoop`:

```java
public void start(String tenancyId, DesiredStateGraph desired, ReconciliationListener listener) {
    TenantLoop loop = new TenantLoop(tenancyId, desired, listener);
    TenantLoop existing = loops.putIfAbsent(tenancyId, loop);
    if (existing != null) {
        throw new IllegalStateException("Reconciliation loop already running for tenant: " + tenancyId);
    }
    loop.start();
}

public void setListener(String tenancyId, ReconciliationListener listener) {
    TenantLoop loop = loops.get(tenancyId);
    if (loop == null) {
        throw new IllegalStateException("No reconciliation loop running for tenant: " + tenancyId);
    }
    loop.listener = listener;
}

public boolean compareAndSetDesired(String tenancyId,
        DesiredStateGraph expected, DesiredStateGraph newDesired) {
    TenantLoop loop = loops.get(tenancyId);
    if (loop == null) {
        throw new IllegalStateException("No reconciliation loop running for tenant: " + tenancyId);
    }
    return loop.desiredRef.compareAndSet(expected, newDesired);
}
```

Modify `TenantLoop`:
- Add `volatile ReconciliationListener listener` field
- Add constructor accepting listener: `TenantLoop(String tenancyId, DesiredStateGraph desired, ReconciliationListener listener)`
- Existing constructor delegates: `this(tenancyId, desired, null)`

- [ ] **Step 5: Wire listener call into reconcile() and reconcileTypes()**

In `TenantLoop.reconcile()`, add listener call **before** the early return for empty plans (after
`readActual` — this is the critical placement per the design review R3-01). The listener must fire
on every cycle, including when the plan is empty:

```java
private void reconcile() {
    // ... existing tracer/span setup ...
    try (Scope ignored = reconcileSpan.makeCurrent()) {
        DesiredStateGraph desired = desiredRef.get();
        ActualState actual = readActual(desired, tenancyId);

        // Listener fires unconditionally — including empty-plan cycles
        fireListener(desired, actual);

        Set<NodeId> driftedNodes = new HashSet<>();
        desired = detectDrift(desired, actual, driftedNodes);
        // ... rest of reconcile unchanged ...
    }
}
```

Same pattern in `reconcileTypes()` — fire listener after `readActual`, before the empty-plan
early return. Pass the full desired graph from `desiredRef.get()`, not the filtered graph.

Add helper:

```java
private void fireListener(DesiredStateGraph desired, ActualState actual) {
    ReconciliationListener l = listener;
    if (l != null) {
        try {
            l.onReconciliationCycleCompleted(tenancyId, desired, actual);
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                "Reconciliation listener failed for tenant " + tenancyId, e);
        }
    }
}
```

- [ ] **Step 6: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl runtime -Dtest=ReconciliationLoopLifecycleTest`
Expected: all tests PASS

- [ ] **Step 7: Run full test suite — verify no regressions**

Run: `mvn --batch-mode test`
Expected: full build passes

- [ ] **Step 8: Commit**

```
feat(#58): add ReconciliationListener, compareAndSetDesired, listener hook in reconciliation cycle
```

---

### Task 4: LifecycleManager

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/LifecycleManager.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/LifecycleManagerTest.java`

**Interfaces:**
- Consumes: `ReconciliationLoop` (start, stop, updateDesired, compareAndSetDesired, setListener),
  `CompilationResult`, `Phase`, `CompletionCondition`, `ReconciliationListener` (from Tasks 1, 3)
- Produces: `LifecycleManager.start(String, CompilationResult)`,
  `LifecycleManager.stop(String)`,
  `LifecycleManager.updateDesired(String, CompilationResult)` (used by Tasks 5, 7)

- [ ] **Step 1: Write failing tests for LifecycleManager**

Create `runtime/src/test/java/io/casehub/desiredstate/runtime/LifecycleManagerTest.java`:

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class LifecycleManagerTest {

    private ReconciliationLoop loop;
    private LifecycleManager manager;
    private TrackingActualStateAdapter adapter;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        adapter = new TrackingActualStateAdapter();
        factory = new DefaultDesiredStateGraphFactory();
        loop = new ReconciliationLoop(
            new TransitionPlanner(), new ImmediateSuccessExecutor(), adapter,
            new FaultPolicyEngine(List.of()),
            () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofMillis(200));
        manager = new LifecycleManager(loop);
    }

    @AfterEach
    void tearDown() {
        manager.stop("t1");
    }

    @Test
    void singleGraph_startsReconciliationDirectly() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), new TestSpec(), false);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.makePresent(NodeId.of("a"));

        manager.start("t1", CompilationResult.single(graph));

        Thread.sleep(300);
        assertThat(loop.getDesired("t1")).isSameAs(graph);
    }

    @Test
    void lifecycle_transitionsOnCompletion() throws Exception {
        DesiredNode buildNode = new DesiredNode(NodeId.of("build"), new TestSpec(), false);
        DesiredNode defendNode = new DesiredNode(NodeId.of("defend"), new TestSpec(), false);

        DesiredStateGraph buildGraph = ImmutableDesiredStateGraph.empty().withNode(buildNode);
        DesiredStateGraph defendGraph = ImmutableDesiredStateGraph.empty().withNode(defendNode);

        adapter.makePresent(NodeId.of("build"));
        adapter.makePresent(NodeId.of("defend"));

        CompilationResult lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));

        manager.start("t1", lifecycle);

        // Wait for transition — build phase completes (all present), defend phase starts
        Thread.sleep(500);
        DesiredStateGraph current = loop.getDesired("t1");
        assertThat(current.nodes()).containsKey(NodeId.of("defend"));
    }

    @Test
    void lifecycle_staysOnPhaseUntilComplete() throws Exception {
        DesiredNode buildNode = new DesiredNode(NodeId.of("build"), new TestSpec(), false);
        DesiredNode defendNode = new DesiredNode(NodeId.of("defend"), new TestSpec(), false);

        DesiredStateGraph buildGraph = ImmutableDesiredStateGraph.empty().withNode(buildNode);
        DesiredStateGraph defendGraph = ImmutableDesiredStateGraph.empty().withNode(defendNode);

        // build node is ABSENT — phase should not complete
        adapter.makeAbsent(NodeId.of("build"));

        CompilationResult lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));

        manager.start("t1", lifecycle);

        Thread.sleep(300);
        DesiredStateGraph current = loop.getDesired("t1");
        assertThat(current.nodes()).containsKey(NodeId.of("build"));
        assertThat(current.nodes()).doesNotContainKey(NodeId.of("defend"));
    }

    @Test
    void stop_cleansUpLifecycleState() {
        DesiredNode node = new DesiredNode(NodeId.of("a"), new TestSpec(), false);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.makePresent(NodeId.of("a"));

        manager.start("t1", CompilationResult.single(graph));
        manager.stop("t1");

        assertThat(loop.activeTenantCount()).isZero();
    }

    private record TestSpec() implements NodeSpec {}

    private static class TrackingActualStateAdapter implements ActualStateAdapter {
        private final Map<NodeId, NodeStatus> statuses = new HashMap<>();
        void makePresent(NodeId id) { statuses.put(id, NodeStatus.PRESENT); }
        void makeAbsent(NodeId id) { statuses.put(id, NodeStatus.ABSENT); }
        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return new ActualState(Map.copyOf(statuses));
        }
    }

    private static class ImmediateSuccessExecutor implements TransitionExecutor {
        @Override
        public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
            for (OrderedStep step : plan.removals()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            for (OrderedStep step : plan.additions()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            return Uni.createFrom().item(new TransitionResult(outcomes));
        }
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=LifecycleManagerTest`
Expected: compilation failure — `LifecycleManager` not found

- [ ] **Step 3: Implement LifecycleManager**

Create `runtime/src/main/java/io/casehub/desiredstate/runtime/LifecycleManager.java`:

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class LifecycleManager {

    private static final Logger LOG = Logger.getLogger(LifecycleManager.class.getName());

    private final ReconciliationLoop loop;
    private final ConcurrentHashMap<String, TenantLifecycle> lifecycles = new ConcurrentHashMap<>();

    @Inject
    public LifecycleManager(ReconciliationLoop loop) {
        this.loop = loop;
    }

    public void start(String tenancyId, CompilationResult result) {
        ReconciliationListener listener = this::onCycleCompleted;
        switch (result) {
            case CompilationResult.SingleGraph sg ->
                loop.start(tenancyId, sg.graph(), listener);
            case CompilationResult.Lifecycle lc -> {
                lifecycles.put(tenancyId, new TenantLifecycle(lc.phases(), 0));
                loop.start(tenancyId, lc.phases().getFirst().graph(), listener);
            }
        }
    }

    public void stop(String tenancyId) {
        lifecycles.remove(tenancyId);
        loop.stop(tenancyId);
    }

    public void updateDesired(String tenancyId, CompilationResult result) {
        lifecycles.remove(tenancyId);
        switch (result) {
            case CompilationResult.SingleGraph sg ->
                loop.updateDesired(tenancyId, sg.graph());
            case CompilationResult.Lifecycle lc -> {
                lifecycles.put(tenancyId, new TenantLifecycle(lc.phases(), 0));
                loop.updateDesired(tenancyId, lc.phases().getFirst().graph());
                loop.setListener(tenancyId, this::onCycleCompleted);
            }
        }
    }

    private void onCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual) {
        TenantLifecycle current = lifecycles.get(tenancyId);
        if (current == null) {
            return;
        }

        Phase currentPhase = current.currentPhase();
        if (!currentPhase.completionCondition().isComplete(currentPhase.graph(), actual)) {
            return;
        }

        int nextIndex = current.phaseIndex + 1;
        if (nextIndex >= current.phases.size()) {
            lifecycles.remove(tenancyId);
            LOG.info("Lifecycle completed for tenant " + tenancyId
                + " — final phase '" + currentPhase.id() + "' complete");
            return;
        }

        Phase nextPhase = current.phases.get(nextIndex);
        TenantLifecycle next = new TenantLifecycle(current.phases, nextIndex);

        if (!lifecycles.replace(tenancyId, current, next)) {
            return;
        }

        if (!loop.compareAndSetDesired(tenancyId, desired, nextPhase.graph())) {
            lifecycles.replace(tenancyId, next, current);
            return;
        }

        LOG.info("Lifecycle transition for tenant " + tenancyId
            + ": '" + currentPhase.id() + "' → '" + nextPhase.id() + "'");
    }

    private record TenantLifecycle(List<Phase> phases, int phaseIndex) {
        Phase currentPhase() { return phases.get(phaseIndex); }
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl runtime -Dtest=LifecycleManagerTest`
Expected: all tests PASS

- [ ] **Step 5: Run full test suite**

Run: `mvn --batch-mode test`
Expected: full build passes

- [ ] **Step 6: Commit**

```
feat(#58): add LifecycleManager — phase transition orchestration via CAS
```

---

### Task 5: Pipeline PendingApproval Gate (#46)

**Files:**
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/TransformerSpec.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/SinkSpec.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineBlueprint.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineGoalCompiler.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineProvisioner.java`
- Modify: `examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/PipelineTest.java`

**Interfaces:**
- Consumes: `PendingApprovalHandler`, `MockPendingApprovalHandler`, `ProvisionResult.PendingApproval`,
  `ApprovalCheckResult`, `ProvisionContext` (existing API types)
- Produces: `TransformerSpec.approvalRequired()`, `SinkSpec.approvalRequired()`,
  `PipelineBlueprint.Builder.transformer(..., boolean approvalRequired)` (pipeline example only)

- [ ] **Step 1: Write failing tests for PendingApproval gate**

Add to `examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/PipelineTest.java`:

```java
@Test
void goldTierApproval_pendingThenApproved() {
    PipelineBlueprint blueprint = PipelineBlueprint.builder()
        .source("clickstream", "json", "kafka://clicks")
        .schema("click-schema", List.of("userId", "pageUrl", "timestamp"), 1)
        .ingestion("click-ingest", "clickstream", 1000, "json")
        .cleanser("click-clean", List.of("deduplicate"), true, "DROP")
        .enricher("geo-enrich", "geo-lookup", List.of("userId"), List.of("country"))
        .validator("quality-gate", "click-schema", 0.95, true)
        .transformer("session-agg", List.of("sessionize"), List.of("group-by-session"), "parquet", true)
        .sink("warehouse", "s3://analytics/sessions", "parquet", List.of("date"))
        .build();

    DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();
    world.registerLookupSource("geo-lookup", new PipelineWorld.LookupSourceEntry("geo-lookup"));

    MockPendingApprovalHandler approvalHandler = new MockPendingApprovalHandler();
    NodeProvisionerRouter router = new DefaultNodeProvisionerRouter(List.of(provisioner));
    SimpleTransitionExecutor executor = new SimpleTransitionExecutor(
        router, new NoOpHumanNodeHandler(), approvalHandler);

    ActualState empty = new ActualState(Map.of());
    TransitionPlan plan = planner.plan(graph, empty);
    TransitionResult result = executor.execute(plan, "default").await().indefinitely();

    // Transformer should be skipped (pending approval)
    assertThat(result.outcomes().get(NodeId.of("session-agg")))
        .isInstanceOf(StepOutcome.Skipped.class);
    assertThat(approvalHandler.recorded).anyMatch(
        r -> r.nodeId().equals(NodeId.of("session-agg"))
             && r.action() == StepAction.PROVISION);

    // Program approval and re-execute
    PlanApproval approval = new PlanApproval(
        "gold-tier:session-agg", "admin", java.time.Instant.now());
    approvalHandler.programCheck(NodeId.of("session-agg"), StepAction.PROVISION,
        new ApprovalCheckResult.Approved(approval));

    TransitionPlan replan = planner.plan(graph, adapter.readActual(graph, "default"));
    TransitionResult reResult = executor.execute(replan, "default").await().indefinitely();

    assertThat(reResult.outcomes().get(NodeId.of("session-agg")))
        .isInstanceOf(StepOutcome.Succeeded.class);
}

@Test
void goldTierApproval_rejected() {
    PipelineBlueprint blueprint = PipelineBlueprint.builder()
        .source("clickstream", "json", "kafka://clicks")
        .schema("click-schema", List.of("userId", "pageUrl", "timestamp"), 1)
        .ingestion("click-ingest", "clickstream", 1000, "json")
        .cleanser("click-clean", List.of("deduplicate"), true, "DROP")
        .enricher("geo-enrich", "geo-lookup", List.of("userId"), List.of("country"))
        .validator("quality-gate", "click-schema", 0.95, true)
        .transformer("session-agg", List.of("sessionize"), List.of("group-by-session"), "parquet", true)
        .sink("warehouse", "s3://analytics/sessions", "parquet", List.of("date"))
        .build();

    DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();
    world.registerLookupSource("geo-lookup", new PipelineWorld.LookupSourceEntry("geo-lookup"));

    MockPendingApprovalHandler approvalHandler = new MockPendingApprovalHandler();
    // First call records pending, then program Rejected
    approvalHandler.programCheck(NodeId.of("session-agg"), StepAction.PROVISION,
        new ApprovalCheckResult.Rejected("gold-tier:session-agg", "Too expensive"));

    NodeProvisionerRouter router = new DefaultNodeProvisionerRouter(List.of(provisioner));
    SimpleTransitionExecutor executor = new SimpleTransitionExecutor(
        router, new NoOpHumanNodeHandler(), approvalHandler);

    ActualState empty = new ActualState(Map.of());
    TransitionPlan plan = planner.plan(graph, empty);
    TransitionResult result = executor.execute(plan, "default").await().indefinitely();

    assertThat(result.outcomes().get(NodeId.of("session-agg")))
        .isInstanceOf(StepOutcome.Rejected.class);
    assertThat(approvalHandler.acknowledgedRejections).anyMatch(
        r -> r.nodeId().equals(NodeId.of("session-agg")));
}

@Test
void goldTierApproval_defaultOff() {
    PipelineBlueprint blueprint = standardBlueprint(); // no approvalRequired
    DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();
    world.registerLookupSource("geo-lookup", new PipelineWorld.LookupSourceEntry("geo-lookup"));

    NodeProvisionerRouter router = new DefaultNodeProvisionerRouter(List.of(provisioner));
    SimpleTransitionExecutor executor = new SimpleTransitionExecutor(
        router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());
    TransitionResult result = executor.execute(planner.plan(graph, new ActualState(Map.of())),
        "default").await().indefinitely();

    // All nodes should succeed — no approval gate
    assertThat(result.outcomes().get(NodeId.of("session-agg")))
        .isInstanceOf(StepOutcome.Succeeded.class);
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest=PipelineTest#goldTierApproval_pendingThenApproved+goldTierApproval_rejected+goldTierApproval_defaultOff`
Expected: compilation failure — `transformer(... boolean)` overload, `TransformerSpec.approvalRequired()` not found

- [ ] **Step 3: Add approvalRequired to TransformerSpec**

Replace `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/TransformerSpec.java`:

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record TransformerSpec(List<String> aggregations, List<String> reshapeRules,
                              String outputFormat, boolean approvalRequired) implements NodeSpec {
    public TransformerSpec {
        aggregations = List.copyOf(aggregations);
        reshapeRules = List.copyOf(reshapeRules);
    }

    public TransformerSpec(List<String> aggregations, List<String> reshapeRules, String outputFormat) {
        this(aggregations, reshapeRules, outputFormat, false);
    }
}
```

- [ ] **Step 4: Add approvalRequired to SinkSpec**

Replace `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/SinkSpec.java`:

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record SinkSpec(String destination, String format, List<String> partitionKeys,
                       boolean approvalRequired) implements NodeSpec {
    public SinkSpec {
        partitionKeys = List.copyOf(partitionKeys);
    }

    public SinkSpec(String destination, String format, List<String> partitionKeys) {
        this(destination, format, partitionKeys, false);
    }
}
```

- [ ] **Step 5: Add builder overloads to PipelineBlueprint**

Add to `PipelineBlueprint`:

New `TransformerEntry` record:
```java
public record TransformerEntry(String id, List<String> aggregations, List<String> reshapeRules,
                               String outputFormat, boolean approvalRequired) {
    public TransformerEntry {
        aggregations = List.copyOf(aggregations);
        reshapeRules = List.copyOf(reshapeRules);
    }
}
```

New `SinkEntry` record:
```java
public record SinkEntry(String id, String destination, String format, List<String> partitionKeys,
                        boolean approvalRequired) {
    public SinkEntry {
        partitionKeys = List.copyOf(partitionKeys);
    }
}
```

Add builder overloads:
```java
public Builder transformer(String id, List<String> aggregations, List<String> reshapeRules,
                            String outputFormat, boolean approvalRequired) {
    transformers.add(new TransformerEntry(id, aggregations, reshapeRules, outputFormat, approvalRequired));
    return this;
}

public Builder sink(String id, String destination, String format, List<String> partitionKeys,
                    boolean approvalRequired) {
    sinks.add(new SinkEntry(id, destination, format, partitionKeys, approvalRequired));
    return this;
}
```

Existing overloads delegate with `false`.

- [ ] **Step 6: Update PipelineGoalCompiler to pass approvalRequired through**

In `PipelineGoalCompiler.compile()`, update the transformer and sink node creation to use the
`approvalRequired` field from the entry:

```java
// Transformer:
new TransformerSpec(tx.aggregations(), tx.reshapeRules(), tx.outputFormat(), tx.approvalRequired())

// Sink:
new SinkSpec(sink.destination(), sink.format(), sink.partitionKeys(), sink.approvalRequired())
```

- [ ] **Step 7: Add approval gate to PipelineProvisioner**

In `PipelineProvisioner.dispatchToBackend()`, add before the backend dispatch:

```java
if (node.spec() instanceof TransformerSpec ts && ts.approvalRequired() && !context.hasApproval()) {
    return new ProvisionResult.PendingApproval(node.id(), "gold-tier:" + node.id().value());
}
if (node.spec() instanceof SinkSpec ss && ss.approvalRequired() && !context.hasApproval()) {
    return new ProvisionResult.PendingApproval(node.id(), "gold-tier:" + node.id().value());
}
```

- [ ] **Step 8: Fix existing test callers**

Update any existing tests that call `compiler.compile()` to handle `CompilationResult` —
the migration from Task 2 may have left these with compiler errors. Pattern:
```java
DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();
```

- [ ] **Step 9: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl examples/pipeline`
Expected: all tests PASS (new and existing)

- [ ] **Step 10: Commit**

```
feat(#46): pipeline PendingApproval gate — per-stage approval on Gold-tier nodes
```

---

### Task 6: Expansion Example Module Scaffold and Domain Types (#56)

**Files:**
- Create: `examples/expansion/pom.xml`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionGoal.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionNodeTypes.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/DefensePosture.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ProbeSpec.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/NexusSpec.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/PylonSpec.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/CannonSpec.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/PatrolSpec.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/MonitorSpec.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ResponseSpec.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionWorld.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionActualStateAdapter.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionProvisioner.java`
- Modify: `pom.xml` — add `<module>examples/expansion</module>`
- Test: `examples/expansion/src/test/java/io/casehub/desiredstate/example/expansion/ExpansionDomainTest.java`

**Interfaces:**
- Consumes: `DesiredNode`, `NodeType`, `NodeSpec`, `NodeId`, `NodeProvisioner`, `ActualStateAdapter`,
  `ProvisionResult`, `DeprovisionResult`, `ProvisionContext`, `DeprovisionContext`, `ActualState`,
  `NodeStatus` (existing API types)
- Produces: `ExpansionGoal`, `ExpansionNodeTypes`, `DefensePosture`, all spec records,
  `ExpansionWorld`, `ExpansionActualStateAdapter`, `ExpansionProvisioner` (used by Task 7)

- [ ] **Step 1: Create pom.xml**

Create `examples/expansion/pom.xml` following the spatial example pattern (parent
`casehub-desiredstate-parent`, `relativePath=../../pom.xml`, dependencies on
`casehub-desiredstate-api` compile, `casehub-desiredstate` test, `casehub-desiredstate-testing`
test, `casehub-ras-api` compile).

- [ ] **Step 2: Add module to parent pom.xml**

Add `<module>examples/expansion</module>` to the `<modules>` section.

- [ ] **Step 3: Create domain types**

Create `DefensePosture.java`:
```java
package io.casehub.desiredstate.example.expansion;

public enum DefensePosture { PATROL, FORTIFY }
```

Create `ExpansionGoal.java`:
```java
package io.casehub.desiredstate.example.expansion;

import java.util.List;

public record ExpansionGoal(String locationId, List<String> requiredStructures,
                            DefensePosture defensePosture) {
    public ExpansionGoal {
        requiredStructures = List.copyOf(requiredStructures);
    }
    public ExpansionGoal withDefensePosture(DefensePosture posture) {
        return new ExpansionGoal(locationId, requiredStructures, posture);
    }
}
```

Create `ExpansionNodeTypes.java`:
```java
package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.NodeType;

public final class ExpansionNodeTypes {
    public static final NodeType PROBE = new NodeType("probe");
    public static final NodeType NEXUS = new NodeType("nexus");
    public static final NodeType PYLON = new NodeType("pylon");
    public static final NodeType CANNON = new NodeType("cannon");
    public static final NodeType PATROL = new NodeType("patrol");
    public static final NodeType MONITOR = new NodeType("monitor");
    public static final NodeType RESPONSE = new NodeType("response");
    private ExpansionNodeTypes() {}
}
```

Create spec records (`ProbeSpec`, `NexusSpec`, `PylonSpec`, `CannonSpec`, `PatrolSpec`,
`MonitorSpec`, `ResponseSpec`) — each implements `NodeSpec` with a `locationId` field.
`ResponseSpec` also has a `DefensePosture posture` field.

- [ ] **Step 4: Create ExpansionWorld simulation**

Create `ExpansionWorld.java` — in-memory state tracker (same pattern as `PipelineWorld`):
- `Map<NodeId, StructureState>` for tracking build/defense structures
- `enum StructureState { BUILDING, BUILT, DESTROYED, PATROLLING, MONITORING, RESPONDING }`
- Methods: `build(NodeId)`, `complete(NodeId)`, `destroy(NodeId)`, `state(NodeId)`, `isBuilt(NodeId)`

- [ ] **Step 5: Create ExpansionActualStateAdapter**

Create `ExpansionActualStateAdapter.java` — reads from `ExpansionWorld`:
- BUILT/PATROLLING/MONITORING/RESPONDING → `NodeStatus.PRESENT`
- DESTROYED → `NodeStatus.ABSENT`
- BUILDING → `NodeStatus.ABSENT` (not yet complete)
- null (unknown) → `NodeStatus.ABSENT`

- [ ] **Step 6: Create ExpansionProvisioner**

Create `ExpansionProvisioner.java`:
- `handledTypes()` returns all 7 node types
- `provision()`: calls `world.build(nodeId)` then `world.complete(nodeId)` for build-phase types;
  sets appropriate state for defense types
- `deprovision()`: calls `world.destroy(nodeId)`

- [ ] **Step 7: Write tests for domain types**

Create `ExpansionDomainTest.java` — tests provisioner, adapter, world:
- Provision probe → world shows BUILT, adapter returns PRESENT
- Deprovision nexus → world shows DESTROYED, adapter returns ABSENT
- All 7 node types handled by provisioner

- [ ] **Step 8: Run tests**

Run: `mvn --batch-mode test -pl examples/expansion`
Expected: all tests PASS

- [ ] **Step 9: Commit**

```
feat(#56): expansion example scaffold — domain types, world, provisioner, adapter
```

---

### Task 7: ExpansionGoalCompiler, Lifecycle Tests, and Replan (#56)

**Files:**
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionGoalCompiler.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionFaultPolicy.java`
- Create: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionSituationRecompiler.java`
- Test: `examples/expansion/src/test/java/io/casehub/desiredstate/example/expansion/ExpansionLifecycleTest.java`

**Interfaces:**
- Consumes: `GoalCompiler<ExpansionGoal>`, `CompilationResult`, `Phase`, `CompletionCondition`,
  `LifecycleManager`, `ReconciliationLoop`, `FaultPolicy`, `SituationRecompiler`,
  `DesiredStateGraphFactory`, `TransitionPlanner` (from Tasks 1-4, 6)
- Produces: End-to-end lifecycle demonstration (terminal — no downstream consumers)

- [ ] **Step 1: Write failing tests for full lifecycle**

Create `examples/expansion/src/test/java/io/casehub/desiredstate/example/expansion/ExpansionLifecycleTest.java`:

```java
package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.*;
import io.casehub.ras.api.ActiveSituation;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ExpansionLifecycleTest {

    private DesiredStateGraphFactory factory;
    private ExpansionGoalCompiler compiler;
    private ExpansionWorld world;
    private ExpansionProvisioner provisioner;
    private ExpansionActualStateAdapter adapter;
    private ReconciliationLoop loop;
    private LifecycleManager manager;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        compiler = new ExpansionGoalCompiler();
        world = new ExpansionWorld();
        provisioner = new ExpansionProvisioner(world);
        adapter = new ExpansionActualStateAdapter(world);

        DefaultNodeProvisionerRouter router = new DefaultNodeProvisionerRouter(List.of(provisioner));
        SimpleTransitionExecutor executor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());

        loop = new ReconciliationLoop(
            new TransitionPlanner(), executor, adapter,
            new FaultPolicyEngine(List.of()),
            () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofMillis(200));
        manager = new LifecycleManager(loop);
    }

    @AfterEach
    void tearDown() {
        manager.stop("t1");
    }

    @Test
    void buildPhase_completesAndTransitionsToDefend() throws Exception {
        ExpansionGoal goal = new ExpansionGoal("loc-1",
            List.of("nexus", "pylon", "cannon"), DefensePosture.PATROL);

        CompilationResult result = compiler.compile(goal, factory);
        assertThat(result).isInstanceOf(CompilationResult.Lifecycle.class);

        CompilationResult.Lifecycle lifecycle = (CompilationResult.Lifecycle) result;
        assertThat(lifecycle.phases()).hasSize(2);
        assertThat(lifecycle.phases().get(0).id()).isEqualTo("build");
        assertThat(lifecycle.phases().get(1).id()).isEqualTo("defend");

        manager.start("t1", result);

        // Wait for build phase to complete and defend phase to start
        Thread.sleep(1000);

        DesiredStateGraph current = loop.getDesired("t1");
        // Defend phase should be active — contains defense node types
        boolean hasDefenseNodes = current.nodes().values().stream()
            .anyMatch(n -> n.type().equals(ExpansionNodeTypes.PATROL)
                        || n.type().equals(ExpansionNodeTypes.MONITOR)
                        || n.type().equals(ExpansionNodeTypes.RESPONSE));
        assertThat(hasDefenseNodes).isTrue();
    }

    @Test
    void defendPhase_reconcilesContinuously() throws Exception {
        ExpansionGoal goal = new ExpansionGoal("loc-1",
            List.of("nexus"), DefensePosture.PATROL);

        manager.start("t1", compiler.compile(goal, factory));
        Thread.sleep(500);

        // Destroy a defense node — reconciliation should re-provision
        NodeId patrolId = loop.getDesired("t1").nodes().keySet().stream()
            .filter(id -> loop.getDesired("t1").nodes().get(id).type().equals(ExpansionNodeTypes.PATROL))
            .findFirst().orElseThrow();
        world.destroy(patrolId);

        Thread.sleep(500);
        assertThat(world.isBuilt(patrolId)).isTrue();
    }

    @Test
    void carryForward_defendPhaseReconcilesBuildArtifacts() throws Exception {
        ExpansionGoal goal = new ExpansionGoal("loc-1",
            List.of("nexus"), DefensePosture.PATROL);

        manager.start("t1", compiler.compile(goal, factory));
        Thread.sleep(500);

        // Verify nexus is in defend phase graph (carry-forward)
        DesiredStateGraph current = loop.getDesired("t1");
        boolean hasNexus = current.nodes().values().stream()
            .anyMatch(n -> n.type().equals(ExpansionNodeTypes.NEXUS));
        assertThat(hasNexus).isTrue();

        // Destroy nexus — should be re-provisioned by defend phase reconciliation
        NodeId nexusId = current.nodes().keySet().stream()
            .filter(id -> current.nodes().get(id).type().equals(ExpansionNodeTypes.NEXUS))
            .findFirst().orElseThrow();
        world.destroy(nexusId);

        Thread.sleep(500);
        assertThat(world.isBuilt(nexusId)).isTrue();
    }

    @Test
    void faultTriggeredReplan_producesNewGraph() {
        ExpansionGoal goal = new ExpansionGoal("loc-1",
            List.of("nexus", "pylon"), DefensePosture.PATROL);

        ExpansionSituationRecompiler recompiler = new ExpansionSituationRecompiler(compiler, goal);

        // Build the initial graph
        CompilationResult initial = compiler.compile(goal, factory);
        DesiredStateGraph buildGraph = ((CompilationResult.Lifecycle) initial).phases().get(0).graph();

        // Simulate a situation — persistent nexus failure
        ActiveSituation situation = new ActiveSituation(
            "nexus-failure", "loc-1", "t1", 0.95,
            Map.of("failedNode", "nexus"), Instant.now(), Instant.now(), 5);

        Optional<CompilationResult> replanned = recompiler.recompile(
            buildGraph, situation, factory);

        assertThat(replanned).isPresent();
        // Replanned result should have FORTIFY defense posture
        CompilationResult.Lifecycle replanLifecycle = (CompilationResult.Lifecycle) replanned.get();
        assertThat(replanLifecycle.phases()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void singlePhaseBackwardCompat() throws Exception {
        // Compile a single-phase result (non-lifecycle)
        DesiredNode node = new DesiredNode(NodeId.of("standalone"), new NexusSpec("loc-1"), false);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        manager.start("t1", CompilationResult.single(graph));
        Thread.sleep(300);

        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("standalone"));
        assertThat(world.isBuilt(NodeId.of("standalone"))).isTrue();
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl examples/expansion -Dtest=ExpansionLifecycleTest`
Expected: compilation failure — `ExpansionGoalCompiler`, `ExpansionFaultPolicy`,
`ExpansionSituationRecompiler` not found

- [ ] **Step 3: Implement ExpansionGoalCompiler**

Create `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionGoalCompiler.java`:

```java
package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import java.util.ArrayList;
import java.util.List;

public class ExpansionGoalCompiler implements GoalCompiler<ExpansionGoal> {

    @Override
    public CompilationResult compile(ExpansionGoal goals, DesiredStateGraphFactory factory) {
        DesiredStateGraph buildGraph = compileBuildPhase(goals, factory);
        DesiredStateGraph defendGraph = compileDefendPhase(goals, factory);

        return CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));
    }

    private DesiredStateGraph compileBuildPhase(ExpansionGoal goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        // Probe is always first
        NodeId probeId = NodeId.of("probe-" + goals.locationId());
        nodes.add(new DesiredNode(probeId, new ProbeSpec(goals.locationId()), false));

        NodeId prevId = probeId;
        for (String structure : goals.requiredStructures()) {
            NodeId nodeId = NodeId.of(structure + "-" + goals.locationId());
            NodeType type = resolveType(structure);
            NodeSpec spec = resolveSpec(structure, goals.locationId());
            nodes.add(new DesiredNode(nodeId, spec, false));
            deps.add(new Dependency(nodeId, prevId));
            prevId = nodeId;
        }

        return factory.of(nodes, deps);
    }

    private DesiredStateGraph compileDefendPhase(ExpansionGoal goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        // Carry forward nexus — it needs continuous reconciliation
        NodeId nexusId = NodeId.of("nexus-" + goals.locationId());
        nodes.add(new DesiredNode(nexusId, new NexusSpec(goals.locationId()), false));

        // Defense nodes
        NodeId patrolId = NodeId.of("patrol-" + goals.locationId());
        NodeId monitorId = NodeId.of("monitor-" + goals.locationId());
        NodeId responseId = NodeId.of("response-" + goals.locationId());

        nodes.add(new DesiredNode(patrolId, new PatrolSpec(goals.locationId()), false));
        nodes.add(new DesiredNode(monitorId, new MonitorSpec(goals.locationId()), false));
        nodes.add(new DesiredNode(responseId, new ResponseSpec(goals.locationId(), goals.defensePosture()), false));

        // patrol and monitor depend on nexus
        deps.add(new Dependency(patrolId, nexusId));
        deps.add(new Dependency(monitorId, nexusId));
        // response depends on patrol and monitor
        deps.add(new Dependency(responseId, patrolId));
        deps.add(new Dependency(responseId, monitorId));

        return factory.of(nodes, deps);
    }

    private NodeType resolveType(String structure) {
        return switch (structure) {
            case "nexus" -> ExpansionNodeTypes.NEXUS;
            case "pylon" -> ExpansionNodeTypes.PYLON;
            case "cannon" -> ExpansionNodeTypes.CANNON;
            default -> throw new IllegalArgumentException("Unknown structure: " + structure);
        };
    }

    private NodeSpec resolveSpec(String structure, String locationId) {
        return switch (structure) {
            case "nexus" -> new NexusSpec(locationId);
            case "pylon" -> new PylonSpec(locationId);
            case "cannon" -> new CannonSpec(locationId);
            default -> throw new IllegalArgumentException("Unknown structure: " + structure);
        };
    }
}
```

- [ ] **Step 4: Implement ExpansionFaultPolicy**

Create `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionFaultPolicy.java`:

```java
package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import java.util.List;

public class ExpansionFaultPolicy implements FaultPolicy {
    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        // Simple retry — no graph mutations. Persistent faults escalate via RAS.
        return List.of();
    }
}
```

- [ ] **Step 5: Implement ExpansionSituationRecompiler**

Create `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionSituationRecompiler.java`:

```java
package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.ActiveSituation;
import java.util.Optional;

public class ExpansionSituationRecompiler implements SituationRecompiler {

    private final ExpansionGoalCompiler compiler;
    private final ExpansionGoal originalGoal;

    public ExpansionSituationRecompiler(ExpansionGoalCompiler compiler, ExpansionGoal originalGoal) {
        this.compiler = compiler;
        this.originalGoal = originalGoal;
    }

    @Override
    public Optional<CompilationResult> recompile(
            DesiredStateGraph current, ActiveSituation situation,
            DesiredStateGraphFactory factory) {
        ExpansionGoal revised = originalGoal.withDefensePosture(DefensePosture.FORTIFY);
        return Optional.of(compiler.compile(revised, factory));
    }
}
```

- [ ] **Step 6: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl examples/expansion`
Expected: all tests PASS

- [ ] **Step 7: Run full build**

Run: `mvn --batch-mode test`
Expected: full build passes — all modules green

- [ ] **Step 8: Commit**

```
feat(#56): expansion example — planner-backed GoalCompiler with lifecycle transitions
```

---

### Task 8: Update DesiredStateReplanDispatch to Use LifecycleManager

**Files:**
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatch.java`
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatchTest.java` (if exists)

**Interfaces:**
- Consumes: `LifecycleManager`, `CompilationResult` (from Tasks 1, 4)
- Produces: Updated `DesiredStateReplanDispatch` that routes through `LifecycleManager`

- [ ] **Step 1: Update DesiredStateReplanDispatch**

Replace `reconciliationLoop` injection with `lifecycleManager`:

```java
private final LifecycleManager lifecycleManager;
private final ReconciliationLoop reconciliationLoop; // keep for getDesired()
```

In `replan()`, change the updateDesired call:

```java
if (newResult.isPresent()) {
    lifecycleManager.updateDesired(tenancyId, newResult.get());
    result.put("status", "REPLANNED");
}
```

Remove the Task 2 interim pattern-matching extraction — `LifecycleManager.updateDesired()`
handles `CompilationResult` directly.

- [ ] **Step 2: Run tests**

Run: `mvn --batch-mode test -pl engine-adapter`
Expected: all tests PASS

- [ ] **Step 3: Run full build**

Run: `mvn --batch-mode test`
Expected: full build passes

- [ ] **Step 4: Commit**

```
feat(#58): route DesiredStateReplanDispatch through LifecycleManager
```

---

### Task 9: CLAUDE.md and ARC42STORIES.MD Updates

**Files:**
- Modify: `CLAUDE.md` — add LifecycleManager, CompilationResult, expansion module to docs
- Modify: `ARC42STORIES.MD` — update relevant sections

**Interfaces:**
- Consumes: all prior tasks
- Produces: updated project documentation

- [ ] **Step 1: Update CLAUDE.md**

Add to Module Structure table: `examples/expansion/` entry.

Add to Core SPIs table: `CompletionCondition` entry.

Add to Core Runtime Types table: `CompilationResult`, `Phase`, `LifecycleManager`,
`ReconciliationListener` entries.

Update GoalCompiler SPI signature in table: return type is `CompilationResult`.
Update SituationRecompiler SPI signature: return type is `Optional<CompilationResult>`.

- [ ] **Step 2: Update ARC42STORIES.MD**

Update relevant sections to reflect lifecycle transitions, CompilationResult, LifecycleManager.

- [ ] **Step 3: Run full build — final verification**

Run: `mvn --batch-mode test`
Expected: full build passes — all modules green

- [ ] **Step 4: Commit**

```
docs(#46,#58,#56): update CLAUDE.md and ARC42STORIES.MD for lifecycle and expansion
```
