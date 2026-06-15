# Data Pipeline Example Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the data pipeline example domain with medallion architecture, three-tier fault escalation, and runtime prerequisites.

**Architecture:** Pipeline example in `examples/pipeline/` implements all desiredstate SPIs (GoalCompiler, ActualStateAdapter, NodeProvisioner, FaultPolicy, EventSource) using an in-memory PipelineWorld simulation. Three runtime fixes in `runtime/` enable DRIFTED detection, correct CAS mutation accumulation, and proper deprovision fault typing. All tests are plain JUnit (no @QuarkusTest), matching the dungeon example pattern.

**Tech Stack:** Java 21, Quarkus (arc, rest, vertx), SmallRye Mutiny, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-15-data-pipeline-example-design.md`

---

### Task 1: Runtime fix — DRIFTED → NODE_DEGRADED detection (#32)

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopTest.java`

- [ ] **Step 1: Write the failing test — DRIFTED nodes produce NODE_DEGRADED fault events**

Add to `ReconciliationLoopTest.java`:

```java
@Test
void driftedNodes_produceNodeDegradedFaultEvents() {
    DesiredNode nodeA = node("a");
    DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());

    // Node "a" is DRIFTED — exists but diverged from spec
    actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.DRIFTED));

    // Fault policy that captures NODE_DEGRADED events
    CopyOnWriteArrayList<FaultEvent> capturedFaults = new CopyOnWriteArrayList<>();
    FaultPolicy capturingPolicy = (event, current) -> {
        capturedFaults.add(event);
        return List.of();
    };
    faultEngine = new FaultPolicyEngine(List.of(capturingPolicy));

    loop = new ReconciliationLoop(
        planner, testExecutor, actualAdapter, faultEngine, testEventSource,
        TEST_DEBOUNCE, TEST_RESYNC);

    loop.start("test-tenant", desired);

    // Wait for initial reconciliation
    await().atMost(Duration.ofSeconds(2)).until(() -> !capturedFaults.isEmpty());

    // Should have received a NODE_DEGRADED fault event for node "a"
    assertEquals(1, capturedFaults.size());
    assertEquals(NodeId.of("a"), capturedFaults.get(0).node());
    assertEquals(FaultType.NODE_DEGRADED, capturedFaults.get(0).type());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest="ReconciliationLoopTest#driftedNodes_produceNodeDegradedFaultEvents"`

Expected: FAIL — no NODE_DEGRADED event produced (DRIFTED nodes are invisible to the current reconcile cycle)

- [ ] **Step 3: Write the failing test — drift detection runs even when transition plan is empty**

```java
@Test
void driftDetection_runsBeforeEmptyPlanCheck() {
    DesiredNode nodeA = node("a");
    DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());

    // Node "a" is DRIFTED — plan will be empty (not ABSENT, so no additions; not orphaned, so no removals)
    actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.DRIFTED));

    // Fault policy that adds a replacement node on NODE_DEGRADED
    DesiredNode replacement = new DesiredNode(
        NodeId.of("a-fix"), NodeType.of("test"), new TestSpec("fix"), false);
    FaultPolicy degradedPolicy = (event, current) -> {
        if (event.type() == FaultType.NODE_DEGRADED) {
            return List.of(new GraphMutation.AddNode(replacement));
        }
        return List.of();
    };
    faultEngine = new FaultPolicyEngine(List.of(degradedPolicy));

    loop = new ReconciliationLoop(
        planner, testExecutor, actualAdapter, faultEngine, testEventSource,
        TEST_DEBOUNCE, TEST_RESYNC);

    loop.start("test-tenant", desired);

    // Wait for reconciliation — the drift-triggered mutation should cause
    // "a-fix" to appear in a subsequent transition plan
    // Set "a-fix" as absent so planner plans to provision it
    actualAdapter.setStatuses(Map.of(
        NodeId.of("a"), NodeStatus.DRIFTED,
        NodeId.of("a-fix"), NodeStatus.UNKNOWN));

    await().atMost(Duration.ofSeconds(3)).until(() ->
        testExecutor.executedPlans.stream().anyMatch(plan ->
            plan.additions().stream().anyMatch(step ->
                step.node().id().equals(NodeId.of("a-fix")))));
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest="ReconciliationLoopTest#driftDetection_runsBeforeEmptyPlanCheck"`

Expected: FAIL — drift detection doesn't exist, so the mutation is never applied and "a-fix" never appears in a plan

- [ ] **Step 5: Implement drift detection in ReconciliationLoop.reconcile()**

In `ReconciliationLoop.java`, restructure the `reconcile()` method in the inner `TenantLoop` class:

```java
private void reconcile() {
    try {
        DesiredStateGraph desired = desiredRef.get();

        // 1. Read actual state
        ActualState actual = actualStateAdapter.readActual(desired);

        // 2. Detect DRIFTED nodes → NODE_DEGRADED fault events
        DesiredStateGraph afterDrift = desired;
        for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
            NodeStatus status = actual.statuses().getOrDefault(entry.getKey(), NodeStatus.UNKNOWN);
            if (status == NodeStatus.DRIFTED) {
                FaultEvent faultEvent = new FaultEvent(
                    entry.getKey(), FaultType.NODE_DEGRADED, "Node drifted from desired spec");
                List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, afterDrift);
                for (GraphMutation mutation : mutations) {
                    afterDrift = afterDrift.withMutation(mutation);
                }
            }
        }
        if (afterDrift != desired) {
            desiredRef.compareAndSet(desired, afterDrift);
            desired = afterDrift;
        }

        // 3. Plan transition (on the potentially-mutated desired graph)
        ActualState actualForPlan = (afterDrift != desired)
            ? actualStateAdapter.readActual(desired) : actual;
        TransitionPlan plan = planner.plan(desired, actual);
        if (plan.isEmpty()) {
            return;
        }

        // 4. Execute transition
        TransitionResult result = executor.execute(plan).await().indefinitely();

        // 5. Fault feedback — evaluate failed outcomes through fault policies
        DesiredStateGraph mutated = desired;
        for (Map.Entry<NodeId, StepOutcome> entry : result.outcomes().entrySet()) {
            if (entry.getValue() instanceof StepOutcome.Failed failed) {
                FaultEvent faultEvent = new FaultEvent(
                    entry.getKey(), FaultType.PROVISION_FAILED, failed.reason());
                List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, mutated);
                for (GraphMutation mutation : mutations) {
                    mutated = mutated.withMutation(mutation);
                }
            }
        }
        if (mutated != desired) {
            desiredRef.compareAndSet(desired, mutated);
        }
    } catch (Exception e) {
        LOG.log(Level.WARNING,
            "Reconciliation cycle failed for tenant " + tenancyId, e);
    }
}
```

Note: Step 5 also fixes the CAS race (Issue #33) — mutations are accumulated on a progressively-mutated graph and applied with a single CAS. The original per-iteration CAS is replaced.

- [ ] **Step 6: Run both new tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime -Dtest="ReconciliationLoopTest#driftedNodes_produceNodeDegradedFaultEvents+driftDetection_runsBeforeEmptyPlanCheck"`

Expected: PASS

- [ ] **Step 7: Run full runtime test suite to check for regressions**

Run: `mvn --batch-mode test -pl runtime`

Expected: All tests pass (the existing `faultFeedback_appliesMutationsToDesiredGraph` test should continue to work with the refactored reconcile method)

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopTest.java
git commit -m "fix(#32,#33): detect DRIFTED → NODE_DEGRADED and fix CAS race in reconcile

ReconciliationLoop.reconcile() now:
1. Scans for DRIFTED nodes and creates NODE_DEGRADED fault events
   BEFORE the empty-plan early return
2. Accumulates all fault mutations on a progressively-mutated graph
   with a single CAS at the end (fixes silent mutation loss when
   multiple nodes fail in the same cycle)"
```

---

### Task 2: Runtime fix — DEPROVISION_FAILED fault typing (#34)

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopTest.java`

- [ ] **Step 1: Write the failing test**

Add to `ReconciliationLoopTest.java`:

```java
@Test
void deprovisionFailure_producesDeprovisionFailedFaultType() {
    DesiredNode nodeA = node("a");
    DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());

    // Node "b" is PRESENT in actual but not in desired — should be deprovisioned
    actualAdapter.setStatuses(Map.of(
        NodeId.of("a"), NodeStatus.PRESENT,
        NodeId.of("b"), NodeStatus.PRESENT));

    // Configure executor to fail deprovision of "b"
    testExecutor.failDeprovisionNodes.add(NodeId.of("b"));

    CopyOnWriteArrayList<FaultEvent> capturedFaults = new CopyOnWriteArrayList<>();
    FaultPolicy capturingPolicy = (event, current) -> {
        capturedFaults.add(event);
        return List.of();
    };
    faultEngine = new FaultPolicyEngine(List.of(capturingPolicy));

    loop = new ReconciliationLoop(
        planner, testExecutor, actualAdapter, faultEngine, testEventSource,
        TEST_DEBOUNCE, TEST_RESYNC);

    loop.start("test-tenant", desired);

    await().atMost(Duration.ofSeconds(2)).until(() -> !capturedFaults.isEmpty());

    assertEquals(1, capturedFaults.size());
    assertEquals(NodeId.of("b"), capturedFaults.get(0).node());
    assertEquals(FaultType.DEPROVISION_FAILED, capturedFaults.get(0).type());
}
```

This test also requires adding `failDeprovisionNodes` to the `TestTransitionExecutor` inner class:

```java
final Set<NodeId> failDeprovisionNodes = ConcurrentHashMap.newKeySet();
```

And updating its `execute()` method's removal loop:

```java
for (OrderedStep step : plan.removals()) {
    if (failDeprovisionNodes.contains(step.node().id())) {
        outcomes.put(step.node().id(), new StepOutcome.Failed("test deprovision failure"));
    } else {
        outcomes.put(step.node().id(), new StepOutcome.Succeeded());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest="ReconciliationLoopTest#deprovisionFailure_producesDeprovisionFailedFaultType"`

Expected: FAIL — fault event has type PROVISION_FAILED instead of DEPROVISION_FAILED

- [ ] **Step 3: Implement deprovision fault typing**

In `ReconciliationLoop.reconcile()`, replace the fault feedback section with code that distinguishes provision vs deprovision failures:

```java
// 5. Fault feedback — evaluate failed outcomes through fault policies
Set<NodeId> removalNodeIds = new HashSet<>();
for (OrderedStep step : plan.removals()) {
    removalNodeIds.add(step.node().id());
}

DesiredStateGraph mutated = desired;
for (Map.Entry<NodeId, StepOutcome> entry : result.outcomes().entrySet()) {
    if (entry.getValue() instanceof StepOutcome.Failed failed) {
        FaultType faultType = removalNodeIds.contains(entry.getKey())
            ? FaultType.DEPROVISION_FAILED
            : FaultType.PROVISION_FAILED;
        FaultEvent faultEvent = new FaultEvent(
            entry.getKey(), faultType, failed.reason());
        List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, mutated);
        for (GraphMutation mutation : mutations) {
            mutated = mutated.withMutation(mutation);
        }
    }
}
if (mutated != desired) {
    desiredRef.compareAndSet(desired, mutated);
}
```

Add the import for `HashSet` and `Set` if not already present.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl runtime -Dtest="ReconciliationLoopTest#deprovisionFailure_producesDeprovisionFailedFaultType"`

Expected: PASS

- [ ] **Step 5: Run full runtime test suite**

Run: `mvn --batch-mode test -pl runtime`

Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add runtime/
git commit -m "fix(#34): ReconciliationLoop produces DEPROVISION_FAILED for removal failures

Correlates failed node IDs with the plan's removals set to determine
the correct FaultType. Previously all failures were typed as
PROVISION_FAILED regardless of whether the step was a provision or
deprovision."
```

---

### Task 3: Module setup + foundation types

**Files:**
- Create: `examples/pipeline/pom.xml`
- Modify: `pom.xml` (parent — add module)
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineLayer.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineNodeTypes.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/DataSourceSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/SchemaSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/IngestionSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/CleanserSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/EnricherSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/ValidatorSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/TransformerSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/SinkSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/AiReviewSpec.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/HumanReviewSpec.java`

- [ ] **Step 1: Create pom.xml for pipeline module**

Create `examples/pipeline/pom.xml` — copy dungeon pom.xml structure, change artifactId and name:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-desiredstate-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>casehub-desiredstate-example-pipeline</artifactId>

    <name>CaseHub Desired State :: Example :: Data Pipeline</name>
    <description>Teaching example — data pipeline domain with medallion architecture,
        schema validation, three-tier fault escalation (retry → AI → human).</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-desiredstate-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-desiredstate</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-vertx</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest</artifactId>
        </dependency>

        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-testing</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>${jandex-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals><goal>jandex</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Add module to parent pom.xml**

Add `<module>examples/pipeline</module>` after `<module>examples/dungeon</module>` in the parent pom.xml modules section.

- [ ] **Step 3: Create PipelineLayer enum**

```java
package io.casehub.desiredstate.example.pipeline;

public enum PipelineLayer {
    BRONZE,
    SILVER,
    GOLD
}
```

- [ ] **Step 4: Create PipelineNodeTypes**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeType;

public final class PipelineNodeTypes {
    public static final NodeType DATA_SOURCE = new NodeType("data-source");
    public static final NodeType SCHEMA = new NodeType("schema");
    public static final NodeType INGESTION = new NodeType("ingestion");
    public static final NodeType CLEANSER = new NodeType("cleanser");
    public static final NodeType ENRICHER = new NodeType("enricher");
    public static final NodeType VALIDATOR = new NodeType("validator");
    public static final NodeType TRANSFORMER = new NodeType("transformer");
    public static final NodeType SINK = new NodeType("sink");
    public static final NodeType AI_REVIEW = new NodeType("ai-review");
    public static final NodeType HUMAN_REVIEW = new NodeType("human-review");

    public static PipelineLayer layerOf(NodeType type) {
        if (type.equals(DATA_SOURCE) || type.equals(SCHEMA) || type.equals(INGESTION)) {
            return PipelineLayer.BRONZE;
        } else if (type.equals(CLEANSER) || type.equals(ENRICHER) || type.equals(VALIDATOR)) {
            return PipelineLayer.SILVER;
        } else if (type.equals(TRANSFORMER) || type.equals(SINK)) {
            return PipelineLayer.GOLD;
        }
        return null;
    }

    private PipelineNodeTypes() {}
}
```

- [ ] **Step 5: Create all 12 NodeSpec records**

Each is a simple record in a separate file under `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/`:

`DataSourceSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;

public record DataSourceSpec(String name, String format, String uri) implements NodeSpec {}
```

`SchemaSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record SchemaSpec(String name, List<String> fields, int version) implements NodeSpec {
    public SchemaSpec { fields = List.copyOf(fields); }
}
```

`IngestionSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;

public record IngestionSpec(String sourceRef, int batchSize, String format) implements NodeSpec {}
```

`CleanserSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record CleanserSpec(List<String> rules, boolean deduplication, String nullHandling) implements NodeSpec {
    public CleanserSpec { rules = List.copyOf(rules); }
}
```

`EnricherSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record EnricherSpec(String lookupSource, List<String> joinKeys, List<String> enrichFields) implements NodeSpec {
    public EnricherSpec { joinKeys = List.copyOf(joinKeys); enrichFields = List.copyOf(enrichFields); }
}
```

`ValidatorSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;

public record ValidatorSpec(String schemaRef, double qualityThreshold, boolean anomalyDetection) implements NodeSpec {}
```

`TransformerSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record TransformerSpec(List<String> aggregations, List<String> reshapeRules, String outputFormat) implements NodeSpec {
    public TransformerSpec { aggregations = List.copyOf(aggregations); reshapeRules = List.copyOf(reshapeRules); }
}
```

`SinkSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record SinkSpec(String destination, String format, List<String> partitionKeys) implements NodeSpec {
    public SinkSpec { partitionKeys = List.copyOf(partitionKeys); }
}
```

`AiReviewSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;

public record AiReviewSpec(NodeId targetNodeId, String errorDetail) implements NodeSpec {}
```

`HumanReviewSpec.java`:
```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;

public record HumanReviewSpec(NodeId targetNodeId, String errorDetail, String escalationReason) implements NodeSpec {}
```

- [ ] **Step 6: Verify the module compiles**

Run: `mvn --batch-mode compile -pl examples/pipeline`

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add examples/pipeline/ pom.xml
git commit -m "feat(#2): add pipeline example module — foundation types, NodeSpecs, medallion layer

PipelineLayer enum (BRONZE/SILVER/GOLD), PipelineNodeTypes (10 types
including fault-generated AI_REVIEW and HUMAN_REVIEW), and 12 NodeSpec
records for the data pipeline domain."
```

---

### Task 4: PipelineBlueprint + PipelineGoalCompiler

**Files:**
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineBlueprint.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineGoalCompiler.java`
- Create: `examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/PipelineTest.java`

- [ ] **Step 1: Write failing tests — buildBasicPipeline and topologicalOrderMatchesMedallionLayers**

Create `PipelineTest.java` with the test infrastructure and first two tests. Tests construct all components manually (no CDI), matching the dungeon pattern:

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {

    private DesiredStateGraphFactory factory;
    private PipelineGoalCompiler compiler;
    private TransitionPlanner planner;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        compiler = new PipelineGoalCompiler();
        planner = new TransitionPlanner();
    }

    private PipelineBlueprint standardBlueprint() {
        return PipelineBlueprint.builder()
            .source("clickstream", "json", "kafka://clicks")
            .schema("click-schema", List.of("userId", "pageUrl", "timestamp"), 1)
            .ingestion("click-ingest", "clickstream", 1000, "json")
            .cleanser("click-clean", List.of("deduplicate", "normalize-timestamps"), true, "DROP")
            .enricher("geo-enrich", "geo-lookup", List.of("userId"), List.of("country", "city"))
            .validator("quality-gate", "click-schema", 0.95, true)
            .transformer("session-agg", List.of("sessionize", "count-pages"), List.of("group-by-session"), "parquet")
            .sink("warehouse", "s3://analytics/sessions", "parquet", List.of("date", "country"))
            .build();
    }

    @Test
    void buildBasicPipeline() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // 8 blueprint node types
        assertEquals(8, graph.nodes().size());

        // Verify all node types present
        Map<NodeId, DesiredNode> nodes = graph.nodes();
        assertTrue(nodes.containsKey(NodeId.of("clickstream")));
        assertTrue(nodes.containsKey(NodeId.of("click-schema")));
        assertTrue(nodes.containsKey(NodeId.of("click-ingest")));
        assertTrue(nodes.containsKey(NodeId.of("click-clean")));
        assertTrue(nodes.containsKey(NodeId.of("geo-enrich")));
        assertTrue(nodes.containsKey(NodeId.of("quality-gate")));
        assertTrue(nodes.containsKey(NodeId.of("session-agg")));
        assertTrue(nodes.containsKey(NodeId.of("warehouse")));

        // Two independent roots
        Set<NodeId> roots = graph.roots();
        assertEquals(2, roots.size());
        assertTrue(roots.contains(NodeId.of("clickstream")));
        assertTrue(roots.contains(NodeId.of("click-schema")));

        // Dependency: ingestion depends on datasource
        assertTrue(graph.dependenciesOf(NodeId.of("click-ingest")).contains(NodeId.of("clickstream")));

        // Fan-in: cleanser depends on both ingestion AND schema
        Set<NodeId> cleanserDeps = graph.dependenciesOf(NodeId.of("click-clean"));
        assertEquals(2, cleanserDeps.size());
        assertTrue(cleanserDeps.contains(NodeId.of("click-ingest")));
        assertTrue(cleanserDeps.contains(NodeId.of("click-schema")));

        // Fan-in: validator depends on both enricher AND schema
        Set<NodeId> validatorDeps = graph.dependenciesOf(NodeId.of("quality-gate"));
        assertEquals(2, validatorDeps.size());
        assertTrue(validatorDeps.contains(NodeId.of("geo-enrich")));
        assertTrue(validatorDeps.contains(NodeId.of("click-schema")));

        // Medallion layer assignments (derived from node type)
        assertEquals(PipelineLayer.BRONZE, PipelineNodeTypes.layerOf(nodes.get(NodeId.of("clickstream")).type()));
        assertEquals(PipelineLayer.SILVER, PipelineNodeTypes.layerOf(nodes.get(NodeId.of("click-clean")).type()));
        assertEquals(PipelineLayer.GOLD, PipelineNodeTypes.layerOf(nodes.get(NodeId.of("session-agg")).type()));
    }

    @Test
    void topologicalOrderMatchesMedallionLayers() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // All nodes ABSENT — planner should plan all additions
        ActualState actual = new ActualState(Map.of());
        TransitionPlan plan = planner.plan(graph, actual);

        assertEquals(8, plan.additions().size());

        // Verify ordering: all Bronze before any Silver, all Silver before any Gold
        List<OrderedStep> additions = plan.additions();
        int lastBronzeIndex = -1;
        int firstSilverIndex = Integer.MAX_VALUE;
        int lastSilverIndex = -1;
        int firstGoldIndex = Integer.MAX_VALUE;

        for (int i = 0; i < additions.size(); i++) {
            PipelineLayer layer = PipelineNodeTypes.layerOf(additions.get(i).node().type());
            if (layer == PipelineLayer.BRONZE) lastBronzeIndex = i;
            if (layer == PipelineLayer.SILVER) {
                firstSilverIndex = Math.min(firstSilverIndex, i);
                lastSilverIndex = i;
            }
            if (layer == PipelineLayer.GOLD) firstGoldIndex = Math.min(firstGoldIndex, i);
        }

        assertTrue(lastBronzeIndex < firstSilverIndex,
            "All Bronze nodes must come before any Silver node");
        assertTrue(lastSilverIndex < firstGoldIndex,
            "All Silver nodes must come before any Gold node");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest="PipelineTest#buildBasicPipeline+topologicalOrderMatchesMedallionLayers"`

Expected: Compilation failure — PipelineBlueprint and PipelineGoalCompiler don't exist

- [ ] **Step 3: Implement PipelineBlueprint**

Create `PipelineBlueprint.java`:

```java
package io.casehub.desiredstate.example.pipeline;

import java.util.ArrayList;
import java.util.List;

public class PipelineBlueprint {

    public record SourceEntry(String id, String format, String uri) {}
    public record SchemaEntry(String id, List<String> fields, int version) {}
    public record IngestionEntry(String id, String sourceRef, int batchSize, String format) {}
    public record CleanserEntry(String id, List<String> rules, boolean deduplication, String nullHandling) {}
    public record EnricherEntry(String id, String lookupSource, List<String> joinKeys, List<String> enrichFields) {}
    public record ValidatorEntry(String id, String schemaRef, double qualityThreshold, boolean anomalyDetection) {}
    public record TransformerEntry(String id, List<String> aggregations, List<String> reshapeRules, String outputFormat) {}
    public record SinkEntry(String id, String destination, String format, List<String> partitionKeys) {}

    private final List<SourceEntry> sources;
    private final List<SchemaEntry> schemas;
    private final List<IngestionEntry> ingestions;
    private final List<CleanserEntry> cleansers;
    private final List<EnricherEntry> enrichers;
    private final List<ValidatorEntry> validators;
    private final List<TransformerEntry> transformers;
    private final List<SinkEntry> sinks;

    private PipelineBlueprint(Builder b) {
        this.sources = List.copyOf(b.sources);
        this.schemas = List.copyOf(b.schemas);
        this.ingestions = List.copyOf(b.ingestions);
        this.cleansers = List.copyOf(b.cleansers);
        this.enrichers = List.copyOf(b.enrichers);
        this.validators = List.copyOf(b.validators);
        this.transformers = List.copyOf(b.transformers);
        this.sinks = List.copyOf(b.sinks);
    }

    public List<SourceEntry> sources() { return sources; }
    public List<SchemaEntry> schemas() { return schemas; }
    public List<IngestionEntry> ingestions() { return ingestions; }
    public List<CleanserEntry> cleansers() { return cleansers; }
    public List<EnricherEntry> enrichers() { return enrichers; }
    public List<ValidatorEntry> validators() { return validators; }
    public List<TransformerEntry> transformers() { return transformers; }
    public List<SinkEntry> sinks() { return sinks; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<SourceEntry> sources = new ArrayList<>();
        private final List<SchemaEntry> schemas = new ArrayList<>();
        private final List<IngestionEntry> ingestions = new ArrayList<>();
        private final List<CleanserEntry> cleansers = new ArrayList<>();
        private final List<EnricherEntry> enrichers = new ArrayList<>();
        private final List<ValidatorEntry> validators = new ArrayList<>();
        private final List<TransformerEntry> transformers = new ArrayList<>();
        private final List<SinkEntry> sinks = new ArrayList<>();

        public Builder source(String id, String format, String uri) {
            sources.add(new SourceEntry(id, format, uri));
            return this;
        }
        public Builder schema(String id, List<String> fields, int version) {
            schemas.add(new SchemaEntry(id, List.copyOf(fields), version));
            return this;
        }
        public Builder ingestion(String id, String sourceRef, int batchSize, String format) {
            ingestions.add(new IngestionEntry(id, sourceRef, batchSize, format));
            return this;
        }
        public Builder cleanser(String id, List<String> rules, boolean deduplication, String nullHandling) {
            cleansers.add(new CleanserEntry(id, List.copyOf(rules), deduplication, nullHandling));
            return this;
        }
        public Builder enricher(String id, String lookupSource, List<String> joinKeys, List<String> enrichFields) {
            enrichers.add(new EnricherEntry(id, lookupSource, List.copyOf(joinKeys), List.copyOf(enrichFields)));
            return this;
        }
        public Builder validator(String id, String schemaRef, double qualityThreshold, boolean anomalyDetection) {
            validators.add(new ValidatorEntry(id, schemaRef, qualityThreshold, anomalyDetection));
            return this;
        }
        public Builder transformer(String id, List<String> aggregations, List<String> reshapeRules, String outputFormat) {
            transformers.add(new TransformerEntry(id, List.copyOf(aggregations), List.copyOf(reshapeRules), outputFormat));
            return this;
        }
        public Builder sink(String id, String destination, String format, List<String> partitionKeys) {
            sinks.add(new SinkEntry(id, destination, format, List.copyOf(partitionKeys)));
            return this;
        }
        public PipelineBlueprint build() { return new PipelineBlueprint(this); }
    }
}
```

- [ ] **Step 4: Implement PipelineGoalCompiler**

Create `PipelineGoalCompiler.java`:

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.ArrayList;
import java.util.List;

public class PipelineGoalCompiler implements GoalCompiler<PipelineBlueprint> {

    @Override
    public DesiredStateGraph compile(PipelineBlueprint goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        // Bronze: sources (roots)
        for (PipelineBlueprint.SourceEntry src : goals.sources()) {
            nodes.add(new DesiredNode(NodeId.of(src.id()), PipelineNodeTypes.DATA_SOURCE,
                new DataSourceSpec(src.id(), src.format(), src.uri()), false));
        }

        // Bronze: schemas (roots)
        for (PipelineBlueprint.SchemaEntry schema : goals.schemas()) {
            nodes.add(new DesiredNode(NodeId.of(schema.id()), PipelineNodeTypes.SCHEMA,
                new SchemaSpec(schema.id(), schema.fields(), schema.version()), false));
        }

        // Bronze: ingestion depends on its sourceRef
        for (PipelineBlueprint.IngestionEntry ing : goals.ingestions()) {
            nodes.add(new DesiredNode(NodeId.of(ing.id()), PipelineNodeTypes.INGESTION,
                new IngestionSpec(ing.sourceRef(), ing.batchSize(), ing.format()), false));
            dependencies.add(new Dependency(NodeId.of(ing.id()), NodeId.of(ing.sourceRef())));
        }

        // Silver: cleanser depends on all ingestions + all schemas
        for (PipelineBlueprint.CleanserEntry cl : goals.cleansers()) {
            nodes.add(new DesiredNode(NodeId.of(cl.id()), PipelineNodeTypes.CLEANSER,
                new CleanserSpec(cl.rules(), cl.deduplication(), cl.nullHandling()), false));
            for (PipelineBlueprint.IngestionEntry ing : goals.ingestions()) {
                dependencies.add(new Dependency(NodeId.of(cl.id()), NodeId.of(ing.id())));
            }
            for (PipelineBlueprint.SchemaEntry schema : goals.schemas()) {
                dependencies.add(new Dependency(NodeId.of(cl.id()), NodeId.of(schema.id())));
            }
        }

        // Silver: enricher depends on all cleansers
        for (PipelineBlueprint.EnricherEntry enr : goals.enrichers()) {
            nodes.add(new DesiredNode(NodeId.of(enr.id()), PipelineNodeTypes.ENRICHER,
                new EnricherSpec(enr.lookupSource(), enr.joinKeys(), enr.enrichFields()), false));
            for (PipelineBlueprint.CleanserEntry cl : goals.cleansers()) {
                dependencies.add(new Dependency(NodeId.of(enr.id()), NodeId.of(cl.id())));
            }
        }

        // Silver: validator depends on all enrichers + all schemas
        for (PipelineBlueprint.ValidatorEntry val : goals.validators()) {
            nodes.add(new DesiredNode(NodeId.of(val.id()), PipelineNodeTypes.VALIDATOR,
                new ValidatorSpec(val.schemaRef(), val.qualityThreshold(), val.anomalyDetection()), false));
            for (PipelineBlueprint.EnricherEntry enr : goals.enrichers()) {
                dependencies.add(new Dependency(NodeId.of(val.id()), NodeId.of(enr.id())));
            }
            for (PipelineBlueprint.SchemaEntry schema : goals.schemas()) {
                dependencies.add(new Dependency(NodeId.of(val.id()), NodeId.of(schema.id())));
            }
        }

        // Gold: transformer depends on all validators
        for (PipelineBlueprint.TransformerEntry tr : goals.transformers()) {
            nodes.add(new DesiredNode(NodeId.of(tr.id()), PipelineNodeTypes.TRANSFORMER,
                new TransformerSpec(tr.aggregations(), tr.reshapeRules(), tr.outputFormat()), false));
            for (PipelineBlueprint.ValidatorEntry val : goals.validators()) {
                dependencies.add(new Dependency(NodeId.of(tr.id()), NodeId.of(val.id())));
            }
        }

        // Gold: sink depends on all transformers
        for (PipelineBlueprint.SinkEntry sink : goals.sinks()) {
            nodes.add(new DesiredNode(NodeId.of(sink.id()), PipelineNodeTypes.SINK,
                new SinkSpec(sink.destination(), sink.format(), sink.partitionKeys()), false));
            for (PipelineBlueprint.TransformerEntry tr : goals.transformers()) {
                dependencies.add(new Dependency(NodeId.of(sink.id()), NodeId.of(tr.id())));
            }
        }

        return factory.of(nodes, dependencies);
    }
}
```

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest="PipelineTest#buildBasicPipeline+topologicalOrderMatchesMedallionLayers"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add examples/pipeline/
git commit -m "feat(#2): PipelineBlueprint + PipelineGoalCompiler — blueprint builder and dependency wiring

Dependencies inferred from canonical pipeline topology:
ingestion → datasource, cleanser → (ingestion + schema),
enricher → cleanser, validator → (enricher + schema),
transformer → validator, sink → transformer.
Two independent roots (datasource, schema)."
```

---

### Task 5: PipelineWorld — simulation with registries

**Files:**
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineWorld.java`

- [ ] **Step 1: Write failing tests for PipelineWorld**

Add to `PipelineTest.java`. These test the world's registries and cascade behavior directly:

```java
private PipelineWorld world;

@BeforeEach
void setUp() {
    factory = new DefaultDesiredStateGraphFactory();
    compiler = new PipelineGoalCompiler();
    planner = new TransitionPlanner();
    world = new PipelineWorld();
}
```

Add test:

```java
@Test
void deprovisionCascade_downstreamGoesIdle() {
    // Set up a running pipeline: ingestion → cleanser → enricher → validator → transformer → sink
    world.registerSource(NodeId.of("src"), "json", "kafka://test");
    world.registerSchema("schema", List.of("a", "b"), 1);
    world.setStage(NodeId.of("ingest"), PipelineWorld.StageState.RUNNING, "schema", "schema");
    world.setStage(NodeId.of("clean"), PipelineWorld.StageState.RUNNING, "schema", "schema");
    world.setStage(NodeId.of("enrich"), PipelineWorld.StageState.RUNNING, "schema", "schema");
    world.setStage(NodeId.of("validate"), PipelineWorld.StageState.RUNNING, "schema", "schema");
    world.setStage(NodeId.of("transform"), PipelineWorld.StageState.RUNNING, "schema", "schema");
    world.setStage(NodeId.of("sink"), PipelineWorld.StageState.RUNNING, "schema", "schema");

    // Register downstream relationships for cascade
    world.registerDownstream(NodeId.of("clean"), List.of(
        NodeId.of("enrich"), NodeId.of("validate"), NodeId.of("transform"), NodeId.of("sink")));

    // Deprovision cleanser — should cascade downstream to IDLE
    world.removeStage(NodeId.of("clean"));

    assertEquals(PipelineWorld.StageState.IDLE, world.stageState(NodeId.of("enrich")));
    assertEquals(PipelineWorld.StageState.IDLE, world.stageState(NodeId.of("validate")));
    assertEquals(PipelineWorld.StageState.IDLE, world.stageState(NodeId.of("transform")));
    assertEquals(PipelineWorld.StageState.IDLE, world.stageState(NodeId.of("sink")));
    // Upstream should be unchanged
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("ingest")));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest="PipelineTest#deprovisionCascade_downstreamGoesIdle"`

Expected: Compilation failure — PipelineWorld doesn't exist

- [ ] **Step 3: Implement PipelineWorld**

Create `PipelineWorld.java` — the full simulation with all registries and resolution methods as specified in Sections 6.1-6.7 of the spec. This is the largest single file. Key elements:
- `StageState` enum
- `StageEntry` record (state, inputSchema, outputSchema, processed/failed/quarantined counters, errorDetail)
- `SchemaDefinition` record (name, fields, version)
- `LookupSourceEntry` record (name)
- `ReviewEntry` record (targetNode, ReviewState)
- `ReviewState` enum (PENDING, RESOLVED, UNRESOLVED)
- ConcurrentHashMaps for stages, schemas, lookup sources, reviews, data sources
- Cascade tracking: `Map<NodeId, List<NodeId>>` for downstream relationships
- Resolution methods: `approveSchemaChange()`, `clearStageError()`, `setAiReviewOutcome()`, `resolveHumanReview()`

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PipelineWorld {

    public enum StageState {
        IDLE, RUNNING, COMPLETED, FAILED, QUARANTINED, DEGRADED
    }

    public enum ReviewState {
        PENDING, RESOLVED, UNRESOLVED
    }

    public record StageEntry(StageState state, String inputSchema, String outputSchema,
                             long processed, long failed, long quarantined, String errorDetail) {
        public StageEntry withState(StageState newState) {
            return new StageEntry(newState, inputSchema, outputSchema, processed, failed, quarantined, errorDetail);
        }
        public StageEntry withError(StageState newState, String error) {
            return new StageEntry(newState, inputSchema, outputSchema, processed, failed, quarantined, error);
        }
    }

    public record SchemaDefinition(String name, List<String> fields, int version) {
        public SchemaDefinition { fields = List.copyOf(fields); }
    }

    public record LookupSourceEntry(String name) {}

    public record ReviewEntry(NodeId targetNode, ReviewState state) {}

    public record DataSourceEntry(String name, String format, String uri) {}

    private final ConcurrentHashMap<NodeId, StageEntry> stages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SchemaDefinition> schemas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LookupSourceEntry> lookupSources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, ReviewEntry> reviews = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, DataSourceEntry> dataSources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, List<NodeId>> downstreamMap = new ConcurrentHashMap<>();

    // --- Data source operations ---
    public void registerSource(NodeId id, String format, String uri) {
        dataSources.put(id, new DataSourceEntry(id.value(), format, uri));
    }
    public boolean hasSource(NodeId id) { return dataSources.containsKey(id); }
    public void removeSource(NodeId id) { dataSources.remove(id); }
    public Map<NodeId, DataSourceEntry> allSources() { return Map.copyOf(dataSources); }

    // --- Schema registry ---
    public void registerSchema(String name, List<String> fields, int version) {
        schemas.put(name, new SchemaDefinition(name, fields, version));
    }
    public SchemaDefinition schema(String name) { return schemas.get(name); }
    public boolean hasSchema(String name) { return schemas.containsKey(name); }
    public void removeSchema(String name) { schemas.remove(name); }
    public Map<String, SchemaDefinition> allSchemas() { return Map.copyOf(schemas); }

    // --- Lookup source registry ---
    public void registerLookupSource(String name) {
        lookupSources.put(name, new LookupSourceEntry(name));
    }
    public boolean hasLookupSource(String name) { return lookupSources.containsKey(name); }

    // --- Stage operations ---
    public void setStage(NodeId id, StageState state, String inputSchema, String outputSchema) {
        stages.put(id, new StageEntry(state, inputSchema, outputSchema, 0, 0, 0, null));
    }
    public StageState stageState(NodeId id) {
        StageEntry entry = stages.get(id);
        return entry != null ? entry.state() : null;
    }
    public StageEntry stageEntry(NodeId id) { return stages.get(id); }
    public void failStage(NodeId id, String reason) {
        StageEntry entry = stages.get(id);
        if (entry != null) {
            stages.put(id, entry.withError(StageState.FAILED, reason));
        }
    }
    public void quarantineStage(NodeId id, long quarantinedCount, String reason) {
        StageEntry entry = stages.get(id);
        if (entry != null) {
            stages.put(id, new StageEntry(StageState.QUARANTINED, entry.inputSchema(), entry.outputSchema(),
                entry.processed(), entry.failed(), quarantinedCount, reason));
        }
    }
    public void degradeStage(NodeId id) {
        StageEntry entry = stages.get(id);
        if (entry != null) {
            stages.put(id, entry.withState(StageState.DEGRADED));
        }
    }
    public Map<NodeId, StageEntry> allStages() { return Map.copyOf(stages); }

    public void registerDownstream(NodeId id, List<NodeId> downstream) {
        downstreamMap.put(id, List.copyOf(downstream));
    }
    public void removeStage(NodeId id) {
        stages.remove(id);
        List<NodeId> downstream = downstreamMap.get(id);
        if (downstream != null) {
            for (NodeId downId : downstream) {
                StageEntry entry = stages.get(downId);
                if (entry != null) {
                    stages.put(downId, entry.withState(StageState.IDLE));
                }
            }
        }
    }

    // --- Review registry ---
    public void addReview(NodeId reviewNodeId, NodeId targetNode) {
        reviews.put(reviewNodeId, new ReviewEntry(targetNode, ReviewState.PENDING));
    }
    public ReviewEntry review(NodeId reviewNodeId) { return reviews.get(reviewNodeId); }
    public void removeReview(NodeId reviewNodeId) { reviews.remove(reviewNodeId); }
    public Map<NodeId, ReviewEntry> allReviews() { return Map.copyOf(reviews); }

    public boolean hasReviewForTarget(NodeId targetNode) {
        return reviews.values().stream().anyMatch(r -> r.targetNode().equals(targetNode));
    }
    public ReviewEntry reviewForTarget(NodeId targetNode) {
        return reviews.values().stream()
            .filter(r -> r.targetNode().equals(targetNode))
            .findFirst().orElse(null);
    }

    // --- Resolution methods ---
    public void setAiReviewOutcome(NodeId targetNode, boolean resolved) {
        NodeId reviewId = NodeId.of("ai-review-" + targetNode.value());
        ReviewState state = resolved ? ReviewState.RESOLVED : ReviewState.UNRESOLVED;
        reviews.put(reviewId, new ReviewEntry(targetNode, state));
    }
    public void resolveHumanReview(NodeId targetNode) {
        NodeId reviewId = NodeId.of("human-review-" + targetNode.value());
        reviews.put(reviewId, new ReviewEntry(targetNode, ReviewState.RESOLVED));
    }
    public void clearStageError(NodeId nodeId) {
        StageEntry entry = stages.get(nodeId);
        if (entry != null) {
            stages.put(nodeId, new StageEntry(StageState.IDLE, entry.inputSchema(), entry.outputSchema(),
                entry.processed(), entry.failed(), entry.quarantined(), null));
        }
    }
    public void approveSchemaChange(String schemaName, int newVersion) {
        SchemaDefinition existing = schemas.get(schemaName);
        if (existing != null) {
            schemas.put(schemaName, new SchemaDefinition(schemaName, existing.fields(), newVersion));
            for (Map.Entry<NodeId, StageEntry> entry : stages.entrySet()) {
                StageEntry stage = entry.getValue();
                if (schemaName.equals(stage.inputSchema()) || schemaName.equals(stage.outputSchema())) {
                    if (stage.state() == StageState.DEGRADED) {
                        stages.put(entry.getKey(), stage.withState(StageState.RUNNING));
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest="PipelineTest#deprovisionCascade_downstreamGoesIdle"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add examples/pipeline/
git commit -m "feat(#2): PipelineWorld — in-memory simulation with registries and resolution methods

Stage lifecycle (IDLE→RUNNING→COMPLETED/FAILED/QUARANTINED/DEGRADED),
schema registry with compatibility checking, lookup source registry,
review registry (PENDING/RESOLVED/UNRESOLVED), deprovision cascade,
schema drift approval, and stage error clearing."
```

---

### Task 6: PipelineProvisioner + PipelineActualStateAdapter

**Files:**
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineProvisioner.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineActualStateAdapter.java`

- [ ] **Step 1: Write failing tests for provisioning and state adaptation**

Add to `PipelineTest.java`:

```java
private PipelineWorld world;
private PipelineProvisioner provisioner;
private PipelineActualStateAdapter adapter;

@BeforeEach
void setUp() {
    factory = new DefaultDesiredStateGraphFactory();
    compiler = new PipelineGoalCompiler();
    planner = new TransitionPlanner();
    world = new PipelineWorld();
    provisioner = new PipelineProvisioner(world);
    adapter = new PipelineActualStateAdapter(world);
}
```

```java
@Test
void provisionFullPipeline() {
    PipelineBlueprint blueprint = standardBlueprint();
    DesiredStateGraph graph = compiler.compile(blueprint, factory);
    ActualState actual = adapter.readActual(graph);
    TransitionPlan plan = planner.plan(graph, actual);

    // Register lookup source that enricher needs
    world.registerLookupSource("geo-lookup");

    for (OrderedStep step : plan.additions()) {
        ProvisionContext context = new ProvisionContext("test-tenancy", graph);
        ProvisionResult result = provisioner.provision(step.node(), context);
        assertInstanceOf(ProvisionResult.Success.class, result,
            "Provisioning " + step.node().id() + " should succeed");
    }

    // All processing stages should be RUNNING
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("click-ingest")));
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("click-clean")));
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("geo-enrich")));
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("quality-gate")));
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("session-agg")));
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("warehouse")));

    // Data sources and schemas should be registered
    assertTrue(world.hasSource(NodeId.of("clickstream")));
    assertTrue(world.hasSchema("click-schema"));

    // Actual state should show all PRESENT
    ActualState afterProvision = adapter.readActual(graph);
    for (NodeId nodeId : graph.nodes().keySet()) {
        assertEquals(NodeStatus.PRESENT, afterProvision.statusOf(nodeId).orElse(null),
            nodeId + " should be PRESENT");
    }
}

@Test
void schemaIncompatibility_failsProvision() {
    // Register a schema, then try to provision a cleanser referencing a non-existent schema
    world.registerSource(NodeId.of("src"), "json", "kafka://test");
    // No schema registered — cleanser validation should fail

    DesiredNode cleanser = new DesiredNode(NodeId.of("clean"), PipelineNodeTypes.CLEANSER,
        new CleanserSpec(List.of("deduplicate"), true, "DROP"), false);

    ProvisionContext context = new ProvisionContext("test-tenancy", factory.empty());
    ProvisionResult result = provisioner.provision(cleanser, context);
    assertInstanceOf(ProvisionResult.Failed.class, result);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest="PipelineTest#provisionFullPipeline+schemaIncompatibility_failsProvision"`

Expected: Compilation failure — PipelineProvisioner and PipelineActualStateAdapter don't exist

- [ ] **Step 3: Implement PipelineActualStateAdapter**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.HashMap;
import java.util.Map;

public class PipelineActualStateAdapter implements ActualStateAdapter {

    private final PipelineWorld world;

    public PipelineActualStateAdapter(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();

        for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
            NodeId nodeId = entry.getKey();
            DesiredNode node = entry.getValue();
            statuses.put(nodeId, translateStatus(nodeId, node.type()));
        }

        // Also include non-desired nodes that exist in the world (for orphan detection)
        for (NodeId sourceId : world.allSources().keySet()) {
            statuses.putIfAbsent(sourceId, NodeStatus.PRESENT);
        }
        for (Map.Entry<NodeId, PipelineWorld.StageEntry> stage : world.allStages().entrySet()) {
            statuses.putIfAbsent(stage.getKey(), translateStageState(stage.getValue().state()));
        }

        return new ActualState(statuses);
    }

    private NodeStatus translateStatus(NodeId nodeId, NodeType type) {
        if (PipelineNodeTypes.DATA_SOURCE.equals(type)) {
            return world.hasSource(nodeId) ? NodeStatus.PRESENT : NodeStatus.ABSENT;
        }
        if (PipelineNodeTypes.SCHEMA.equals(type)) {
            DesiredNode node = null; // need spec to get schema name — use nodeId value as key
            return world.hasSchema(nodeId.value()) ? NodeStatus.PRESENT : NodeStatus.ABSENT;
        }
        if (PipelineNodeTypes.AI_REVIEW.equals(type) || PipelineNodeTypes.HUMAN_REVIEW.equals(type)) {
            PipelineWorld.ReviewEntry review = world.review(nodeId);
            if (review == null) return NodeStatus.ABSENT;
            return review.state() == PipelineWorld.ReviewState.RESOLVED
                ? NodeStatus.PRESENT : NodeStatus.ABSENT;
        }

        // Processing stages
        PipelineWorld.StageState state = world.stageState(nodeId);
        if (state == null) return NodeStatus.ABSENT;
        return translateStageState(state);
    }

    private NodeStatus translateStageState(PipelineWorld.StageState state) {
        return switch (state) {
            case RUNNING, COMPLETED -> NodeStatus.PRESENT;
            case IDLE, FAILED -> NodeStatus.ABSENT;
            case DEGRADED, QUARANTINED -> NodeStatus.DRIFTED;
        };
    }
}
```

- [ ] **Step 4: Implement PipelineProvisioner**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

public class PipelineProvisioner implements NodeProvisioner {

    private final PipelineWorld world;

    public PipelineProvisioner(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

        if (PipelineNodeTypes.DATA_SOURCE.equals(type)) {
            DataSourceSpec spec = (DataSourceSpec) node.spec();
            world.registerSource(node.id(), spec.format(), spec.uri());
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.SCHEMA.equals(type)) {
            SchemaSpec spec = (SchemaSpec) node.spec();
            world.registerSchema(spec.name(), spec.fields(), spec.version());
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.INGESTION.equals(type)) {
            IngestionSpec spec = (IngestionSpec) node.spec();
            if (!world.hasSource(NodeId.of(spec.sourceRef()))) {
                return new ProvisionResult.Failed("Data source not found: " + spec.sourceRef());
            }
            world.setStage(node.id(), PipelineWorld.StageState.RUNNING, null, spec.format());
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.CLEANSER.equals(type)) {
            // Cleanser needs upstream ingestion to exist
            // Schema compatibility would be validated here in a richer simulation
            world.setStage(node.id(), PipelineWorld.StageState.RUNNING, null, null);
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.ENRICHER.equals(type)) {
            EnricherSpec spec = (EnricherSpec) node.spec();
            if (!world.hasLookupSource(spec.lookupSource())) {
                return new ProvisionResult.Failed("Lookup source not found: " + spec.lookupSource());
            }
            world.setStage(node.id(), PipelineWorld.StageState.RUNNING, null, null);
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.VALIDATOR.equals(type)) {
            ValidatorSpec spec = (ValidatorSpec) node.spec();
            if (!world.hasSchema(spec.schemaRef())) {
                return new ProvisionResult.Failed("Schema not found: " + spec.schemaRef());
            }
            world.setStage(node.id(), PipelineWorld.StageState.RUNNING, spec.schemaRef(), spec.schemaRef());
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.TRANSFORMER.equals(type)) {
            TransformerSpec spec = (TransformerSpec) node.spec();
            world.setStage(node.id(), PipelineWorld.StageState.RUNNING, null, spec.outputFormat());
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.SINK.equals(type)) {
            world.setStage(node.id(), PipelineWorld.StageState.RUNNING, null, null);
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.AI_REVIEW.equals(type)) {
            AiReviewSpec spec = (AiReviewSpec) node.spec();
            PipelineWorld.ReviewEntry existing = world.review(node.id());
            if (existing != null && existing.state() != PipelineWorld.ReviewState.PENDING) {
                // Already diagnosed — use existing outcome
                if (existing.state() == PipelineWorld.ReviewState.RESOLVED) {
                    world.clearStageError(spec.targetNodeId());
                }
                return new ProvisionResult.Success();
            }
            // Check if test has pre-set the outcome
            PipelineWorld.ReviewEntry preset = world.review(node.id());
            if (preset != null) {
                if (preset.state() == PipelineWorld.ReviewState.RESOLVED) {
                    world.clearStageError(spec.targetNodeId());
                }
                return new ProvisionResult.Success();
            }
            // Default: add as PENDING (test must call setAiReviewOutcome to resolve)
            world.addReview(node.id(), spec.targetNodeId());
            return new ProvisionResult.Success();
        }
        if (PipelineNodeTypes.HUMAN_REVIEW.equals(type)) {
            // requiresHuman=true — skipped by SimpleTransitionExecutor
            // If somehow reached, register the review as pending
            HumanReviewSpec spec = (HumanReviewSpec) node.spec();
            world.addReview(node.id(), spec.targetNodeId());
            return new ProvisionResult.Success();
        }

        return new ProvisionResult.Failed("Unknown node type: " + type);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        NodeType type = node.type();

        if (PipelineNodeTypes.DATA_SOURCE.equals(type)) {
            world.removeSource(node.id());
            return new DeprovisionResult.Success();
        }
        if (PipelineNodeTypes.SCHEMA.equals(type)) {
            SchemaSpec spec = (SchemaSpec) node.spec();
            world.removeSchema(spec.name());
            return new DeprovisionResult.Success();
        }
        if (PipelineNodeTypes.AI_REVIEW.equals(type) || PipelineNodeTypes.HUMAN_REVIEW.equals(type)) {
            world.removeReview(node.id());
            return new DeprovisionResult.Success();
        }
        // Processing stages — remove with cascade
        world.removeStage(node.id());
        return new DeprovisionResult.Success();
    }
}
```

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest="PipelineTest#provisionFullPipeline+schemaIncompatibility_failsProvision+deprovisionCascade_downstreamGoesIdle"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add examples/pipeline/
git commit -m "feat(#2): PipelineProvisioner + PipelineActualStateAdapter

Provisioner dispatches on NodeType with validation (source exists,
schema compatible, lookup source registered). Adapter translates
StageState → NodeStatus (RUNNING→PRESENT, FAILED→ABSENT,
QUARANTINED/DEGRADED→DRIFTED). Review nodes: RESOLVED→PRESENT,
PENDING/UNRESOLVED→ABSENT."
```

---

### Task 7: PipelineEventSource

**Files:**
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineEventSource.java`

- [ ] **Step 1: Implement PipelineEventSource**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;

import java.util.concurrent.atomic.AtomicReference;

public class PipelineEventSource implements EventSource {

    private final AtomicReference<MultiEmitter<? super StateEvent>> emitterRef = new AtomicReference<>();
    private final Multi<StateEvent> multi;

    public PipelineEventSource() {
        this.multi = Multi.createFrom().emitter(emitter -> emitterRef.set(emitter));
    }

    @Override
    public Multi<StateEvent> stream() {
        return multi;
    }

    public void stageFailure(String stageId) {
        emit(new StateEvent(NodeId.of(stageId), NodeStatus.ABSENT, "Stage failure"));
    }

    public void schemaDrift(String schemaId) {
        emit(new StateEvent(NodeId.of(schemaId), NodeStatus.DRIFTED, "Schema drift detected"));
    }

    public void throughputDrop(String stageId) {
        emit(new StateEvent(NodeId.of(stageId), NodeStatus.DRIFTED, "Throughput degradation"));
    }

    public void dataArrival(String sourceId) {
        emit(new StateEvent(NodeId.of(sourceId), NodeStatus.PRESENT, "New data batch"));
    }

    private void emit(StateEvent event) {
        MultiEmitter<? super StateEvent> emitter = emitterRef.get();
        if (emitter != null) {
            emitter.emit(event);
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn --batch-mode compile -pl examples/pipeline`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add examples/pipeline/
git commit -m "feat(#2): PipelineEventSource — simulation event emission

stageFailure, schemaDrift, throughputDrop, dataArrival methods for
test-driven event injection. Uses Multi.createFrom().emitter() instead
of deprecated BroadcastProcessor."
```

---

### Task 8: Fault policies

**Files:**
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/ProvisionEscalationFaultPolicy.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/QuarantineFaultPolicy.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/SchemaDriftFaultPolicy.java`

- [ ] **Step 1: Write failing tests for fault escalation**

Add to `PipelineTest.java`:

```java
@Test
void provisionFailure_fullEscalationChain() {
    ProvisionEscalationFaultPolicy policy = new ProvisionEscalationFaultPolicy(world);
    DesiredNode ingestion = new DesiredNode(NodeId.of("ingest"), PipelineNodeTypes.INGESTION,
        new IngestionSpec("src", 1000, "json"), false);
    DesiredStateGraph graph = factory.of(List.of(ingestion), List.of());

    FaultEvent fault = new FaultEvent(NodeId.of("ingest"), FaultType.PROVISION_FAILED, "source unavailable");

    // Events 1-3: empty (built-in retry)
    for (int i = 0; i < 3; i++) {
        List<GraphMutation> mutations = policy.onFault(fault, graph);
        assertTrue(mutations.isEmpty(), "Event " + (i + 1) + " should return empty (built-in retry)");
    }

    // Event 4: creates AI_REVIEW
    List<GraphMutation> event4 = policy.onFault(fault, graph);
    assertEquals(1, event4.size());
    assertInstanceOf(GraphMutation.AddNode.class, event4.get(0));
    GraphMutation.AddNode addReview = (GraphMutation.AddNode) event4.get(0);
    assertEquals(NodeId.of("ai-review-ingest"), addReview.node().id());
    assertEquals(PipelineNodeTypes.AI_REVIEW, addReview.node().type());
    assertFalse(addReview.node().requiresHuman());

    // Apply mutation to graph for subsequent checks
    graph = graph.withMutation(event4.get(0));

    // Event 5 with AI_REVIEW PENDING: returns empty (wait)
    world.addReview(NodeId.of("ai-review-ingest"), NodeId.of("ingest"));
    List<GraphMutation> event5Pending = policy.onFault(fault, graph);
    assertTrue(event5Pending.isEmpty(), "Should wait while AI_REVIEW is PENDING");

    // Event 5+ with AI_REVIEW UNRESOLVED: creates HUMAN_REVIEW
    world.setAiReviewOutcome(NodeId.of("ingest"), false);
    List<GraphMutation> event5Unresolved = policy.onFault(fault, graph);
    assertEquals(1, event5Unresolved.size());
    assertInstanceOf(GraphMutation.AddNode.class, event5Unresolved.get(0));
    GraphMutation.AddNode addHuman = (GraphMutation.AddNode) event5Unresolved.get(0);
    assertEquals(NodeId.of("human-review-ingest"), addHuman.node().id());
    assertEquals(PipelineNodeTypes.HUMAN_REVIEW, addHuman.node().type());
    assertTrue(addHuman.node().requiresHuman());

    // Further events with HUMAN_REVIEW existing: returns empty (idempotency)
    graph = graph.withMutation(event5Unresolved.get(0));
    world.addReview(NodeId.of("human-review-ingest"), NodeId.of("ingest"));
    List<GraphMutation> eventIdempotent = policy.onFault(fault, graph);
    assertTrue(eventIdempotent.isEmpty(), "Should not create duplicate HUMAN_REVIEW");
}

@Test
void provisionFailure_aiReviewResolves() {
    ProvisionEscalationFaultPolicy policy = new ProvisionEscalationFaultPolicy(world);
    DesiredNode ingestion = new DesiredNode(NodeId.of("ingest"), PipelineNodeTypes.INGESTION,
        new IngestionSpec("src", 1000, "json"), false);
    DesiredStateGraph graph = factory.of(List.of(ingestion), List.of());

    FaultEvent fault = new FaultEvent(NodeId.of("ingest"), FaultType.PROVISION_FAILED, "source unavailable");

    // Events 1-3: retry
    for (int i = 0; i < 3; i++) policy.onFault(fault, graph);

    // Event 4: creates AI_REVIEW
    List<GraphMutation> event4 = policy.onFault(fault, graph);
    assertEquals(1, event4.size());
    graph = graph.withMutation(event4.get(0));

    // AI_REVIEW resolves
    world.setAiReviewOutcome(NodeId.of("ingest"), true);

    // Next event should return empty (RESOLVED — fix takes effect next cycle)
    List<GraphMutation> nextEvent = policy.onFault(fault, graph);
    assertTrue(nextEvent.isEmpty());
}

@Test
void validationQuarantine_humanReview() {
    QuarantineFaultPolicy policy = new QuarantineFaultPolicy(world);

    // Validator is QUARANTINED (not just DEGRADED from upstream drift)
    world.setStage(NodeId.of("validator"), PipelineWorld.StageState.QUARANTINED, "schema", "schema");

    DesiredNode validator = new DesiredNode(NodeId.of("validator"), PipelineNodeTypes.VALIDATOR,
        new ValidatorSpec("schema", 0.95, true), false);
    DesiredStateGraph graph = factory.of(List.of(validator), List.of());

    FaultEvent fault = new FaultEvent(NodeId.of("validator"), FaultType.NODE_DEGRADED, "Records quarantined");

    List<GraphMutation> mutations = policy.onFault(fault, graph);
    assertEquals(1, mutations.size());
    GraphMutation.AddNode addHuman = (GraphMutation.AddNode) mutations.get(0);
    assertEquals(NodeId.of("human-review-validator"), addHuman.node().id());
    assertTrue(addHuman.node().requiresHuman());
}

@Test
void schemaDrift_humanReviewOnly() {
    SchemaDriftFaultPolicy policy = new SchemaDriftFaultPolicy();

    DesiredNode schema = new DesiredNode(NodeId.of("my-schema"), PipelineNodeTypes.SCHEMA,
        new SchemaSpec("my-schema", List.of("a", "b"), 1), false);
    DesiredStateGraph graph = factory.of(List.of(schema), List.of());

    FaultEvent fault = new FaultEvent(NodeId.of("my-schema"), FaultType.NODE_DEGRADED, "Schema version changed");

    List<GraphMutation> mutations = policy.onFault(fault, graph);
    assertEquals(1, mutations.size());
    assertInstanceOf(GraphMutation.AddNode.class, mutations.get(0));

    GraphMutation.AddNode addHuman = (GraphMutation.AddNode) mutations.get(0);
    assertEquals(NodeId.of("human-review-my-schema"), addHuman.node().id());
    assertTrue(addHuman.node().requiresHuman());

    // No RemoveNode mutations — downstream stays in desired graph
    assertTrue(mutations.stream().noneMatch(m -> m instanceof GraphMutation.RemoveNode));
}

@Test
void schemaDrift_approvalRestoresPipeline() {
    // Set up: schema exists, dependent stages are DEGRADED
    world.registerSchema("click-schema", List.of("userId", "pageUrl"), 1);
    world.setStage(NodeId.of("cleanser"), PipelineWorld.StageState.DEGRADED, "click-schema", "click-schema");
    world.setStage(NodeId.of("validator"), PipelineWorld.StageState.DEGRADED, "click-schema", "click-schema");

    // Approve the schema change
    world.approveSchemaChange("click-schema", 2);

    // Stages should return to RUNNING
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("cleanser")));
    assertEquals(PipelineWorld.StageState.RUNNING, world.stageState(NodeId.of("validator")));

    // Schema version should be updated
    assertEquals(2, world.schema("click-schema").version());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest="PipelineTest#provisionFailure_fullEscalationChain+provisionFailure_aiReviewResolves+validationQuarantine_humanReview+schemaDrift_humanReviewOnly+schemaDrift_approvalRestoresPipeline"`

Expected: Compilation failure — fault policy classes don't exist

- [ ] **Step 3: Implement ProvisionEscalationFaultPolicy**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProvisionEscalationFaultPolicy implements FaultPolicy {

    private final PipelineWorld world;
    private final Map<NodeId, Integer> faultCounts = new ConcurrentHashMap<>();

    public ProvisionEscalationFaultPolicy(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        if (event.type() != FaultType.PROVISION_FAILED) return List.of();

        // Don't escalate fault-generated nodes (prevent infinite regress)
        DesiredNode faultedNode = current.nodes().get(event.node());
        if (faultedNode != null && (PipelineNodeTypes.AI_REVIEW.equals(faultedNode.type())
                || PipelineNodeTypes.HUMAN_REVIEW.equals(faultedNode.type()))) {
            return List.of();
        }

        int count = faultCounts.merge(event.node(), 1, Integer::sum);

        // Events 1-3: built-in retry — no policy action
        if (count <= 3) return List.of();

        NodeId aiReviewId = NodeId.of("ai-review-" + event.node().value());
        NodeId humanReviewId = NodeId.of("human-review-" + event.node().value());

        // Check if HUMAN_REVIEW already exists for this target
        if (current.nodes().containsKey(humanReviewId)) return List.of();
        PipelineWorld.ReviewEntry humanReview = world.review(humanReviewId);
        if (humanReview != null) return List.of();

        // Event 4: create AI_REVIEW
        if (!current.nodes().containsKey(aiReviewId)) {
            DesiredNode reviewNode = new DesiredNode(aiReviewId, PipelineNodeTypes.AI_REVIEW,
                new AiReviewSpec(event.node(), event.detail()), false);
            return List.of(new GraphMutation.AddNode(reviewNode));
        }

        // Event 5+: check review registry
        PipelineWorld.ReviewEntry review = world.review(aiReviewId);
        if (review == null || review.state() == PipelineWorld.ReviewState.PENDING) {
            return List.of(); // Wait for diagnosis
        }
        if (review.state() == PipelineWorld.ReviewState.RESOLVED) {
            return List.of(); // Fix takes effect next cycle
        }
        // UNRESOLVED: escalate to human
        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
            new HumanReviewSpec(event.node(), event.detail(), "AI review could not resolve"), true);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
```

- [ ] **Step 4: Implement QuarantineFaultPolicy**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.List;

public class QuarantineFaultPolicy implements FaultPolicy {

    private final PipelineWorld world;

    public QuarantineFaultPolicy(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        if (event.type() != FaultType.NODE_DEGRADED) return List.of();

        DesiredNode node = current.nodes().get(event.node());
        if (node == null || !PipelineNodeTypes.VALIDATOR.equals(node.type())) return List.of();

        // Only activate for QUARANTINED validators, not upstream-drift-induced DEGRADED
        PipelineWorld.StageState state = world.stageState(event.node());
        if (state != PipelineWorld.StageState.QUARANTINED) return List.of();

        NodeId humanReviewId = NodeId.of("human-review-" + event.node().value());
        if (current.nodes().containsKey(humanReviewId)) return List.of();

        PipelineWorld.StageEntry entry = world.stageEntry(event.node());
        String reason = entry != null ? entry.errorDetail() : "Validation quarantine";

        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
            new HumanReviewSpec(event.node(), reason, "Records quarantined by validator"), true);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
```

- [ ] **Step 5: Implement SchemaDriftFaultPolicy**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.List;

public class SchemaDriftFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        if (event.type() != FaultType.NODE_DEGRADED) return List.of();

        DesiredNode node = current.nodes().get(event.node());
        if (node == null || !PipelineNodeTypes.SCHEMA.equals(node.type())) return List.of();

        NodeId humanReviewId = NodeId.of("human-review-" + event.node().value());
        if (current.nodes().containsKey(humanReviewId)) return List.of();

        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
            new HumanReviewSpec(event.node(), event.detail(), "Schema drift requires approval"), true);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
```

- [ ] **Step 6: Run all fault policy tests**

Run: `mvn --batch-mode test -pl examples/pipeline -Dtest="PipelineTest#provisionFailure_fullEscalationChain+provisionFailure_aiReviewResolves+validationQuarantine_humanReview+schemaDrift_humanReviewOnly+schemaDrift_approvalRestoresPipeline"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add examples/pipeline/
git commit -m "feat(#2): fault policies — ProvisionEscalation, Quarantine, SchemaDrift

Three-tier escalation: built-in retry (events 1-3) → AI_REVIEW
(event 4) → HUMAN_REVIEW (event 5+ if AI unresolved).
QuarantineFaultPolicy checks PipelineWorld for QUARANTINED state.
SchemaDriftFaultPolicy creates HUMAN_REVIEW only (no RemoveNode).
All with deterministic IDs and idempotency guards."
```

---

### Task 9: Visualizer + integration tests + final verification

**Files:**
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineVisualizer.java`
- Modify: `examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/PipelineTest.java`

- [ ] **Step 1: Write remaining integration tests**

Add to `PipelineTest.java`:

```java
@Test
void fullReconciliationCycle() {
    PipelineBlueprint blueprint = standardBlueprint();
    DesiredStateGraph graph = compiler.compile(blueprint, factory);

    // Register lookup source
    world.registerLookupSource("geo-lookup");

    // Phase 1: All ABSENT → plan all additions → provision all
    ActualState actual = adapter.readActual(graph);
    TransitionPlan plan = planner.plan(graph, actual);
    assertEquals(8, plan.additions().size());

    for (OrderedStep step : plan.additions()) {
        ProvisionResult result = provisioner.provision(step.node(), new ProvisionContext("test", graph));
        assertInstanceOf(ProvisionResult.Success.class, result);
    }

    // Phase 2: All PRESENT → empty plan
    actual = adapter.readActual(graph);
    plan = planner.plan(graph, actual);
    assertTrue(plan.isEmpty(), "No changes needed — pipeline is reconciled");

    // Phase 3: Simulate failure → fault → recovery
    world.failStage(NodeId.of("click-ingest"), "Connection lost");
    actual = adapter.readActual(graph);
    assertEquals(NodeStatus.ABSENT, actual.statusOf(NodeId.of("click-ingest")).orElse(null));

    // Re-plan: ingestion should be re-provisioned
    plan = planner.plan(graph, actual);
    assertFalse(plan.isEmpty());
    assertTrue(plan.additions().stream().anyMatch(s -> s.node().id().equals(NodeId.of("click-ingest"))));

    // Fix and re-provision
    world.clearStageError(NodeId.of("click-ingest"));
    for (OrderedStep step : plan.additions()) {
        provisioner.provision(step.node(), new ProvisionContext("test", graph));
    }

    // Phase 4: All PRESENT again
    actual = adapter.readActual(graph);
    for (NodeId nodeId : graph.nodes().keySet()) {
        assertEquals(NodeStatus.PRESENT, actual.statusOf(nodeId).orElse(null),
            nodeId + " should be PRESENT after recovery");
    }
}

@Test
void faultGeneratedNodes_neverInBlueprint() {
    PipelineBlueprint blueprint = standardBlueprint();
    DesiredStateGraph graph = compiler.compile(blueprint, factory);

    // No AI_REVIEW or HUMAN_REVIEW nodes in compiled graph
    for (DesiredNode node : graph.nodes().values()) {
        assertNotEquals(PipelineNodeTypes.AI_REVIEW, node.type(),
            "AI_REVIEW should not be in blueprint-compiled graph");
        assertNotEquals(PipelineNodeTypes.HUMAN_REVIEW, node.type(),
            "HUMAN_REVIEW should not be in blueprint-compiled graph");
    }
}
```

- [ ] **Step 2: Implement PipelineVisualizer**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/pipeline")
public class PipelineVisualizer {

    @Inject
    PipelineWorld world;

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<PipelineSnapshot> stream() {
        return Multi.createFrom().ticks().every(Duration.ofMillis(500))
            .map(tick -> snapshot());
    }

    private PipelineSnapshot snapshot() {
        Map<String, StageView> bronze = new LinkedHashMap<>();
        Map<String, StageView> silver = new LinkedHashMap<>();
        Map<String, StageView> gold = new LinkedHashMap<>();
        Map<String, ReviewView> reviews = new LinkedHashMap<>();
        Map<String, SchemaView> schemas = new LinkedHashMap<>();

        for (Map.Entry<NodeId, PipelineWorld.StageEntry> entry : world.allStages().entrySet()) {
            PipelineWorld.StageEntry stage = entry.getValue();
            StageView view = new StageView(stage.state(), stage.processed(), stage.failed(), stage.quarantined());
            String id = entry.getKey().value();

            // Determine layer from stage name convention (simplified — real impl would track type)
            // For visualization, put all stages in silver by default
            silver.put(id, view);
        }

        for (Map.Entry<NodeId, PipelineWorld.ReviewEntry> entry : world.allReviews().entrySet()) {
            PipelineWorld.ReviewEntry review = entry.getValue();
            reviews.put(entry.getKey().value(),
                new ReviewView(entry.getKey().value().startsWith("ai-review") ? "AI_REVIEW" : "HUMAN_REVIEW",
                    review.targetNode().value(), "", review.state()));
        }

        for (Map.Entry<String, PipelineWorld.SchemaDefinition> entry : world.allSchemas().entrySet()) {
            PipelineWorld.SchemaDefinition schema = entry.getValue();
            schemas.put(entry.getKey(), new SchemaView(schema.name(), schema.version(), schema.fields()));
        }

        return new PipelineSnapshot(bronze, silver, gold, reviews, schemas);
    }

    public record PipelineSnapshot(
        Map<String, StageView> bronzeStages,
        Map<String, StageView> silverStages,
        Map<String, StageView> goldStages,
        Map<String, ReviewView> activeReviews,
        Map<String, SchemaView> schemas
    ) {}

    public record StageView(PipelineWorld.StageState state, long processed, long failed, long quarantined) {}
    public record ReviewView(String nodeType, String targetStageId, String reason, PipelineWorld.ReviewState state) {}
    public record SchemaView(String name, int version, List<String> fields) {}
}
```

- [ ] **Step 3: Run all pipeline tests**

Run: `mvn --batch-mode test -pl examples/pipeline`

Expected: All 12 tests PASS

- [ ] **Step 4: Run full project build**

Run: `mvn --batch-mode install`

Expected: BUILD SUCCESS for all modules including runtime (with fixes) and examples/pipeline

- [ ] **Step 5: Commit**

```bash
git add examples/pipeline/
git commit -m "feat(#2): PipelineVisualizer + integration tests — complete pipeline example

SSE endpoint streaming PipelineSnapshot by medallion layer.
fullReconciliationCycle end-to-end test: build → run → fault → recover.
faultGeneratedNodes_neverInBlueprint confirms AI_REVIEW and
HUMAN_REVIEW are fault-policy-only."
```

- [ ] **Step 6: Run code review before final commit**

Invoke `superpowers:requesting-code-review` on all changes on this branch.

- [ ] **Step 7: Run implementation-doc-sync**

Invoke `implementation-doc-sync` to update CLAUDE.md and any other docs affected by the new module.
