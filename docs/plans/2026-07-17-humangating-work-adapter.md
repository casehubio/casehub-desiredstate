# HumanGating Per-Action Flags Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #72 — evaluate work-adapter boundary
**Issue group:** #72, #79, #80

**Goal:** Replace `boolean requiresHuman` with `HumanGating` enum for per-action human gating, remove `WorkItemHumanNodeHandler`, add CTE case cancellation, and update the pipeline example to demonstrate asymmetric gating.

**Architecture:** `HumanGating` is a four-state enum (NONE, PROVISION_ONLY, DEPROVISION_ONLY, ALL) that replaces the single boolean on `DesiredNode` and `NodeSpec`. STE and CTE route per-action using `requiresHuman(StepAction)`. `GraphMutation.UpdateNode` carries the full adapted `DesiredNode` instead of just `NodeSpec`. CTE cancels previous active cases before starting new ones.

**Tech Stack:** Java 22, Quarkus, Mutiny, JUnit 5, AssertJ

## Global Constraints

- Pre-release platform — breaking changes cost nothing
- IntelliJ MCP mandatory for all .java edits
- No MockHumanNodeHandler exists in the testing module (CLAUDE.md is stale)
- `casehub-ops` cross-repo constructor migration tracked in issue #82

---

### Task 1: HumanGating Enum + DesiredNode + NodeSpec

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/HumanGating.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/NodeSpec.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/HumanNodeHandler.java`
- Modify: `api/src/test/java/io/casehub/desiredstate/api/CoreTypesTest.java`

**Interfaces:**
- Produces: `HumanGating` enum with `requiresHuman(StepAction)`, `any()`, `merge(HumanGating)`
- Produces: `DesiredNode(NodeId, NodeType, NodeSpec, HumanGating)` with `requiresHuman(StepAction)` and `requiresHuman()`
- Produces: `NodeSpec.humanGating()` default method returning `HumanGating.NONE`

- [ ] **Step 1: Write HumanGating enum tests**

Create test file `api/src/test/java/io/casehub/desiredstate/api/HumanGatingTest.java`:

```java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class HumanGatingTest {

    @Test void none_requiresHuman_bothFalse() {
        assertThat(HumanGating.NONE.requiresHuman(StepAction.PROVISION)).isFalse();
        assertThat(HumanGating.NONE.requiresHuman(StepAction.DEPROVISION)).isFalse();
    }

    @Test void none_any_false() {
        assertThat(HumanGating.NONE.any()).isFalse();
    }

    @Test void provisionOnly_requiresHuman_provisionTrue_deprovisionFalse() {
        assertThat(HumanGating.PROVISION_ONLY.requiresHuman(StepAction.PROVISION)).isTrue();
        assertThat(HumanGating.PROVISION_ONLY.requiresHuman(StepAction.DEPROVISION)).isFalse();
    }

    @Test void provisionOnly_any_true() {
        assertThat(HumanGating.PROVISION_ONLY.any()).isTrue();
    }

    @Test void deprovisionOnly_requiresHuman_provisionFalse_deprovisionTrue() {
        assertThat(HumanGating.DEPROVISION_ONLY.requiresHuman(StepAction.PROVISION)).isFalse();
        assertThat(HumanGating.DEPROVISION_ONLY.requiresHuman(StepAction.DEPROVISION)).isTrue();
    }

    @Test void deprovisionOnly_any_true() {
        assertThat(HumanGating.DEPROVISION_ONLY.any()).isTrue();
    }

    @Test void all_requiresHuman_bothTrue() {
        assertThat(HumanGating.ALL.requiresHuman(StepAction.PROVISION)).isTrue();
        assertThat(HumanGating.ALL.requiresHuman(StepAction.DEPROVISION)).isTrue();
    }

    @Test void all_any_true() {
        assertThat(HumanGating.ALL.any()).isTrue();
    }

    @Test void merge_none_none_isNone() {
        assertThat(HumanGating.NONE.merge(HumanGating.NONE)).isEqualTo(HumanGating.NONE);
    }

    @Test void merge_none_all_isAll() {
        assertThat(HumanGating.NONE.merge(HumanGating.ALL)).isEqualTo(HumanGating.ALL);
    }

    @Test void merge_all_none_isAll() {
        assertThat(HumanGating.ALL.merge(HumanGating.NONE)).isEqualTo(HumanGating.ALL);
    }

    @Test void merge_provisionOnly_deprovisionOnly_isAll() {
        assertThat(HumanGating.PROVISION_ONLY.merge(HumanGating.DEPROVISION_ONLY)).isEqualTo(HumanGating.ALL);
    }

    @Test void merge_deprovisionOnly_provisionOnly_isAll() {
        assertThat(HumanGating.DEPROVISION_ONLY.merge(HumanGating.PROVISION_ONLY)).isEqualTo(HumanGating.ALL);
    }

    @Test void merge_provisionOnly_provisionOnly_isProvisionOnly() {
        assertThat(HumanGating.PROVISION_ONLY.merge(HumanGating.PROVISION_ONLY)).isEqualTo(HumanGating.PROVISION_ONLY);
    }

    @Test void merge_deprovisionOnly_deprovisionOnly_isDeprovisionOnly() {
        assertThat(HumanGating.DEPROVISION_ONLY.merge(HumanGating.DEPROVISION_ONLY)).isEqualTo(HumanGating.DEPROVISION_ONLY);
    }

    @Test void merge_provisionOnly_none_isProvisionOnly() {
        assertThat(HumanGating.PROVISION_ONLY.merge(HumanGating.NONE)).isEqualTo(HumanGating.PROVISION_ONLY);
    }

    @Test void merge_none_deprovisionOnly_isDeprovisionOnly() {
        assertThat(HumanGating.NONE.merge(HumanGating.DEPROVISION_ONLY)).isEqualTo(HumanGating.DEPROVISION_ONLY);
    }

    @ParameterizedTest @EnumSource(HumanGating.class)
    void merge_all_withAnything_isAll(HumanGating other) {
        assertThat(HumanGating.ALL.merge(other)).isEqualTo(HumanGating.ALL);
    }

    @ParameterizedTest @EnumSource(HumanGating.class)
    void merge_anything_withAll_isAll(HumanGating other) {
        assertThat(other.merge(HumanGating.ALL)).isEqualTo(HumanGating.ALL);
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=HumanGatingTest`
Expected: compilation failure — `HumanGating` does not exist

- [ ] **Step 3: Create HumanGating enum**

Use `ide_create_file` to create `api/src/main/java/io/casehub/desiredstate/api/HumanGating.java`:

```java
package io.casehub.desiredstate.api;

public enum HumanGating {
    NONE,
    PROVISION_ONLY,
    DEPROVISION_ONLY,
    ALL;

    public boolean requiresHuman(StepAction action) {
        return switch (action) {
            case PROVISION -> this == PROVISION_ONLY || this == ALL;
            case DEPROVISION -> this == DEPROVISION_ONLY || this == ALL;
        };
    }

    public boolean any() {
        return this != NONE;
    }

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

- [ ] **Step 4: Run HumanGating tests — verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=HumanGatingTest`
Expected: all tests PASS

- [ ] **Step 5: Update DesiredNode, NodeSpec, HumanNodeHandler, and CoreTypesTest**

Use `ide_edit_member` to replace `DesiredNode` record declaration:
```java
public record DesiredNode(NodeId id, NodeType type, NodeSpec spec, HumanGating humanGating) {

    public DesiredNode {
        Objects.requireNonNull(id, "DesiredNode id must not be null");
        Objects.requireNonNull(type, "DesiredNode type must not be null");
        Objects.requireNonNull(spec, "DesiredNode spec must not be null");
        Objects.requireNonNull(humanGating, "DesiredNode humanGating must not be null");
    }

    public boolean requiresHuman(StepAction action) {
        return humanGating.requiresHuman(action) || spec.humanGating().requiresHuman(action);
    }

    public boolean requiresHuman() {
        return humanGating.any() || spec.humanGating().any();
    }
}
```

Use `ide_edit_member` to replace `NodeSpec.requiresHuman()` with:
```java
default HumanGating humanGating() { return HumanGating.NONE; }
```

Use `ide_edit_member` to update `HumanNodeHandler` javadoc and `onDeprovision` default:
```java
default StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
    return new StepOutcome.Skipped("no HumanNodeHandler configured for deprovision");
}
```

Update `CoreTypesTest` — replace all `new DesiredNode(..., false)` with `HumanGating.NONE`, `true` with `HumanGating.ALL`. Replace `spec.requiresHuman()` assertions with `spec.humanGating()`. Add new tests for per-action merge semantics:

```java
@Test void requiresHuman_perAction_nodeProvisionOnly_specNone() {
    NodeSpec spec = new NodeSpec() {};
    DesiredNode node = new DesiredNode(NodeId.of("n"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);
    assertThat(node.requiresHuman(StepAction.PROVISION)).isTrue();
    assertThat(node.requiresHuman(StepAction.DEPROVISION)).isFalse();
    assertThat(node.requiresHuman()).isTrue();
}

@Test void requiresHuman_perAction_nodeNone_specDeprovisionOnly() {
    NodeSpec spec = new NodeSpec() {
        @Override public HumanGating humanGating() { return HumanGating.DEPROVISION_ONLY; }
    };
    DesiredNode node = new DesiredNode(NodeId.of("n"), NodeType.of("t"), spec, HumanGating.NONE);
    assertThat(node.requiresHuman(StepAction.PROVISION)).isFalse();
    assertThat(node.requiresHuman(StepAction.DEPROVISION)).isTrue();
}

@Test void requiresHuman_perAction_merge_nodeProvisionOnly_specDeprovisionOnly() {
    NodeSpec spec = new NodeSpec() {
        @Override public HumanGating humanGating() { return HumanGating.DEPROVISION_ONLY; }
    };
    DesiredNode node = new DesiredNode(NodeId.of("n"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);
    assertThat(node.requiresHuman(StepAction.PROVISION)).isTrue();
    assertThat(node.requiresHuman(StepAction.DEPROVISION)).isTrue();
    assertThat(node.requiresHuman()).isTrue();
}

@Test void humanGating_nullRejected() {
    assertThatThrownBy(() -> new DesiredNode(NodeId.of("n"), NodeType.of("t"), new NodeSpec() {}, null))
        .isInstanceOf(NullPointerException.class);
}
```

- [ ] **Step 6: Fix compilation across all modules**

The `DesiredNode` constructor change breaks every call site. Run `ide_find_references` for `DesiredNode` constructor and update mechanically:
- `false` → `HumanGating.NONE`
- `true` → `HumanGating.ALL`
- `existing.requiresHuman()` → `existing.humanGating()` (in `ImmutableDesiredStateGraph.withMutation`)

Key files: `PipelineGoalCompiler`, `DungeonGoalCompiler`, `DungeonBlueprint`, `CreatureSpec`, `ImmutableDesiredStateGraph`, all test files constructing `DesiredNode`.

Use `ide_build_project` to verify compilation. Fix any remaining errors.

- [ ] **Step 7: Run full test suite — verify all pass**

Run: `mvn --batch-mode test`
Expected: all tests PASS (existing behavior preserved — `HumanGating.ALL` is equivalent to `true`, `NONE` to `false`)

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add api/src/main/java/io/casehub/desiredstate/api/HumanGating.java api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java api/src/main/java/io/casehub/desiredstate/api/NodeSpec.java api/src/main/java/io/casehub/desiredstate/api/HumanNodeHandler.java api/src/test/java/io/casehub/desiredstate/api/HumanGatingTest.java api/src/test/java/io/casehub/desiredstate/api/CoreTypesTest.java
git -C /Users/mdproctor/claude/casehub/desiredstate add -u
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#79): HumanGating enum — replace boolean requiresHuman with per-action gating"
```

---

### Task 2: GraphDiff + UpdateNode — Full Node Equality

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/GraphMutation.java` (line 8)
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/GraphDiff.java` (line 38)
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ImmutableDesiredStateGraph.java` (line 202-217)
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/GraphDiffTest.java`

**Interfaces:**
- Consumes: `DesiredNode` record with `HumanGating humanGating` from Task 1
- Produces: `GraphMutation.UpdateNode(NodeId id, DesiredNode adaptedNode)` — carries full adapted node

- [ ] **Step 1: Write failing tests for GraphDiff humanGating detection**

Add to `GraphDiffTest`:

```java
@Test void humanGating_change_only_generates_updateNode() {
    DesiredNode current = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.NONE);
    DesiredNode adapted = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.DEPROVISION_ONLY);

    DesiredStateGraph currentGraph = factory.of(List.of(current), List.of());
    DesiredStateGraph adaptedGraph = factory.of(List.of(adapted), List.of());

    List<GraphMutation> mutations = GraphDiff.computeMutations(currentGraph, adaptedGraph);

    assertThat(mutations).hasSize(1);
    assertThat(mutations.get(0)).isInstanceOf(GraphMutation.UpdateNode.class);
    GraphMutation.UpdateNode update = (GraphMutation.UpdateNode) mutations.get(0);
    assertThat(update.id()).isEqualTo(NodeId.of("n1"));
    assertThat(update.adaptedNode().humanGating()).isEqualTo(HumanGating.DEPROVISION_ONLY);
}

@Test void updateNode_carries_adapted_humanGating_not_merged() {
    NodeSpec specWithGating = new NodeSpec() {
        @Override public HumanGating humanGating() { return HumanGating.PROVISION_ONLY; }
    };
    DesiredNode current = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), specWithGating, HumanGating.NONE);
    DesiredNode adapted = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), specWithGating, HumanGating.DEPROVISION_ONLY);

    DesiredStateGraph currentGraph = factory.of(List.of(current), List.of());
    DesiredStateGraph adaptedGraph = factory.of(List.of(adapted), List.of());

    List<GraphMutation> mutations = GraphDiff.computeMutations(currentGraph, adaptedGraph);

    assertThat(mutations).hasSize(1);
    GraphMutation.UpdateNode update = (GraphMutation.UpdateNode) mutations.get(0);
    // Must carry the adapted node's humanGating (DEPROVISION_ONLY), not the merged value (ALL)
    assertThat(update.adaptedNode().humanGating()).isEqualTo(HumanGating.DEPROVISION_ONLY);
}

@Test void equal_nodes_generate_no_mutation() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);

    DesiredStateGraph currentGraph = factory.of(List.of(node), List.of());
    DesiredStateGraph adaptedGraph = factory.of(List.of(node), List.of());

    List<GraphMutation> mutations = GraphDiff.computeMutations(currentGraph, adaptedGraph);

    assertThat(mutations).isEmpty();
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=GraphDiffTest`
Expected: FAIL — `humanGating_change_only` produces empty mutations (current code only compares spec)

- [ ] **Step 3: Update GraphMutation.UpdateNode**

Use `ide_edit_member` on `GraphMutation` to replace `UpdateNode`:
```java
record UpdateNode(NodeId id, DesiredNode adaptedNode) implements GraphMutation {}
```

- [ ] **Step 4: Update GraphDiff.computeMutations**

Use `ide_replace_member` on `GraphDiff.computeMutations` to change the comparison from `!Objects.equals(currentNode.spec(), adaptedNode.spec())` to `!currentNode.equals(adaptedNode)`, and the mutation from `new GraphMutation.UpdateNode(id, adaptedNode.spec())` to `new GraphMutation.UpdateNode(id, adaptedNode)`.

Also update `targetNodeId` switch case:
```java
case GraphMutation.UpdateNode update -> update.id();
```

- [ ] **Step 5: Update ImmutableDesiredStateGraph.withMutation**

Use `ide_replace_member` on `ImmutableDesiredStateGraph.withMutation` to replace the `UpdateNode` case:
```java
case GraphMutation.UpdateNode m -> withNode(m.adaptedNode());
```

- [ ] **Step 6: Fix compilation — update all UpdateNode constructor call sites**

Run `ide_find_references` for `GraphMutation.UpdateNode` to find all call sites. The `CbrFaultPolicy` and any other callers constructing `UpdateNode(id, newSpec)` must now construct with the full adapted `DesiredNode`. Use `ide_diagnostics` to find remaining errors.

- [ ] **Step 7: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl runtime -Dtest=GraphDiffTest`
Expected: all PASS

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add -u
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "fix(#79): GraphDiff compares full node equality — UpdateNode carries adapted DesiredNode"
```

---

### Task 3: STE Per-Action Routing + Span Attributes

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java`

**Interfaces:**
- Consumes: `DesiredNode.requiresHuman(StepAction)` from Task 1

- [ ] **Step 1: Write failing tests for per-action routing**

Add to `SimpleTransitionExecutorTest`:

```java
@Test void provisionOnly_provision_delegatesToHandler_deprovision_toProvisioner() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);
    // Build graph and plan with this node in both additions and removals...
    // Verify: provision → handler called, provisioner NOT called
    // Verify: deprovision → provisioner called, handler NOT called
}

@Test void deprovisionOnly_provision_toProvisioner_deprovision_delegatesToHandler() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.DEPROVISION_ONLY);
    // Verify: provision → provisioner called, handler NOT called
    // Verify: deprovision → handler called, provisioner NOT called
}

@Test void deprovisionOnly_approvalCheck_runsForProvision_skippedForDeprovision() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.DEPROVISION_ONLY);
    // Verify: provision action runs through PendingApprovalHandler.check()
    // Verify: deprovision action skips approval check (goes to handler)
}
```

Follow the existing test patterns in `SimpleTransitionExecutorTest` — construct a `TransitionPlan`, call `execute()`, verify outcomes and mock interactions.

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=SimpleTransitionExecutorTest`
Expected: FAIL — new tests fail because STE uses blanket `requiresHuman()` check

- [ ] **Step 3: Update executeProvision and executeDeprovision**

Use `ide_replace_member` on `SimpleTransitionExecutor.executeProvision`:
- Change `if (node.requiresHuman())` to `if (node.requiresHuman(StepAction.PROVISION))`
- Replace span attribute: `.setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman())` with:
  ```java
  .setAttribute(AttributeKey.stringKey("desiredstate.human.gating"), node.humanGating().name())
  .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman(StepAction.PROVISION))
  ```

Use `ide_replace_member` on `SimpleTransitionExecutor.executeDeprovision`:
- Change `if (node.requiresHuman())` to `if (node.requiresHuman(StepAction.DEPROVISION))`
- Replace span attribute analogously with `StepAction.DEPROVISION`

- [ ] **Step 4: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl runtime -Dtest=SimpleTransitionExecutorTest`
Expected: all PASS (existing tests still pass — `HumanGating.ALL` behaves like old `true`)

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add -u
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#79): STE per-action routing — requiresHuman(StepAction) replaces blanket check"
```

---

### Task 4: CTE Per-Action Separation + Case Cancellation

**Files:**
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/CaseTransitionExecutor.java`
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateExecutionRegistry.java`
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/CaseTransitionExecutorTest.java`
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateExecutionRegistryTest.java`

**Interfaces:**
- Consumes: `DesiredNode.requiresHuman(StepAction)` from Task 1
- Produces: `DesiredStateExecutionRegistry.getActiveCaseId(String tenancyId)`, `setActiveCaseId(String tenancyId, UUID caseId)`, `clearActiveCaseId(String tenancyId)`

- [ ] **Step 1: Write failing tests for per-action separation and case cancellation**

Add to `DesiredStateExecutionRegistryTest`:
```java
@Test void activeCaseId_notSet_returnsEmpty() {
    assertThat(registry.getActiveCaseId("tenant-1")).isEmpty();
}

@Test void activeCaseId_setAndGet() {
    UUID id = UUID.randomUUID();
    registry.setActiveCaseId("tenant-1", id);
    assertThat(registry.getActiveCaseId("tenant-1")).contains(id);
}

@Test void activeCaseId_overwrite() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    registry.setActiveCaseId("tenant-1", first);
    registry.setActiveCaseId("tenant-1", second);
    assertThat(registry.getActiveCaseId("tenant-1")).contains(second);
}

@Test void activeCaseId_clear() {
    registry.setActiveCaseId("tenant-1", UUID.randomUUID());
    registry.clearActiveCaseId("tenant-1");
    assertThat(registry.getActiveCaseId("tenant-1")).isEmpty();
}
```

Add to `CaseTransitionExecutorTest`:
```java
@Test void perAction_provisionOnly_additionsHumanBinding_removalsAutomated() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);
    // Build plan with node in additions → expect human-provision binding
    // Build plan with node in removals → expect automated prune worker, no human-deprovision binding
}

@Test void checkApproval_provisionOnly_skipsForProvision_runsForDeprovision() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);
    // In removals: checkApproval should run (deprovision is not human-gated)
    // In additions: checkApproval should return null (provision is human-gated)
}

@Test void caseCancellation_previousCaseCancelledBeforeNewCase() {
    // First execute: starts case, stores active case ID
    // Second execute: cancels first case, starts new case
}

@Test void caseCancellation_failureLogsAndProceeds() {
    // Mock caseHubRuntime.cancelCase() to throw
    // Verify: new case still starts, warning logged
}

@Test void buildOptimisticResult_perAction_provisionOnly() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);
    // In additions: Skipped("routed to human task binding")
    // In removals: Succeeded()
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl engine-adapter`
Expected: compilation errors — new registry methods don't exist yet

- [ ] **Step 3: Add active case tracking to DesiredStateExecutionRegistry**

Use `ide_insert_member` to add:
```java
private final ConcurrentHashMap<String, UUID> activeCases = new ConcurrentHashMap<>();

public Optional<UUID> getActiveCaseId(String tenancyId) {
    return Optional.ofNullable(activeCases.get(tenancyId));
}

public void setActiveCaseId(String tenancyId, UUID caseId) {
    activeCases.put(tenancyId, caseId);
}

public void clearActiveCaseId(String tenancyId) {
    activeCases.remove(tenancyId);
}
```

- [ ] **Step 4: Update CTE per-action separation and case cancellation**

Use `ide_replace_member` on `CaseTransitionExecutor.execute` to add case cancellation before building the new case:
```java
Optional<UUID> previousCaseId = executionRegistry.getActiveCaseId(tenancyId);
previousCaseId.ifPresent(id -> {
    try {
        caseHubRuntime.cancelCase(id);
    } catch (Exception e) {
        LOG.warnf("Failed to cancel previous case %s for tenancy %s — proceeding", id, tenancyId);
    }
});
```

And to store the new case ID after starting:
```java
executionRegistry.setActiveCaseId(tenancyId, caseId);
```

Use `ide_replace_member` on `CaseTransitionExecutor.checkApproval` — change `step.node().requiresHuman()` to `step.node().requiresHuman(step.action())`.

Use `ide_replace_member` on `CaseTransitionExecutor.buildCaseDefinition` — change all four `step.node().requiresHuman()` checks to `step.node().requiresHuman(step.action())`.

Use `ide_replace_member` on `CaseTransitionExecutor.buildOptimisticResult` — change both `step.node().requiresHuman()` checks to `step.node().requiresHuman(step.action())`, and change message from `"routed to WorkItem"` to `"routed to human task binding"`.

- [ ] **Step 5: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl engine-adapter`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add -u
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#80): CTE per-action separation + case cancellation for lifecycle coordination"
```

---

### Task 5: Remove WorkItemHumanNodeHandler + Update NoOp

**Files:**
- Delete: `work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandler.java`
- Delete: `work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandlerTest.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandler.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandlerTest.java`

**Interfaces:**
- Consumes: `HumanNodeHandler` SPI from Task 1

- [ ] **Step 1: Update NoOpHumanNodeHandler messages**

Use `ide_replace_member` on `NoOpHumanNodeHandler.onProvision`:
```java
@Override
public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
    return new StepOutcome.Skipped("requires human — no HumanNodeHandler configured");
}
```

Use `ide_replace_member` on `NoOpHumanNodeHandler.onDeprovision`:
```java
@Override
public StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
    return new StepOutcome.Skipped("requires human — no HumanNodeHandler configured");
}
```

Update `NoOpHumanNodeHandlerTest` assertions to match.

- [ ] **Step 2: Delete WorkItemHumanNodeHandler and its test**

Use `ide_refactor_safe_delete` on `WorkItemHumanNodeHandler.java` and `WorkItemHumanNodeHandlerTest.java`.

If safe delete reports usages, verify they are only test-internal or cross-references that will be removed.

- [ ] **Step 3: Verify work-adapter compiles**

Run: `mvn --batch-mode compile -pl work-adapter`
Expected: PASS — module compiles with no source files (or just the POM remains)

- [ ] **Step 4: Run full test suite**

Run: `mvn --batch-mode test`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add -u
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#72): remove WorkItemHumanNodeHandler — human nodes require case-backed orchestration"
```

---

### Task 6: Pipeline Example — Asymmetric Gating

**Files:**
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/TransformerSpec.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/SinkSpec.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineGoalCompiler.java`
- Modify: `examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/PipelineTest.java`

**Interfaces:**
- Consumes: `HumanGating.DEPROVISION_ONLY` from Task 1

- [ ] **Step 1: Write failing test for Gold-tier asymmetric gating**

Add to `PipelineTest`:

```java
@Test void goldTier_transformer_deprovisionOnly_gating() {
    PipelineBlueprint blueprint = standardBlueprintWithApproval();
    CompilationResult result = compiler.compile(blueprint, factory);
    DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();

    DesiredNode transformer = graph.nodes().get(NodeId.of("gold-agg"));
    assertThat(transformer.requiresHuman(StepAction.PROVISION)).isFalse();
    assertThat(transformer.requiresHuman(StepAction.DEPROVISION)).isTrue();
    assertThat(transformer.humanGating()).isEqualTo(HumanGating.NONE);
    // Gating comes from spec level, not node level
    assertThat(transformer.spec().humanGating()).isEqualTo(HumanGating.DEPROVISION_ONLY);
}

@Test void goldTier_sink_deprovisionOnly_gating() {
    PipelineBlueprint blueprint = standardBlueprintWithApproval();
    CompilationResult result = compiler.compile(blueprint, factory);
    DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();

    DesiredNode sink = graph.nodes().get(NodeId.of("warehouse-sink"));
    assertThat(sink.requiresHuman(StepAction.PROVISION)).isFalse();
    assertThat(sink.requiresHuman(StepAction.DEPROVISION)).isTrue();
}

@Test void bronzeSilver_noHumanGating() {
    PipelineBlueprint blueprint = standardBlueprint();
    CompilationResult result = compiler.compile(blueprint, factory);
    DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();

    for (DesiredNode node : graph.nodes().values()) {
        assertThat(node.requiresHuman()).isFalse();
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest=PipelineTest`
Expected: FAIL — Gold-tier specs don't have `humanGating()` returning DEPROVISION_ONLY yet

- [ ] **Step 3: Update TransformerSpec and SinkSpec**

Use `ide_edit_member` on `TransformerSpec` to add `humanGating()` override:
```java
@Override
public HumanGating humanGating() {
    return approvalRequired ? HumanGating.DEPROVISION_ONLY : HumanGating.NONE;
}
```

Use `ide_edit_member` on `SinkSpec` to add `humanGating()` override:
```java
@Override
public HumanGating humanGating() {
    return approvalRequired ? HumanGating.DEPROVISION_ONLY : HumanGating.NONE;
}
```

- [ ] **Step 4: Update PipelineGoalCompiler**

The GoalCompiler currently passes `false` as the `requiresHuman` boolean. Update to `HumanGating.NONE` for all nodes (already done in Task 1's mechanical update). The spec-level gating handles the per-action routing — the GoalCompiler doesn't need to set node-level gating.

- [ ] **Step 5: Update existing pipeline test assertions**

Existing tests asserting `node.requiresHuman()` need updating:
- `assertThat(addHumanReview.node().requiresHuman()).isTrue()` → use `requiresHuman(StepAction.DEPROVISION)` for deprovision-gated nodes
- `assertThat(addAiReview.node().requiresHuman()).isFalse()` → stays the same

- [ ] **Step 6: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest=PipelineTest`
Expected: all PASS

- [ ] **Step 7: Run full build**

Run: `mvn --batch-mode test`
Expected: all modules PASS

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add -u
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#79): pipeline example — Gold-tier DEPROVISION_ONLY asymmetric gating"
```

---

### Task 7: Dungeon Example — Update to HumanGating

**Files:**
- Modify: `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/CreatureSpec.java`
- Modify: `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/DungeonBlueprint.java`
- Modify: `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/DungeonGoalCompiler.java`
- Modify: `examples/dungeon/src/test/java/io/casehub/desiredstate/example/dungeon/DungeonTest.java`

**Interfaces:**
- Consumes: `HumanGating` from Task 1

- [ ] **Step 1: Update CreatureSpec**

Use `ide_edit_member` to replace `CreatureSpec`:
```java
public record CreatureSpec(String species, int level, HumanGating humanGating) implements NodeSpec {
    public CreatureSpec {
        Objects.requireNonNull(humanGating, "humanGating must not be null");
    }

    @Override
    public HumanGating humanGating() {
        return humanGating;
    }
}
```

- [ ] **Step 2: Update DungeonBlueprint.CreatureEntry**

Use `ide_edit_member` — replace `boolean requiresHuman` with `HumanGating humanGating`:
```java
public record CreatureEntry(String id, String species, int level, List<String> roomDeps, HumanGating humanGating) {}
```

- [ ] **Step 3: Update DungeonGoalCompiler**

Use `ide_replace_member` on `DungeonGoalCompiler` — replace `creature.requiresHuman()` with `creature.humanGating()`:
```java
CreatureSpec spec = new CreatureSpec(creature.species(), creature.level(), creature.humanGating());
```

- [ ] **Step 4: Update DungeonTest**

Replace `requiresHuman` assertions with `humanGating()` checks. Dragon should use `HumanGating.ALL`. Update blueprint construction.

- [ ] **Step 5: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl examples/dungeon`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add -u
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "chore(#79): dungeon example — migrate to HumanGating enum"
```

---

### Task 8: CLAUDE.md + Final Verification

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update CLAUDE.md**

Update references to `requiresHuman` → `HumanGating` throughout. Update:
- Core SPIs table: `HumanNodeHandler` description
- Core Runtime Types: add `HumanGating` entry
- Human Nodes section: document per-action routing
- Remove references to `WorkItemHumanNodeHandler` from work-adapter module description

- [ ] **Step 2: Full build verification**

Run: `mvn --batch-mode install`
Expected: all modules build and test successfully

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add CLAUDE.md
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "docs(#72,#79,#80): update CLAUDE.md — HumanGating, work-adapter boundary, case cancellation"
```
