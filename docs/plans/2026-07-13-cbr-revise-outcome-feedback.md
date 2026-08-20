# CBR Revise Outcome Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #76 — CBR Revise step: outcome feedback loop to case store
**Issue group:** #76

**Goal:** Track CBR proposals across reconciliation cycles and emit
outcome CloudEvents so downstream consumers can close the CBR feedback loop.

**Architecture:** CbrProposalTracker mediates between CBR components
(CbrFaultPolicy, CbrSituationRecompiler) and ReconciliationLoop. CBR
components record proposals when they select a configuration; the
reconciliation loop matches outcomes against pending proposals after
execution and emits `io.casehub.cbr.outcome` CloudEvents.

**Tech Stack:** Java 21, Quarkus, CDI, CloudEvents, JUnit 5

## Global Constraints

- All new types in api/ are pure Java (no Quarkus dependencies)
- All new runtime types are `@ApplicationScoped` CDI beans
- CloudEvent type `io.casehub.cbr.outcome` is platform-level (not desiredstate-specific)
- `GraphDiff` remains package-private — all callers are in `runtime/` package
- Pre-release: breaking SPI changes are acceptable
- TDD: every implementation step has a failing test first

---

### Task 1: New API Types — CbrProposal, CbrPath, CbrOutcomeData, CbrEventTypes

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/CbrProposal.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/CbrPath.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/CbrOutcomeData.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/CbrEventTypes.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/CbrProposalTest.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/CbrOutcomeDataTest.java`

**Interfaces:**
- Consumes: `NodeId` from api/
- Produces: `CbrProposal(sourceId, path, affectedNodeIds, timestamp)`,
  `CbrPath.FAULT | SITUATION`, `CbrOutcomeData(tenancyId, sourceId, path,
  nodeOutcomes, successCount, failureCount, resolvedCount, successRate,
  proposedAt, observedAt)`, `CbrEventTypes.CBR_OUTCOME`

- [ ] **Step 1: Write CbrProposal validation tests**

```java
// api/src/test/java/io/casehub/desiredstate/api/CbrProposalTest.java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class CbrProposalTest {

    @Test
    void validProposal() {
        var proposal = new CbrProposal("src-1", CbrPath.FAULT,
            Set.of(new NodeId("n1")), Instant.now());
        assertThat(proposal.sourceId()).isEqualTo("src-1");
        assertThat(proposal.path()).isEqualTo(CbrPath.FAULT);
        assertThat(proposal.affectedNodeIds()).containsExactly(new NodeId("n1"));
    }

    @Test
    void nullSourceId_throws() {
        assertThatThrownBy(() -> new CbrProposal(null, CbrPath.FAULT,
            Set.of(new NodeId("n1")), Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullPath_throws() {
        assertThatThrownBy(() -> new CbrProposal("src-1", null,
            Set.of(new NodeId("n1")), Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTimestamp_throws() {
        assertThatThrownBy(() -> new CbrProposal("src-1", CbrPath.FAULT,
            Set.of(new NodeId("n1")), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void affectedNodeIds_defensiveCopy() {
        var mutable = new java.util.HashSet<>(Set.of(new NodeId("n1")));
        var proposal = new CbrProposal("src-1", CbrPath.FAULT, mutable, Instant.now());
        mutable.add(new NodeId("n2"));
        assertThat(proposal.affectedNodeIds()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=CbrProposalTest`
Expected: FAIL — class not found

- [ ] **Step 3: Create CbrPath enum**

Use `ide_create_file`:

```java
// api/src/main/java/io/casehub/desiredstate/api/CbrPath.java
package io.casehub.desiredstate.api;

public enum CbrPath { FAULT, SITUATION }
```

- [ ] **Step 4: Create CbrProposal record**

Use `ide_create_file`:

```java
// api/src/main/java/io/casehub/desiredstate/api/CbrProposal.java
package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record CbrProposal(
    String sourceId,
    CbrPath path,
    Set<NodeId> affectedNodeIds,
    Instant timestamp
) {
    public CbrProposal {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(path, "path must not be null");
        affectedNodeIds = Set.copyOf(affectedNodeIds);
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}
```

- [ ] **Step 5: Run CbrProposalTest — verify PASS**

Run: `mvn --batch-mode test -pl api -Dtest=CbrProposalTest`

- [ ] **Step 6: Write CbrOutcomeData validation tests**

```java
// api/src/test/java/io/casehub/desiredstate/api/CbrOutcomeDataTest.java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CbrOutcomeDataTest {

    @Test
    void validOutcome() {
        var outcome = new CbrOutcomeData("t1", "src-1", CbrPath.FAULT,
            Map.of("n1", "SUCCEEDED"), 1, 0, 1, 1.0,
            Instant.now(), Instant.now());
        assertThat(outcome.tenancyId()).isEqualTo("t1");
        assertThat(outcome.successRate()).isEqualTo(1.0);
    }

    @Test
    void nodeOutcomes_defensiveCopy() {
        var mutable = new java.util.HashMap<>(Map.of("n1", "SUCCEEDED"));
        var outcome = new CbrOutcomeData("t1", "src-1", CbrPath.FAULT,
            mutable, 1, 0, 1, 1.0, Instant.now(), Instant.now());
        mutable.put("n2", "FAILED");
        assertThat(outcome.nodeOutcomes()).hasSize(1);
    }

    @Test
    void nullTenancyId_throws() {
        assertThatThrownBy(() -> new CbrOutcomeData(null, "src-1", CbrPath.FAULT,
            Map.of(), 0, 0, 0, 0.0, Instant.now(), Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 7: Create CbrOutcomeData record**

Use `ide_create_file`:

```java
// api/src/main/java/io/casehub/desiredstate/api/CbrOutcomeData.java
package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record CbrOutcomeData(
    String tenancyId,
    String sourceId,
    CbrPath path,
    Map<String, String> nodeOutcomes,
    int successCount,
    int failureCount,
    int resolvedCount,
    double successRate,
    Instant proposedAt,
    Instant observedAt
) {
    public CbrOutcomeData {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(path, "path must not be null");
        nodeOutcomes = Map.copyOf(nodeOutcomes);
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
```

- [ ] **Step 8: Create CbrEventTypes class**

Use `ide_create_file`:

```java
// api/src/main/java/io/casehub/desiredstate/api/CbrEventTypes.java
package io.casehub.desiredstate.api;

public final class CbrEventTypes {
    private CbrEventTypes() {}

    public static final String CBR_OUTCOME = "io.casehub.cbr.outcome";
}
```

- [ ] **Step 9: Run all api tests — verify PASS**

Run: `mvn --batch-mode test -pl api`

- [ ] **Step 10: Commit**

```bash
git add api/src/main/java/io/casehub/desiredstate/api/CbrProposal.java \
  api/src/main/java/io/casehub/desiredstate/api/CbrPath.java \
  api/src/main/java/io/casehub/desiredstate/api/CbrOutcomeData.java \
  api/src/main/java/io/casehub/desiredstate/api/CbrEventTypes.java \
  api/src/test/java/io/casehub/desiredstate/api/CbrProposalTest.java \
  api/src/test/java/io/casehub/desiredstate/api/CbrOutcomeDataTest.java
git commit -m "feat(#76): add CbrProposal, CbrOutcomeData, CbrPath, CbrEventTypes API types"
```

---

### Task 2: SPI Breaking Changes — FaultPolicy + SituationRecompiler gain tenancyId

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/FaultPolicy.java` — add `String tenancyId` parameter
- Modify: `api/src/main/java/io/casehub/desiredstate/api/SituationRecompiler.java` — add `String tenancyId` parameter
- Modify: `api/src/test/java/io/casehub/desiredstate/api/SpiContractTest.java:78` — update call
- Modify: `api/src/test/java/io/casehub/desiredstate/api/SituationRecompilerTest.java:32,64` — update calls
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/FaultPolicyEngine.java:32,36` — add tenancyId, thread through
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/CbrFaultPolicy.java:33` — add tenancyId
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/CbrSituationRecompiler.java:40` — add tenancyId
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/SituationRecompilerEngine.java:23` — add tenancyId, thread through
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java:649,710,726` — pass tenancyId to faultPolicyEngine.evaluate()
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatch.java:94` — pass tenancyId to recompilerEngine.recompile()
- Modify: all example FaultPolicy implementations (4 files) — add tenancyId parameter
- Modify: `examples/expansion/.../ExpansionSituationRecompiler.java` — add tenancyId
- Modify: all test files calling evaluate/recompile/onFault (FaultPolicyEngineTest, CbrFaultPolicyTest, CbrSituationRecompilerTest, SituationRecompilerEngineTest, ReconciliationLoopCloudEventTest, etc.)

**Interfaces:**
- Consumes: existing SPI interfaces
- Produces: updated `FaultPolicy.onFault(String tenancyId, ...)`,
  `SituationRecompiler.recompile(String tenancyId, ...)`,
  `FaultPolicyEngine.evaluate(String tenancyId, ...)`,
  `SituationRecompilerEngine.recompile(String tenancyId, ...)`

- [ ] **Step 1: Update FaultPolicy SPI**

Use `ide_edit_member` on `FaultPolicy.java`, member `onFault`:

```java
List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual);
```

- [ ] **Step 2: Update SituationRecompiler SPI**

Use `ide_edit_member` on `SituationRecompiler.java`, member `recompile`:

```java
Optional<CompilationResult> recompile(
    String tenancyId,
    DesiredStateGraph current,
    ActualState actual,
    ActiveSituation situation,
    DesiredStateGraphFactory factory);
```

- [ ] **Step 3: Update FaultPolicyEngine.evaluate()**

Use `ide_edit_member` on `FaultPolicyEngine.java`, member `evaluate`:

```java
public List<GraphMutation> evaluate(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
    List<GraphMutation> allMutations = new ArrayList<>();
    for (FaultPolicy policy : policies) {
        List<GraphMutation> policyMutations = policy.onFault(tenancyId, event, current, actual);
        allMutations.addAll(policyMutations);
    }

    Map<NodeId, List<GraphMutation>> byNode = new HashMap<>();
    List<GraphMutation> dependencyMutations = new ArrayList<>();

    for (GraphMutation mutation : allMutations) {
        NodeId targetNodeId = getTargetNodeId(mutation);
        if (targetNodeId != null) {
            byNode.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(mutation);
        } else {
            dependencyMutations.add(mutation);
        }
    }

    List<GraphMutation> merged = new ArrayList<>();

    for (Map.Entry<NodeId, List<GraphMutation>> entry : byNode.entrySet()) {
        NodeId nodeId = entry.getKey();
        List<GraphMutation> nodeMutations = entry.getValue();

        Set<GraphMutation> uniqueMutations = new LinkedHashSet<>(nodeMutations);

        if (uniqueMutations.size() > 1) {
            Iterator<GraphMutation> it = uniqueMutations.iterator();
            GraphMutation first = it.next();
            GraphMutation second = it.next();
            throw new ConflictingMutationException(nodeId, first, second);
        }

        merged.addAll(uniqueMutations);
    }

    merged.addAll(dependencyMutations);

    return merged;
}
```

- [ ] **Step 4: Update SituationRecompilerEngine.recompile()**

Use `ide_edit_member` on `SituationRecompilerEngine.java`, member `recompile`:

```java
public Optional<CompilationResult> recompile(
        String tenancyId,
        DesiredStateGraph current, ActualState actual,
        ActiveSituation situation, DesiredStateGraphFactory factory) {
    for (SituationRecompiler recompiler : recompilers) {
        Optional<CompilationResult> result = recompiler.recompile(
            tenancyId, current, actual, situation, factory);
        if (result.isPresent()) return result;
    }
    return Optional.empty();
}
```

- [ ] **Step 5: Update CbrFaultPolicy.onFault()**

Use `ide_edit_member` on `CbrFaultPolicy.java`, member `onFault`:

```java
@Override
public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
    CbrConfiguration config = resolveConfiguration();

    RetrievalContext context = RetrievalContext.forFault(current, actual, event);

    List<RetrievedConfiguration> candidates = retriever.retrieve(context, config.maxCandidates());
    if (candidates.isEmpty()) {
        LOG.log(Level.INFO, "cbr.no-candidate: no similar configurations found for {0}", event.node());
        return List.of();
    }

    LOG.log(Level.INFO, "cbr.retrieve: {0} candidate(s), top confidence={1}",
        new Object[]{candidates.size(), candidates.stream()
            .mapToDouble(RetrievedConfiguration::confidence).max().orElse(0.0)});

    Optional<AdaptedConfiguration> best = candidates.stream()
        .filter(c -> c.confidence() >= config.minimumRetrievalConfidence())
        .map(c -> adapter.adapt(c, context))
        .flatMap(Optional::stream)
        .filter(a -> a.confidence() >= config.minimumAdaptationConfidence())
        .max(Comparator.comparingDouble(AdaptedConfiguration::confidence));

    if (best.isEmpty()) {
        LOG.log(Level.INFO, "cbr.no-candidate: no candidate survived confidence gates");
        return List.of();
    }

    AdaptedConfiguration selected = best.get();
    LOG.log(Level.INFO, "cbr.selected: sourceId={0}, confidence={1}, path=fault",
        new Object[]{selected.sourceId(), selected.confidence()});

    return GraphDiff.computeMutations(current, selected.graph());
}
```

- [ ] **Step 6: Update CbrSituationRecompiler.recompile()**

Use `ide_edit_member` on `CbrSituationRecompiler.java`, member `recompile`:

```java
@Override
public Optional<CompilationResult> recompile(
        String tenancyId,
        DesiredStateGraph current, ActualState actual,
        ActiveSituation situation, DesiredStateGraphFactory factory) {
    CbrConfiguration config = resolveConfiguration();

    RetrievalContext context = RetrievalContext.forSituation(current, actual, situation);

    List<RetrievedConfiguration> candidates = retriever.retrieve(context, config.maxCandidates());
    if (candidates.isEmpty()) {
        LOG.log(Level.INFO, "cbr.no-candidate: no similar configurations found for situation {0}",
            situation.situationId());
        return Optional.empty();
    }

    LOG.log(Level.INFO, "cbr.retrieve: {0} candidate(s), top confidence={1}",
        new Object[]{candidates.size(), candidates.stream()
            .mapToDouble(RetrievedConfiguration::confidence).max().orElse(0.0)});

    Optional<AdaptedConfiguration> best = candidates.stream()
        .filter(c -> c.confidence() >= config.minimumRetrievalConfidence())
        .map(c -> adapter.adapt(c, context))
        .flatMap(Optional::stream)
        .filter(a -> a.confidence() >= config.minimumAdaptationConfidence())
        .max(Comparator.comparingDouble(AdaptedConfiguration::confidence));

    if (best.isEmpty()) {
        LOG.log(Level.INFO, "cbr.no-candidate: no candidate survived confidence gates for situation {0}",
            situation.situationId());
        return Optional.empty();
    }

    AdaptedConfiguration selected = best.get();
    LOG.log(Level.INFO, "cbr.selected: sourceId={0}, confidence={1}, path=situation",
        new Object[]{selected.sourceId(), selected.confidence()});

    return Optional.of(CompilationResult.single(selected.graph()));
}
```

- [ ] **Step 7: Update ReconciliationLoop — detectDrift and faultFeedback**

In `ReconciliationLoop.java`, `detectDrift()` at line 649 calls `faultPolicyEngine.evaluate(faultEvent, mutated, actual)`. Update to pass `tenancyId`:

Use `ide_replace_member` on `detectDrift` — the call at line 649 becomes:
```java
List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(tenancyId, faultEvent, mutated, actual);
```

In `faultFeedback()` at lines 710 and 726 — same change:
```java
List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(tenancyId, faultEvent, mutated, actual);
```

- [ ] **Step 8: Update DesiredStateReplanDispatch.replan()**

At line 94, the call `recompilerEngine.recompile(current, actual, situation, graphFactory)` becomes:

```java
Optional<CompilationResult> newResult = recompilerEngine.recompile(
    tenancyId, current, actual, situation, graphFactory);
```

- [ ] **Step 9: Update all example FaultPolicy implementations**

Each needs `String tenancyId` added as the first parameter of `onFault()`:

- `examples/dungeon/.../HeroRaidFaultPolicy.java`
- `examples/pipeline/.../ProvisionEscalationFaultPolicy.java`
- `examples/pipeline/.../SchemaDriftFaultPolicy.java`
- `examples/pipeline/.../QuarantineFaultPolicy.java`
- `examples/spatial/.../ZoneRebalanceFaultPolicy.java`
- `examples/expansion/.../ExpansionFaultPolicy.java`
- `examples/expansion/.../ExpansionSituationRecompiler.java`

Use `ide_edit_member` on each — add `String tenancyId` as first parameter, body unchanged.

- [ ] **Step 10: Update all test files**

Fix compilation in test files by adding `"tenant-1"` as the first argument to all `onFault()`, `evaluate()`, `recompile()` calls:

- `api/src/test/java/.../SpiContractTest.java` — line 78
- `api/src/test/java/.../SituationRecompilerTest.java` — lines 32, 64
- `runtime/src/test/java/.../FaultPolicyEngineTest.java` — 6 calls
- `runtime/src/test/java/.../CbrFaultPolicyTest.java` — all test calls
- `runtime/src/test/java/.../CbrSituationRecompilerTest.java` — all test calls
- `runtime/src/test/java/.../SituationRecompilerEngineTest.java` — 6 calls
- `runtime/src/test/java/.../ReconciliationLoopCloudEventTest.java` — if FaultPolicyEngine is constructed with test policies

- [ ] **Step 11: Build full project — verify PASS**

Run: `mvn --batch-mode install`

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat(#76): add tenancyId to FaultPolicy.onFault() and SituationRecompiler.recompile()"
```

---

### Task 3: GraphDiff.targetNodeId() Consolidation

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/GraphDiff.java` — add `targetNodeId()` static method
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/FaultPolicyEngine.java` — delegate `getTargetNodeId()` to `GraphDiff.targetNodeId()`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/GraphDiffTest.java` — add targetNodeId tests

**Interfaces:**
- Consumes: `GraphMutation` sealed interface
- Produces: `GraphDiff.targetNodeId(GraphMutation) → NodeId` (package-private)

- [ ] **Step 1: Write targetNodeId tests**

Add to existing `GraphDiffTest.java` using `ide_insert_member`:

```java
@Test
void targetNodeId_addNode() {
    DesiredNode node = new DesiredNode(new NodeId("n1"), new TestSpec("v"), false);
    assertThat(GraphDiff.targetNodeId(new GraphMutation.AddNode(node))).isEqualTo(new NodeId("n1"));
}

@Test
void targetNodeId_removeNode() {
    assertThat(GraphDiff.targetNodeId(new GraphMutation.RemoveNode(new NodeId("n1")))).isEqualTo(new NodeId("n1"));
}

@Test
void targetNodeId_updateNode() {
    assertThat(GraphDiff.targetNodeId(new GraphMutation.UpdateNode(new NodeId("n1"), new TestSpec("v2")))).isEqualTo(new NodeId("n1"));
}

@Test
void targetNodeId_addDependency_returnsNull() {
    assertThat(GraphDiff.targetNodeId(new GraphMutation.AddDependency(
        new Dependency(new NodeId("a"), new NodeId("b"))))).isNull();
}

@Test
void targetNodeId_removeDependency_returnsNull() {
    assertThat(GraphDiff.targetNodeId(new GraphMutation.RemoveDependency(
        new Dependency(new NodeId("a"), new NodeId("b"))))).isNull();
}
```

- [ ] **Step 2: Run tests — verify FAIL**

Run: `mvn --batch-mode test -pl runtime -Dtest=GraphDiffTest`

- [ ] **Step 3: Add targetNodeId() to GraphDiff**

Use `ide_insert_member` on `GraphDiff.java`, after `computeMutations`:

```java
static NodeId targetNodeId(GraphMutation mutation) {
    return switch (mutation) {
        case GraphMutation.AddNode add -> add.node().id();
        case GraphMutation.RemoveNode remove -> remove.id();
        case GraphMutation.UpdateNode update -> update.id();
        case GraphMutation.AddDependency ignored -> null;
        case GraphMutation.RemoveDependency ignored -> null;
    };
}
```

- [ ] **Step 4: Delegate FaultPolicyEngine.getTargetNodeId() to GraphDiff**

Use `ide_replace_member` on `FaultPolicyEngine.java`, member `getTargetNodeId`:

```java
return GraphDiff.targetNodeId(mutation);
```

- [ ] **Step 5: Run tests — verify PASS**

Run: `mvn --batch-mode test -pl runtime -Dtest="GraphDiffTest,FaultPolicyEngineTest"`

- [ ] **Step 6: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/GraphDiff.java \
  runtime/src/main/java/io/casehub/desiredstate/runtime/FaultPolicyEngine.java \
  runtime/src/test/java/io/casehub/desiredstate/runtime/GraphDiffTest.java
git commit -m "refactor(#76): consolidate targetNodeId into GraphDiff, FaultPolicyEngine delegates"
```

---

### Task 4: CbrProposalTracker

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/CbrProposalTracker.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/CbrProposalTrackerTest.java`

**Interfaces:**
- Consumes: `CbrProposal`, `CbrOutcomeData`, `CbrPath`, `TransitionResult`, `StepOutcome`, `DesiredStateGraph`, `NodeId`
- Produces: `CbrProposalTracker.recordProposal(String tenancyId, CbrProposal)`,
  `CbrProposalTracker.matchOutcomes(String tenancyId, TransitionResult, DesiredStateGraph) → List<CbrOutcomeData>`,
  `CbrProposalTracker.clearTenant(String tenancyId)`

- [ ] **Step 1: Write CbrProposalTrackerTest**

```java
// runtime/src/test/java/io/casehub/desiredstate/runtime/CbrProposalTrackerTest.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CbrProposalTrackerTest {

    private record TestSpec(String value) implements NodeSpec {}

    private CbrProposalTracker tracker;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        tracker = new CbrProposalTracker();
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void noPendingProposals_returnsEmpty() {
        var result = new TransitionResult(Map.of());
        var graph = factory.empty();
        assertThat(tracker.matchOutcomes("t1", result, graph)).isEmpty();
    }

    @Test
    void proposalMatchedWithSucceeded() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Succeeded()));
        var graph = factory.empty();
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).successCount()).isEqualTo(1);
        assertThat(outcomes.get(0).failureCount()).isEqualTo(0);
        assertThat(outcomes.get(0).resolvedCount()).isEqualTo(1);
        assertThat(outcomes.get(0).successRate()).isEqualTo(1.0);
        assertThat(outcomes.get(0).nodeOutcomes().get("n1")).isEqualTo("SUCCEEDED");
    }

    @Test
    void proposalMatchedWithFailed() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Failed("err")));
        var graph = factory.empty();
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).failureCount()).isEqualTo(1);
        assertThat(outcomes.get(0).successRate()).isEqualTo(0.0);
        assertThat(outcomes.get(0).nodeOutcomes().get("n1")).isEqualTo("FAILED");
    }

    @Test
    void proposalMatchedWithRejected_countsAsFailure() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Rejected("denied")));
        var graph = factory.empty();
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        assertThat(outcomes.get(0).failureCount()).isEqualTo(1);
        assertThat(outcomes.get(0).nodeOutcomes().get("n1")).isEqualTo("REJECTED");
    }

    @Test
    void nodeNotInResultButInGraph_alreadyPresent() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of());
        var node = new DesiredNode(nodeId, new TestSpec("v"), false);
        var graph = factory.of(Map.of(nodeId, node), Set.of());
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        assertThat(outcomes.get(0).successCount()).isEqualTo(1);
        assertThat(outcomes.get(0).nodeOutcomes().get("n1")).isEqualTo("ALREADY_PRESENT");
    }

    @Test
    void nodeNotInResultNotInGraph_superseded() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of());
        var graph = factory.empty();
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        // All nodes SUPERSEDED → resolvedCount=0 → no outcome emitted
        assertThat(outcomes).isEmpty();
    }

    @Test
    void allNodesSkipped_noOutcomeEmitted() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Skipped("skip")));
        var graph = factory.empty();
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        assertThat(outcomes).isEmpty();
    }

    @Test
    void mixedOutcomes_partialSuccess() {
        var n1 = new NodeId("n1");
        var n2 = new NodeId("n2");
        var n3 = new NodeId("n3");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(n1, n2, n3), Instant.now()));

        var result = new TransitionResult(Map.of(
            n1, new StepOutcome.Succeeded(),
            n2, new StepOutcome.Failed("err"),
            n3, new StepOutcome.Succeeded()));
        var graph = factory.empty();
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        assertThat(outcomes.get(0).successCount()).isEqualTo(2);
        assertThat(outcomes.get(0).failureCount()).isEqualTo(1);
        assertThat(outcomes.get(0).resolvedCount()).isEqualTo(3);
        assertThat(outcomes.get(0).successRate()).isCloseTo(0.6667, within(0.001));
    }

    @Test
    void multipleProposals_matchedIndependently() {
        var n1 = new NodeId("n1");
        var n2 = new NodeId("n2");
        tracker.recordProposal("t1", new CbrProposal(
            "src-A", CbrPath.FAULT, Set.of(n1), Instant.now()));
        tracker.recordProposal("t1", new CbrProposal(
            "src-B", CbrPath.SITUATION, Set.of(n2), Instant.now()));

        var result = new TransitionResult(Map.of(
            n1, new StepOutcome.Succeeded(),
            n2, new StepOutcome.Failed("err")));
        var graph = factory.empty();
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).sourceId()).isEqualTo("src-A");
        assertThat(outcomes.get(0).successRate()).isEqualTo(1.0);
        assertThat(outcomes.get(1).sourceId()).isEqualTo("src-B");
        assertThat(outcomes.get(1).successRate()).isEqualTo(0.0);
    }

    @Test
    void clearTenant_removesPendingProposals() {
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(new NodeId("n1")), Instant.now()));
        tracker.clearTenant("t1");

        var result = new TransitionResult(Map.of(new NodeId("n1"), new StepOutcome.Succeeded()));
        assertThat(tracker.matchOutcomes("t1", result, factory.empty())).isEmpty();
    }

    @Test
    void differentTenants_noInterference() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Succeeded()));
        assertThat(tracker.matchOutcomes("t2", result, factory.empty())).isEmpty();
        // t1's proposal is still pending
        assertThat(tracker.matchOutcomes("t1", result, factory.empty())).hasSize(1);
    }

    @Test
    void matchOutcomes_removesProposalsAfterMatching() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Succeeded()));
        tracker.matchOutcomes("t1", result, factory.empty());
        // Second call should return empty — proposals consumed
        assertThat(tracker.matchOutcomes("t1", result, factory.empty())).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests — verify FAIL**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrProposalTrackerTest`

- [ ] **Step 3: Create CbrProposalTracker**

Use `ide_create_file`:

```java
// runtime/src/main/java/io/casehub/desiredstate/runtime/CbrProposalTracker.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class CbrProposalTracker {

    private final ConcurrentHashMap<String, List<CbrProposal>> pending =
        new ConcurrentHashMap<>();

    public void recordProposal(String tenancyId, CbrProposal proposal) {
        pending.computeIfAbsent(tenancyId, k -> new CopyOnWriteArrayList<>())
            .add(proposal);
    }

    public List<CbrOutcomeData> matchOutcomes(String tenancyId,
            TransitionResult result, DesiredStateGraph currentGraph) {
        List<CbrProposal> proposals = pending.remove(tenancyId);
        if (proposals == null || proposals.isEmpty()) return List.of();

        Instant now = Instant.now();
        List<CbrOutcomeData> outcomes = new ArrayList<>();

        for (CbrProposal proposal : proposals) {
            Map<String, String> nodeOutcomes = new LinkedHashMap<>();
            int success = 0, failure = 0;

            for (NodeId nodeId : proposal.affectedNodeIds()) {
                StepOutcome outcome = result.outcomes().get(nodeId);
                if (outcome == null) {
                    if (!currentGraph.nodes().containsKey(nodeId)) {
                        nodeOutcomes.put(nodeId.value(), "SUPERSEDED");
                    } else {
                        nodeOutcomes.put(nodeId.value(), "ALREADY_PRESENT");
                        success++;
                    }
                } else {
                    switch (outcome) {
                        case StepOutcome.Succeeded s -> {
                            nodeOutcomes.put(nodeId.value(), "SUCCEEDED");
                            success++;
                        }
                        case StepOutcome.Failed f -> {
                            nodeOutcomes.put(nodeId.value(), "FAILED");
                            failure++;
                        }
                        case StepOutcome.Skipped s ->
                            nodeOutcomes.put(nodeId.value(), "SKIPPED");
                        case StepOutcome.Rejected r -> {
                            nodeOutcomes.put(nodeId.value(), "REJECTED");
                            failure++;
                        }
                    }
                }
            }

            int resolved = success + failure;
            if (resolved == 0) continue;
            double successRate = (double) success / resolved;
            outcomes.add(new CbrOutcomeData(
                tenancyId, proposal.sourceId(), proposal.path(),
                nodeOutcomes, success, failure, resolved, successRate,
                proposal.timestamp(), now));
        }
        return outcomes;
    }

    public void clearTenant(String tenancyId) {
        pending.remove(tenancyId);
    }
}
```

- [ ] **Step 4: Run tests — verify PASS**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrProposalTrackerTest`

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/CbrProposalTracker.java \
  runtime/src/test/java/io/casehub/desiredstate/runtime/CbrProposalTrackerTest.java
git commit -m "feat(#76): add CbrProposalTracker — mediates CBR proposals and reconciliation outcomes"
```

---

### Task 5: CbrFaultPolicy + CbrSituationRecompiler Proposal Recording

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/CbrFaultPolicy.java` — inject tracker, record proposals
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/CbrSituationRecompiler.java` — inject tracker, record proposals
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/CbrFaultPolicyTest.java` — add proposal recording tests
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/CbrSituationRecompilerTest.java` — add proposal recording tests

**Interfaces:**
- Consumes: `CbrProposalTracker.recordProposal()`, `GraphDiff.targetNodeId()`, `GraphDiff.computeMutations()`
- Produces: proposal recorded in tracker when CBR selects a configuration

- [ ] **Step 1: Write proposal recording tests for CbrFaultPolicy**

Add to `CbrFaultPolicyTest.java` using `ide_insert_member`. The test class needs a `CbrProposalTracker tracker` field added and threaded through to the `CbrFaultPolicy` constructor. Update `setUp()` to inject the tracker:

```java
@Test
void successfulAdaptation_recordsProposal() {
    // Set up retriever and adapter to return a valid adapted configuration
    // with node n1 added
    // ... (use same pattern as successfulAdaptation_shouldProduceMutations)
    policy.onFault("tenant-1", faultEvent, currentGraph, actualState);
    
    // Verify proposal was recorded
    var result = new TransitionResult(Map.of(new NodeId("n1"), new StepOutcome.Succeeded()));
    var outcomes = tracker.matchOutcomes("tenant-1", result, factory.empty());
    assertThat(outcomes).hasSize(1);
    assertThat(outcomes.get(0).sourceId()).isEqualTo("src-1");
    assertThat(outcomes.get(0).path()).isEqualTo(CbrPath.FAULT);
}

@Test
void noCandidates_doesNotRecordProposal() {
    retriever.setResults(List.of());
    policy.onFault("tenant-1", faultEvent, currentGraph, actualState);
    
    var result = new TransitionResult(Map.of());
    assertThat(tracker.matchOutcomes("tenant-1", result, factory.empty())).isEmpty();
}

@Test
void emptyMutations_doesNotRecordProposal() {
    // Set up so the adapted graph equals the current graph → zero mutations
    // ... adapter returns graph identical to current
    policy.onFault("tenant-1", faultEvent, currentGraph, actualState);
    
    var result = new TransitionResult(Map.of());
    assertThat(tracker.matchOutcomes("tenant-1", result, factory.empty())).isEmpty();
}
```

- [ ] **Step 2: Run tests — verify FAIL**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrFaultPolicyTest`

- [ ] **Step 3: Update CbrFaultPolicy to inject tracker and record proposals**

Add `CbrProposalTracker` field. Use `ide_edit_member` on constructor to accept and store it.
Use `ide_edit_member` on `onFault` to add proposal recording after `GraphDiff.computeMutations()`:

```java
List<GraphMutation> mutations = GraphDiff.computeMutations(current, selected.graph());

Set<NodeId> affectedNodeIds = mutations.stream()
    .map(GraphDiff::targetNodeId)
    .filter(Objects::nonNull)
    .collect(Collectors.toSet());

if (!affectedNodeIds.isEmpty()) {
    tracker.recordProposal(tenancyId, new CbrProposal(
        selected.sourceId(), CbrPath.FAULT, affectedNodeIds, Instant.now()));
}

return mutations;
```

- [ ] **Step 4: Run CbrFaultPolicyTest — verify PASS**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrFaultPolicyTest`

- [ ] **Step 5: Write proposal recording tests for CbrSituationRecompiler**

Same pattern — add tracker injection to test setup, verify proposal recorded after successful adaptation, verify not recorded when no candidates or empty delta.

- [ ] **Step 6: Update CbrSituationRecompiler to inject tracker and record proposals**

Same pattern as CbrFaultPolicy. After selecting the adapted configuration, compute the delta and record:

```java
Set<NodeId> affectedNodeIds = GraphDiff.computeMutations(current, selected.graph())
    .stream()
    .map(GraphDiff::targetNodeId)
    .filter(Objects::nonNull)
    .collect(Collectors.toSet());

if (!affectedNodeIds.isEmpty()) {
    tracker.recordProposal(tenancyId, new CbrProposal(
        selected.sourceId(), CbrPath.SITUATION, affectedNodeIds, Instant.now()));
}
```

- [ ] **Step 7: Run CbrSituationRecompilerTest — verify PASS**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrSituationRecompilerTest`

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/CbrFaultPolicy.java \
  runtime/src/main/java/io/casehub/desiredstate/runtime/CbrSituationRecompiler.java \
  runtime/src/test/java/io/casehub/desiredstate/runtime/CbrFaultPolicyTest.java \
  runtime/src/test/java/io/casehub/desiredstate/runtime/CbrSituationRecompilerTest.java
git commit -m "feat(#76): CbrFaultPolicy and CbrSituationRecompiler record proposals in tracker"
```

---

### Task 6: ReconciliationEventEmitter.cbrOutcome() + ReconciliationLoop Integration

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationEventEmitter.java` — add `cbrOutcome()` method
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java` — inject tracker, call matchOutcomes, emit events
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopCbrOutcomeTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopCloudEventTest.java` — update constructors if needed

**Interfaces:**
- Consumes: `CbrProposalTracker`, `CbrOutcomeData`, `CbrEventTypes.CBR_OUTCOME`
- Produces: `io.casehub.cbr.outcome` CloudEvents emitted via the cloud event sink

- [ ] **Step 1: Add cbrOutcome() to ReconciliationEventEmitter**

Use `ide_insert_member` on `ReconciliationEventEmitter.java`, after `nodeRecovered`:

```java
public CloudEvent cbrOutcome(CbrOutcomeData data) {
    return base(CbrEventTypes.CBR_OUTCOME)
        .withSubject(data.sourceId())
        .withExtension("tenancyid", data.tenancyId())
        .withExtension("cbrpath", data.path().name().toLowerCase())
        .withExtension("successrate", String.valueOf(data.successRate()))
        .withData("application/json", serialize(data))
        .build();
}
```

- [ ] **Step 2: Write ReconciliationLoopCbrOutcomeTest**

Create a dedicated test class for CBR outcome integration:

```java
// runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopCbrOutcomeTest.java
package io.casehub.desiredstate.runtime;

// Test cases:
// - cbrFaultInCycleN_outcomeMatchedInCycleN1_cloudEventEmitted
// - driftTriggeredProposal_matchedInSameCycle
// - emptyPlanCycle_resolvesPendingProposalsAsAlreadyPresent
// - supersededNodes_correctEventEmitted
// - reconcileTypes_doesNotCallMatchOutcomes_proposalsRemainPending
// - tenantStop_clearsPendingProposals
// - noCbrProposals_noOutcomeEvents
```

Tests use the same test infrastructure as `ReconciliationLoopCloudEventTest` (TestActualStateAdapter, TestTransitionExecutor, TestEventSource) plus a `CbrProposalTracker`. The ReconciliationLoop constructor needs to accept the tracker (add it to the private canonical constructor).

Key test: set up a CbrFaultPolicy with a StubRetriever/StubAdapter that returns a known configuration. Run two reconciliation cycles:
- Cycle 1: a node faults → CbrFaultPolicy proposes mutations → mutations applied
- Cycle 2: proposed nodes are executed → matchOutcomes fires → CloudEvent captured

Verify the captured CloudEvent has type `io.casehub.cbr.outcome`, correct sourceId, correct per-node outcomes.

- [ ] **Step 3: Run tests — verify FAIL**

Run: `mvn --batch-mode test -pl runtime -Dtest=ReconciliationLoopCbrOutcomeTest`

- [ ] **Step 4: Add CbrProposalTracker to ReconciliationLoop**

Add field: `private final CbrProposalTracker cbrTracker;`

Update the private canonical constructor (line 166) to accept `CbrProposalTracker`. For the CDI constructor (line 87), inject it. For test constructors, accept it or default to `new CbrProposalTracker()`.

- [ ] **Step 5: Add matchOutcomes + emitCbrOutcomeEvents to reconcile()**

Update `reconcile()` (line 521). Insert `matchOutcomes()` call after `execute()` but before `faultFeedback()`. Also handle the empty-plan path:

```java
// In the empty-plan path (around line 539):
TransitionResult emptyResult = new TransitionResult(Map.of());
List<CbrOutcomeData> cbrOutcomes = cbrTracker.matchOutcomes(tenancyId, emptyResult, desired);
if (!driftedNodes.isEmpty() || !activeProblems.isEmpty()) {
    emitCycleEvents(desired, plan, emptyResult, actual, driftedNodes);
}
emitCbrOutcomeEvents(cbrOutcomes);
return;

// In the non-empty-plan path (around line 548):
TransitionResult result = execute(plan, tenancyId);
List<CbrOutcomeData> cbrOutcomes = cbrTracker.matchOutcomes(tenancyId, result, desired);
faultFeedback(desired, plan, result, actual);
emitCycleEvents(desired, plan, result, actual, driftedNodes);
emitCbrOutcomeEvents(cbrOutcomes);
```

Add `emitCbrOutcomeEvents` method to TenantLoop:

```java
private void emitCbrOutcomeEvents(List<CbrOutcomeData> outcomes) {
    for (CbrOutcomeData outcome : outcomes) {
        cloudEventSink.accept(eventEmitter.cbrOutcome(outcome));
    }
}
```

- [ ] **Step 6: Add tracker cleanup to TenantLoop.stop()**

At the end of `stop()` (line 450), add:

```java
cbrTracker.clearTenant(tenancyId);
```

- [ ] **Step 7: Run tests — verify PASS**

Run: `mvn --batch-mode test -pl runtime -Dtest=ReconciliationLoopCbrOutcomeTest`

- [ ] **Step 8: Run full test suite — verify no regressions**

Run: `mvn --batch-mode install`

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(#76): ReconciliationLoop emits CBR outcome CloudEvents via CbrProposalTracker"
```

---

### Task 7: Update CLAUDE.md and Testing Module

**Files:**
- Modify: `CLAUDE.md` — update Core SPIs table, Core Runtime Types, DesiredStateEventTypes → add CbrEventTypes
- Modify: `testing/src/main/java/io/casehub/desiredstate/testing/MockConfigurationRetriever.java` — verify no changes needed
- Modify: `testing/src/main/java/io/casehub/desiredstate/testing/MockConfigurationAdapter.java` — verify no changes needed

**Interfaces:**
- Consumes: all new types from Tasks 1-6
- Produces: updated documentation

- [ ] **Step 1: Update CLAUDE.md**

Add to Core SPIs table:
- `FaultPolicy.onFault()` — add `String tenancyId` first parameter
- `SituationRecompiler.recompile()` — add `String tenancyId` first parameter

Add to Core Runtime Types:
- `CbrProposal` — sourceId, path, affectedNodeIds, timestamp
- `CbrOutcomeData` — CloudEvent data for CBR outcomes
- `CbrPath` — FAULT, SITUATION
- `CbrEventTypes` — `io.casehub.cbr.outcome`
- `CbrProposalTracker` — mediates CBR proposals and reconciliation outcomes

- [ ] **Step 2: Run full build — verify PASS**

Run: `mvn --batch-mode install`

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(#76): update CLAUDE.md with CBR Revise types and updated SPI signatures"
```
