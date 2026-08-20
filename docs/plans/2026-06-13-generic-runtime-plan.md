# Generic Desired-State Runtime — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the domain-agnostic desired-state management runtime — graph model, planner, reconciliation loop, fault policy engine, core SPIs, engine adapter, and Nefarious Dungeons example.

**Architecture:** Five modules. `api/` defines SPIs and value types (pure Java). `runtime/` provides the default graph implementation, TransitionPlanner, ReconciliationLoop, FaultPolicyEngine, and SimpleTransitionExecutor. `engine-adapter/` bridges to casehub-engine via CaseTransitionExecutor with generated Worker(Workflow) phases. `testing/` provides mock SPIs. `examples/dungeon/` is a teaching example with a 2D tile visualizer.

**Tech Stack:** Java 21, Quarkus 3.32.2, Mutiny, casehub-platform-api, casehub-engine-api/common/flow, casehub-work-api, Vert.x timers, SSE.

**Spec:** `docs/specs/2026-06-12-generic-runtime-design.md`

---

## File Map

### api/src/main/java/io/casehub/desiredstate/api/

| File | Responsibility |
|------|---------------|
| `NodeSpec.java` | Marker interface for domain-specific node specifications |
| `NodeId.java` | Value type — node identifier |
| `NodeType.java` | Value type — node type classifier |
| `Dependency.java` | Value type — directed edge (from depends on to) |
| `DesiredNode.java` | Value type — node with id, type, spec, requiresHuman flag |
| `DesiredStateGraph.java` | SPI interface — immutable versioned DAG with query and mutation methods |
| `DesiredStateGraphFactory.java` | SPI interface — creates graph instances |
| `GraphMutation.java` | Sealed interface — AddNode, RemoveNode, UpdateNode, AddDependency, RemoveDependency |
| `OrderedStep.java` | Value type — a node + action (PROVISION/DEPROVISION) |
| `StepAction.java` | Enum — PROVISION, DEPROVISION |
| `TransitionPlan.java` | Value type — ordered removals + additions with before/after graphs |
| `TransitionResult.java` | Value type — per-node outcomes map |
| `StepOutcome.java` | Sealed interface — Succeeded, Failed, Skipped |
| `ActualState.java` | Value type — map of node statuses |
| `NodeStatus.java` | Enum — PRESENT, ABSENT, DEGRADED, UNKNOWN |
| `ReconciliationResult.java` | Value type — resolved, drifted, faulted sets + mutations |
| `FaultEvent.java` | Value type — node + fault type + detail |
| `FaultType.java` | Enum — NODE_DESTROYED, NODE_DEGRADED, PROVISION_FAILED, etc. |
| `StateEvent.java` | Value type — node + new status + detail |
| `ProvisionContext.java` | Value type — tenancyId + graph |
| `DeprovisionContext.java` | Value type — tenancyId + graph |
| `ProvisionResult.java` | Sealed interface — Success, Failed |
| `DeprovisionResult.java` | Sealed interface — Success, Failed |
| `GoalCompiler.java` | SPI — compiles goals into DesiredStateGraph |
| `ActualStateAdapter.java` | SPI — reads current actual state |
| `NodeProvisioner.java` | SPI — provisions/deprovisions a node (blocking) |
| `ReactiveNodeProvisioner.java` | SPI — provisions/deprovisions a node (reactive) |
| `FaultPolicy.java` | SPI — returns graph mutations for fault events |
| `EventSource.java` | SPI — streams state events |
| `TransitionExecutor.java` | SPI — executes a transition plan |
| `CyclicDependencyException.java` | Error — cycle detected in graph |
| `DanglingDependencyException.java` | Error — dependency references absent node |
| `ConflictingMutationException.java` | Error — incompatible fault policy mutations |

### runtime/src/main/java/io/casehub/desiredstate/runtime/

| File | Responsibility |
|------|---------------|
| `ImmutableDesiredStateGraph.java` | Default DesiredStateGraph — dual adjacency maps, Map.copyOf(), cycle detection |
| `DefaultDesiredStateGraphFactory.java` | @DefaultBean factory producing ImmutableDesiredStateGraph |
| `TransitionPlanner.java` | Diffs desired vs actual, produces TopologicallySorted TransitionPlan |
| `FaultPolicyEngine.java` | Runs all FaultPolicy beans, merges mutations, detects conflicts |
| `SimpleTransitionExecutor.java` | @DefaultBean — sequential NodeProvisioner calls, skips human nodes |
| `ReconciliationLoop.java` | Per-tenant loop — event-driven + periodic re-sync, debounced, fault feedback |

### testing/src/main/java/io/casehub/desiredstate/testing/

| File | Responsibility |
|------|---------------|
| `MockNodeProvisioner.java` | Records provision/deprovision calls, configurable outcomes |
| `MockActualStateAdapter.java` | Returns configurable ActualState |
| `CannedEventSource.java` | Controllable Multi\<StateEvent\> sink for tests |

### engine-adapter/src/main/java/io/casehub/desiredstate/engine/

| File | Responsibility |
|------|---------------|
| `CaseTransitionExecutor.java` | @ApplicationScoped — generates case with Worker(Workflow) phases |
| `DesiredStateWorkerFunction.java` | Bridge worker wrapping NodeProvisioner for casehub-engine dispatch |
| `TransitionWorkflowGenerator.java` | Generates Serverless Workflow definitions from ordered steps |

### examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/

| File | Responsibility |
|------|---------------|
| `DungeonRoomSpec.java` | NodeSpec for rooms |
| `CreatureSpec.java` | NodeSpec for creatures |
| `TrapSpec.java` | NodeSpec for traps |
| `DungeonNodeTypes.java` | NodeType constants |
| `DungeonBlueprint.java` | Goal declaration — rooms, creatures, traps with dependencies |
| `DungeonWorld.java` | Mutable in-memory simulation of dungeon reality |
| `DungeonGoalCompiler.java` | GoalCompiler\<DungeonBlueprint\> |
| `DungeonActualStateAdapter.java` | Reads from DungeonWorld |
| `GoblinProvisioner.java` | NodeProvisioner — builds/demolishes in DungeonWorld |
| `DungeonEventSource.java` | Controllable event stream — hero raids, cave-ins, revolts |
| `HeroRaidFaultPolicy.java` | FaultPolicy — rebuilds destroyed rooms |
| `DungeonVisualizer.java` | SSE endpoint streaming DungeonWorld state |

### examples/dungeon/src/main/resources/META-INF/resources/

| File | Responsibility |
|------|---------------|
| `index.html` | 2D tile grid — vanilla HTML/CSS/JS, SSE-driven |

---

## Task 1: Fix module dependencies and add new modules

**Files:**
- Modify: `pom.xml` (parent)
- Modify: `api/pom.xml`
- Modify: `runtime/pom.xml`
- Create: `engine-adapter/pom.xml`
- Create: `examples/dungeon/pom.xml`

- [ ] **Step 1: Remove casehub-work-api from api/pom.xml**

```xml
<!-- REMOVE this dependency block from api/pom.xml -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-work-api</artifactId>
</dependency>
```

- [ ] **Step 2: Remove casehub-engine-flow and casehub-work-api from runtime/pom.xml, fix quarkus-junit5 → quarkus-junit**

In `runtime/pom.xml`, remove:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-engine-flow</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-work-api</artifactId>
</dependency>
```

Change `quarkus-junit5` to `quarkus-junit`:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-junit</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Add engine-adapter and examples/dungeon dependencies to parent pom.xml dependencyManagement**

Add to `<dependencyManagement>` in parent `pom.xml`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-engine</artifactId>
    <version>${project.version}</version>
</dependency>

<!-- Engine — for engine-adapter module -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-engine-api</artifactId>
    <version>${version.io.casehub}</version>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-engine-common</artifactId>
    <version>${version.io.casehub}</version>
</dependency>
```

Add to `<modules>`:
```xml
<module>engine-adapter</module>
<module>examples/dungeon</module>
```

- [ ] **Step 4: Create engine-adapter/pom.xml**

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
    </parent>

    <artifactId>casehub-desiredstate-engine</artifactId>

    <name>CaseHub Desired State :: Engine Adapter</name>
    <description>Orchestration-tier bridge — CaseTransitionExecutor generates cases with
        Worker(Workflow) phases for prune/grow, executes via CaseHubRuntime.</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-desiredstate-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-engine-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-engine-common</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-engine-flow</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-work-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
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
            <artifactId>casehub-desiredstate-testing</artifactId>
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

- [ ] **Step 5: Create examples/dungeon/pom.xml**

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
    </parent>

    <artifactId>casehub-desiredstate-example-dungeon</artifactId>

    <name>CaseHub Desired State :: Example :: Nefarious Dungeons</name>
    <description>Teaching example — dungeon management domain implementing all SPIs.
        GoblinProvisioner, hero raid fault policies, 2D tile visualizer.</description>

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

- [ ] **Step 6: Create directory structures**

```bash
mkdir -p engine-adapter/src/main/java/io/casehub/desiredstate/engine
mkdir -p engine-adapter/src/test/java/io/casehub/desiredstate/engine
mkdir -p examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon
mkdir -p examples/dungeon/src/main/resources/META-INF/resources
mkdir -p examples/dungeon/src/test/java/io/casehub/desiredstate/example/dungeon
```

- [ ] **Step 7: Verify build compiles**

Run: `mvn --batch-mode compile -pl api,runtime,testing,engine-adapter,examples/dungeon`
Expected: BUILD SUCCESS (empty modules compile)

- [ ] **Step 8: Commit**

```bash
git add pom.xml api/pom.xml runtime/pom.xml engine-adapter/ examples/
git commit -m "chore(#1): fix module dependencies, add engine-adapter and dungeon example modules"
```

---

## Task 2: API — Core value types

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeSpec.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeId.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeType.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/Dependency.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/CoreTypesTest.java`

- [ ] **Step 1: Write failing tests for core value types**

```java
package io.casehub.desiredstate.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CoreTypesTest {

    record TestSpec(String name, int size) implements NodeSpec {}

    @Test
    void nodeId_equality() {
        var a = new NodeId("library");
        var b = new NodeId("library");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.value()).isEqualTo("library");
    }

    @Test
    void nodeType_equality() {
        var a = new NodeType("room");
        var b = new NodeType("room");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void dependency_semantics() {
        var from = new NodeId("creature");
        var to = new NodeId("room");
        var dep = new Dependency(from, to);
        assertThat(dep.from()).isEqualTo(from);
        assertThat(dep.to()).isEqualTo(to);
    }

    @Test
    void desiredNode_humanFlag() {
        var spec = new TestSpec("Library", 12);
        var node = new DesiredNode(new NodeId("library"), spec, false);
        assertThat(node.requiresHuman()).isFalse();

        var humanNode = new DesiredNode(new NodeId("dragon"), spec, true);
        assertThat(humanNode.requiresHuman()).isTrue();
    }

    @Test
    void nodeSpec_markerInterface() {
        NodeSpec spec = new TestSpec("Crypt", 10);
        assertThat(spec).isInstanceOf(NodeSpec.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl api -Dtest=CoreTypesTest`
Expected: FAIL — classes not found

- [ ] **Step 3: Implement core value types**

`NodeSpec.java`:
```java
package io.casehub.desiredstate.api;

public interface NodeSpec {}
```

`NodeId.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeId(String value) {
    public NodeId {
        Objects.requireNonNull(value, "NodeId value must not be null");
    }
}
```

`NodeType.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeType(String value) {
    public NodeType {
        Objects.requireNonNull(value, "NodeType value must not be null");
    }
}
```

`Dependency.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record Dependency(NodeId from, NodeId to) {
    public Dependency {
        Objects.requireNonNull(from, "Dependency.from must not be null");
        Objects.requireNonNull(to, "Dependency.to must not be null");
    }
}
```

`DesiredNode.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record DesiredNode(NodeId id, NodeType type, NodeSpec spec, boolean requiresHuman) {
    public DesiredNode {
        Objects.requireNonNull(id, "DesiredNode.id must not be null");
        Objects.requireNonNull(type, "DesiredNode.type must not be null");
        Objects.requireNonNull(spec, "DesiredNode.spec must not be null");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl api -Dtest=CoreTypesTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add api/src/
git commit -m "feat(#1): add core value types — NodeSpec, NodeId, NodeType, Dependency, DesiredNode"
```

---

## Task 3: API — Graph mutations, fault types, provisioner types

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/GraphMutation.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/FaultEvent.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/FaultType.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/StateEvent.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeStatus.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ActualState.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ProvisionContext.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DeprovisionContext.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ProvisionResult.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DeprovisionResult.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/StepAction.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/OrderedStep.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/StepOutcome.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/TransitionPlan.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/TransitionResult.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ReconciliationResult.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/TypesTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.desiredstate.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypesTest {

    record TestSpec(String name) implements NodeSpec {}

    @Test
    void graphMutation_sealedExhaustive() {
        var node = new DesiredNode(new NodeId("a"), new TestSpec("x"), false);
        GraphMutation mutation = new GraphMutation.AddNode(node);
        String result = switch (mutation) {
            case GraphMutation.AddNode m -> "add:" + m.node().id().value();
            case GraphMutation.RemoveNode m -> "remove:" + m.id().value();
            case GraphMutation.UpdateNode m -> "update:" + m.id().value();
            case GraphMutation.AddDependency m -> "addDep:" + m.dependency().from().value();
            case GraphMutation.RemoveDependency m -> "rmDep:" + m.dependency().from().value();
        };
        assertThat(result).isEqualTo("add:a");
    }

    @Test
    void provisionResult_sealed() {
        ProvisionResult success = new ProvisionResult.Success();
        ProvisionResult failed = new ProvisionResult.Failed("timeout");
        assertThat(success).isInstanceOf(ProvisionResult.Success.class);
        assertThat(((ProvisionResult.Failed) failed).reason()).isEqualTo("timeout");
    }

    @Test
    void deprovisionResult_sealed() {
        DeprovisionResult success = new DeprovisionResult.Success();
        DeprovisionResult failed = new DeprovisionResult.Failed("locked");
        assertThat(((DeprovisionResult.Failed) failed).reason()).isEqualTo("locked");
    }

    @Test
    void stepOutcome_sealed() {
        StepOutcome outcome = new StepOutcome.Failed("boom");
        String result = switch (outcome) {
            case StepOutcome.Succeeded s -> "ok";
            case StepOutcome.Failed f -> "fail:" + f.reason();
            case StepOutcome.Skipped s -> "skip:" + s.reason();
        };
        assertThat(result).isEqualTo("fail:boom");
    }

    @Test
    void transitionResult_outcomes() {
        var id = new NodeId("a");
        var result = new TransitionResult(Map.of(id, new StepOutcome.Succeeded()));
        assertThat(result.outcomes()).containsKey(id);
    }

    @Test
    void actualState_statuses() {
        var id = new NodeId("lib");
        var state = new ActualState(Map.of(id, NodeStatus.PRESENT));
        assertThat(state.statuses().get(id)).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void faultEvent_fields() {
        var event = new FaultEvent(new NodeId("lib"), FaultType.NODE_DESTROYED, "hero raid");
        assertThat(event.type()).isEqualTo(FaultType.NODE_DESTROYED);
    }

    @Test
    void stateEvent_fields() {
        var event = new StateEvent(new NodeId("lib"), NodeStatus.ABSENT, "destroyed");
        assertThat(event.newStatus()).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void reconciliationResult_fields() {
        var result = new ReconciliationResult(
            Set.of(new NodeId("a")), Set.of(new NodeId("b")),
            Set.of(), List.of());
        assertThat(result.resolved()).hasSize(1);
        assertThat(result.drifted()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl api -Dtest=TypesTest`
Expected: FAIL

- [ ] **Step 3: Implement all types**

`GraphMutation.java`:
```java
package io.casehub.desiredstate.api;

public sealed interface GraphMutation {
    record AddNode(DesiredNode node) implements GraphMutation {}
    record RemoveNode(NodeId id) implements GraphMutation {}
    record UpdateNode(NodeId id, NodeSpec newSpec) implements GraphMutation {}
    record AddDependency(Dependency dependency) implements GraphMutation {}
    record RemoveDependency(Dependency dependency) implements GraphMutation {}
}
```

`FaultType.java`:
```java
package io.casehub.desiredstate.api;

public enum FaultType {
    NODE_DESTROYED,
    NODE_DEGRADED,
    PROVISION_FAILED,
    DEPROVISION_FAILED,
    HUMAN_NODE_TIMEOUT,
    DEPENDENCY_UNAVAILABLE
}
```

`FaultEvent.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record FaultEvent(NodeId node, FaultType type, String detail) {
    public FaultEvent {
        Objects.requireNonNull(node);
        Objects.requireNonNull(type);
    }
}
```

`NodeStatus.java`:
```java
package io.casehub.desiredstate.api;

public enum NodeStatus {
    PRESENT, ABSENT, DEGRADED, UNKNOWN
}
```

`StateEvent.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record StateEvent(NodeId node, NodeStatus newStatus, String detail) {
    public StateEvent {
        Objects.requireNonNull(node);
        Objects.requireNonNull(newStatus);
    }
}
```

`ActualState.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Map;

public record ActualState(Map<NodeId, NodeStatus> statuses) {
    public ActualState {
        statuses = Map.copyOf(statuses);
    }
}
```

`ProvisionContext.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record ProvisionContext(String tenancyId, DesiredStateGraph graph) {
    public ProvisionContext {
        Objects.requireNonNull(tenancyId);
        Objects.requireNonNull(graph);
    }
}
```

`DeprovisionContext.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record DeprovisionContext(String tenancyId, DesiredStateGraph graph) {
    public DeprovisionContext {
        Objects.requireNonNull(tenancyId);
        Objects.requireNonNull(graph);
    }
}
```

`ProvisionResult.java`:
```java
package io.casehub.desiredstate.api;

public sealed interface ProvisionResult {
    record Success() implements ProvisionResult {}
    record Failed(String reason) implements ProvisionResult {}
}
```

`DeprovisionResult.java`:
```java
package io.casehub.desiredstate.api;

public sealed interface DeprovisionResult {
    record Success() implements DeprovisionResult {}
    record Failed(String reason) implements DeprovisionResult {}
}
```

`StepAction.java`:
```java
package io.casehub.desiredstate.api;

public enum StepAction {
    PROVISION, DEPROVISION
}
```

`OrderedStep.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record OrderedStep(DesiredNode node, StepAction action) {
    public OrderedStep {
        Objects.requireNonNull(node);
        Objects.requireNonNull(action);
    }
}
```

`StepOutcome.java`:
```java
package io.casehub.desiredstate.api;

public sealed interface StepOutcome {
    record Succeeded() implements StepOutcome {}
    record Failed(String reason) implements StepOutcome {}
    record Skipped(String reason) implements StepOutcome {}
}
```

`TransitionPlan.java`:
```java
package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Objects;

public record TransitionPlan(
    List<OrderedStep> removals,
    List<OrderedStep> additions,
    DesiredStateGraph before,
    DesiredStateGraph after
) {
    public TransitionPlan {
        removals = List.copyOf(removals);
        additions = List.copyOf(additions);
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
    }

    public boolean isEmpty() {
        return removals.isEmpty() && additions.isEmpty();
    }
}
```

`TransitionResult.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Map;

public record TransitionResult(Map<NodeId, StepOutcome> outcomes) {
    public TransitionResult {
        outcomes = Map.copyOf(outcomes);
    }
}
```

`ReconciliationResult.java`:
```java
package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Set;

public record ReconciliationResult(
    Set<NodeId> resolved,
    Set<NodeId> drifted,
    Set<NodeId> faulted,
    List<GraphMutation> mutations
) {
    public ReconciliationResult {
        resolved = Set.copyOf(resolved);
        drifted = Set.copyOf(drifted);
        faulted = Set.copyOf(faulted);
        mutations = List.copyOf(mutations);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl api -Dtest=TypesTest`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add api/src/
git commit -m "feat(#1): add graph mutations, fault types, transition types, provisioner types"
```

---

## Task 4: API — DesiredStateGraph SPI, factory, error types, and domain SPIs

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/DesiredStateGraph.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DesiredStateGraphFactory.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/CyclicDependencyException.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DanglingDependencyException.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ConflictingMutationException.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/GoalCompiler.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ActualStateAdapter.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeProvisioner.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ReactiveNodeProvisioner.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/FaultPolicy.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/EventSource.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/TransitionExecutor.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/SpiContractTest.java`

- [ ] **Step 1: Write SPI contract tests**

```java
package io.casehub.desiredstate.api;

import static org.assertj.core.api.Assertions.assertThat;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpiContractTest {

    record TestSpec(String name) implements NodeSpec {}

    @Test
    void goalCompiler_canBeImplementedWithAnonymousClass() {
        GoalCompiler<String> compiler = (goals, factory) -> factory.empty();
        DesiredStateGraphFactory mockFactory = new DesiredStateGraphFactory() {
            @Override public DesiredStateGraph empty() { return null; }
            @Override public DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps) { return null; }
        };
        assertThat(compiler.compile("test", mockFactory)).isNull();
    }

    @Test
    void actualStateAdapter_canBeImplemented() {
        ActualStateAdapter adapter = desired -> new ActualState(Map.of());
        assertThat(adapter.readActual(null)).isNotNull();
    }

    @Test
    void nodeProvisioner_canBeImplemented() {
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) {
                return new ProvisionResult.Success();
            }
            @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) {
                return new DeprovisionResult.Success();
            }
        };
        var node = new DesiredNode(new NodeId("a"), new TestSpec("x"), false);
        assertThat(provisioner.provision(node, null)).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void reactiveNodeProvisioner_canBeImplemented() {
        ReactiveNodeProvisioner provisioner = new ReactiveNodeProvisioner() {
            @Override public Uni<ProvisionResult> provision(DesiredNode node, ProvisionContext ctx) {
                return Uni.createFrom().item(new ProvisionResult.Success());
            }
            @Override public Uni<DeprovisionResult> deprovision(DesiredNode node, DeprovisionContext ctx) {
                return Uni.createFrom().item(new DeprovisionResult.Success());
            }
        };
        var node = new DesiredNode(new NodeId("a"), new TestSpec("x"), false);
        assertThat(provisioner.provision(node, null).await().indefinitely())
            .isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void faultPolicy_canBeImplemented() {
        FaultPolicy policy = (event, graph) -> List.of();
        assertThat(policy.onFault(null, null)).isEmpty();
    }

    @Test
    void eventSource_canBeImplemented() {
        EventSource source = () -> Multi.createFrom().empty();
        assertThat(source.stream()).isNotNull();
    }

    @Test
    void transitionExecutor_canBeImplemented() {
        TransitionExecutor executor = plan -> Uni.createFrom().item(
            new TransitionResult(Map.of()));
        assertThat(executor.execute(null).await().indefinitely()).isNotNull();
    }

    @Test
    void cyclicDependencyException_carriesCycle() {
        var cycle = List.of(new NodeId("a"), new NodeId("b"), new NodeId("a"));
        var ex = new CyclicDependencyException(cycle);
        assertThat(ex.getCycle()).hasSize(3);
        assertThat(ex.getMessage()).contains("a");
    }

    @Test
    void danglingDependencyException_carriesNodes() {
        var from = new NodeId("creature");
        var missing = new NodeId("room");
        var ex = new DanglingDependencyException(from, missing);
        assertThat(ex.getFrom()).isEqualTo(from);
        assertThat(ex.getMissingTo()).isEqualTo(missing);
    }

    @Test
    void conflictingMutationException_carriesBothMutations() {
        var id = new NodeId("lib");
        GraphMutation a = new GraphMutation.RemoveNode(id);
        GraphMutation b = new GraphMutation.UpdateNode(id, new TestSpec("new"));
        var ex = new ConflictingMutationException(id, a, b);
        assertThat(ex.getNodeId()).isEqualTo(id);
        assertThat(ex.getMutationA()).isEqualTo(a);
        assertThat(ex.getMutationB()).isEqualTo(b);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl api -Dtest=SpiContractTest`
Expected: FAIL

- [ ] **Step 3: Implement DesiredStateGraph SPI**

```java
package io.casehub.desiredstate.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface DesiredStateGraph {

    Map<NodeId, DesiredNode> nodes();
    Set<Dependency> dependencies();
    Set<NodeId> dependenciesOf(NodeId node);
    Set<NodeId> dependentsOf(NodeId node);
    Set<NodeId> roots();
    Set<NodeId> leaves();
    int version();
    boolean isEmpty();

    DesiredStateGraph withNode(DesiredNode node);
    DesiredStateGraph withoutNode(NodeId id);
    DesiredStateGraph withDependency(Dependency dep);
    DesiredStateGraph withoutDependency(Dependency dep);
    DesiredStateGraph withMutation(GraphMutation mutation);

    DesiredStateGraph overlay(DesiredStateGraph other);
    DesiredStateGraph connect(DesiredStateGraph other);
}
```

- [ ] **Step 4: Implement DesiredStateGraphFactory SPI**

```java
package io.casehub.desiredstate.api;

import java.util.Collection;

public interface DesiredStateGraphFactory {
    DesiredStateGraph empty();
    DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps);
}
```

- [ ] **Step 5: Implement error types**

`CyclicDependencyException.java`:
```java
package io.casehub.desiredstate.api;

import java.util.List;

public class CyclicDependencyException extends RuntimeException {
    private final List<NodeId> cycle;

    public CyclicDependencyException(List<NodeId> cycle) {
        super("Cyclic dependency detected: " +
              cycle.stream().map(NodeId::value).reduce((a, b) -> a + " → " + b).orElse(""));
        this.cycle = List.copyOf(cycle);
    }

    public List<NodeId> getCycle() { return cycle; }
}
```

`DanglingDependencyException.java`:
```java
package io.casehub.desiredstate.api;

public class DanglingDependencyException extends RuntimeException {
    private final NodeId from;
    private final NodeId missingTo;

    public DanglingDependencyException(NodeId from, NodeId missingTo) {
        super("Dangling dependency: " + from.value() + " depends on " +
              missingTo.value() + " which is not in the graph");
        this.from = from;
        this.missingTo = missingTo;
    }

    public NodeId getFrom() { return from; }
    public NodeId getMissingTo() { return missingTo; }
}
```

`ConflictingMutationException.java`:
```java
package io.casehub.desiredstate.api;

public class ConflictingMutationException extends RuntimeException {
    private final NodeId nodeId;
    private final GraphMutation mutationA;
    private final GraphMutation mutationB;

    public ConflictingMutationException(NodeId nodeId, GraphMutation mutationA, GraphMutation mutationB) {
        super("Conflicting mutations for node " + nodeId.value() + ": " + mutationA + " vs " + mutationB);
        this.nodeId = nodeId;
        this.mutationA = mutationA;
        this.mutationB = mutationB;
    }

    public NodeId getNodeId() { return nodeId; }
    public GraphMutation getMutationA() { return mutationA; }
    public GraphMutation getMutationB() { return mutationB; }
}
```

- [ ] **Step 6: Implement domain SPIs**

`GoalCompiler.java`:
```java
package io.casehub.desiredstate.api;

public interface GoalCompiler<G> {
    DesiredStateGraph compile(G goals, DesiredStateGraphFactory factory);
}
```

`ActualStateAdapter.java`:
```java
package io.casehub.desiredstate.api;

public interface ActualStateAdapter {
    ActualState readActual(DesiredStateGraph desired);
}
```

`NodeProvisioner.java`:
```java
package io.casehub.desiredstate.api;

public interface NodeProvisioner {
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}
```

`ReactiveNodeProvisioner.java`:
```java
package io.casehub.desiredstate.api;

import io.smallrye.mutiny.Uni;

public interface ReactiveNodeProvisioner {
    Uni<ProvisionResult> provision(DesiredNode node, ProvisionContext context);
    Uni<DeprovisionResult> deprovision(DesiredNode node, DeprovisionContext context);
}
```

`FaultPolicy.java`:
```java
package io.casehub.desiredstate.api;

import java.util.List;

public interface FaultPolicy {
    List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current);
}
```

`EventSource.java`:
```java
package io.casehub.desiredstate.api;

import io.smallrye.mutiny.Multi;

public interface EventSource {
    Multi<StateEvent> stream();
}
```

`TransitionExecutor.java`:
```java
package io.casehub.desiredstate.api;

import io.smallrye.mutiny.Uni;

public interface TransitionExecutor {
    Uni<TransitionResult> execute(TransitionPlan plan);
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -pl api -Dtest=SpiContractTest`
Expected: PASS (10 tests)

- [ ] **Step 8: Run all api tests**

Run: `mvn test -pl api`
Expected: PASS (all tests)

- [ ] **Step 9: Commit**

```bash
git add api/src/
git commit -m "feat(#1): add DesiredStateGraph SPI, factory, error types, and all domain SPIs"
```

---

## Task 5: Runtime — ImmutableDesiredStateGraph + factory

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/ImmutableDesiredStateGraph.java`
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultDesiredStateGraphFactory.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/ImmutableDesiredStateGraphTest.java`

This is the largest task — the core data structure. Tests exercise all graph operations, immutability, cycle detection, dangling dependency detection, overlay/connect composition, and versioning.

- [ ] **Step 1: Write comprehensive failing tests**

```java
package io.casehub.desiredstate.runtime;

import static org.assertj.core.api.Assertions.*;
import io.casehub.desiredstate.api.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ImmutableDesiredStateGraphTest {

    record Spec(String name) implements NodeSpec {}

    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
    }

    private DesiredNode node(String id, String type) {
        return new DesiredNode(new NodeId(id), new Spec(id), false);
    }

    private DesiredNode humanNode(String id) {
        return new DesiredNode(new NodeId(id), new Spec(id), true);
    }

    private Dependency dep(String from, String to) {
        return new Dependency(new NodeId(from), new NodeId(to));
    }

    @Nested
    class EmptyGraph {
        @Test
        void isEmpty() {
            var graph = factory.empty();
            assertThat(graph.isEmpty()).isTrue();
            assertThat(graph.nodes()).isEmpty();
            assertThat(graph.dependencies()).isEmpty();
            assertThat(graph.version()).isEqualTo(0);
        }
    }

    @Nested
    class NodeOperations {
        @Test
        void addNode_returnsNewGraph() {
            var graph = factory.empty();
            var withNode = graph.withNode(node("lair", "room"));
            assertThat(graph.isEmpty()).isTrue();
            assertThat(withNode.nodes()).hasSize(1);
            assertThat(withNode.version()).isEqualTo(1);
        }

        @Test
        void removeNode_returnsNewGraph() {
            var graph = factory.empty()
                .withNode(node("lair", "room"));
            var without = graph.withoutNode(new NodeId("lair"));
            assertThat(without.isEmpty()).isTrue();
            assertThat(without.version()).isEqualTo(2);
            assertThat(graph.nodes()).hasSize(1);
        }

        @Test
        void removeNode_alsoRemovesEdges() {
            var graph = factory.empty()
                .withNode(node("a", "t"))
                .withNode(node("b", "t"))
                .withDependency(dep("a", "b"));
            var without = graph.withoutNode(new NodeId("a"));
            assertThat(without.dependencies()).isEmpty();
            assertThat(without.dependentsOf(new NodeId("b"))).isEmpty();
        }
    }

    @Nested
    class EdgeOperations {
        @Test
        void addDependency() {
            var graph = factory.empty()
                .withNode(node("creature", "creature"))
                .withNode(node("room", "room"))
                .withDependency(dep("creature", "room"));
            assertThat(graph.dependenciesOf(new NodeId("creature")))
                .containsExactly(new NodeId("room"));
            assertThat(graph.dependentsOf(new NodeId("room")))
                .containsExactly(new NodeId("creature"));
        }

        @Test
        void removeDependency() {
            var graph = factory.empty()
                .withNode(node("a", "t"))
                .withNode(node("b", "t"))
                .withDependency(dep("a", "b"))
                .withoutDependency(dep("a", "b"));
            assertThat(graph.dependencies()).isEmpty();
        }

        @Test
        void danglingDependency_throws() {
            var graph = factory.empty()
                .withNode(node("a", "t"));
            assertThatThrownBy(() -> graph.withDependency(dep("a", "missing")))
                .isInstanceOf(DanglingDependencyException.class)
                .hasMessageContaining("missing");
        }
    }

    @Nested
    class CycleDetection {
        @Test
        void selfLoop_throws() {
            var graph = factory.empty()
                .withNode(node("a", "t"));
            assertThatThrownBy(() -> graph.withDependency(dep("a", "a")))
                .isInstanceOf(CyclicDependencyException.class);
        }

        @Test
        void twoNodeCycle_throws() {
            var graph = factory.empty()
                .withNode(node("a", "t"))
                .withNode(node("b", "t"))
                .withDependency(dep("a", "b"));
            assertThatThrownBy(() -> graph.withDependency(dep("b", "a")))
                .isInstanceOf(CyclicDependencyException.class);
        }

        @Test
        void transitiveeCycle_throws() {
            var graph = factory.empty()
                .withNode(node("a", "t"))
                .withNode(node("b", "t"))
                .withNode(node("c", "t"))
                .withDependency(dep("a", "b"))
                .withDependency(dep("b", "c"));
            assertThatThrownBy(() -> graph.withDependency(dep("c", "a")))
                .isInstanceOf(CyclicDependencyException.class);
        }

        @Test
        void diamond_noCycle() {
            var graph = factory.empty()
                .withNode(node("a", "t"))
                .withNode(node("b", "t"))
                .withNode(node("c", "t"))
                .withNode(node("d", "t"))
                .withDependency(dep("a", "b"))
                .withDependency(dep("a", "c"))
                .withDependency(dep("b", "d"))
                .withDependency(dep("c", "d"));
            assertThat(graph.dependencies()).hasSize(4);
        }
    }

    @Nested
    class TopologicalQueries {
        @Test
        void roots_nodesWithNoDependencies() {
            var graph = factory.empty()
                .withNode(node("root", "t"))
                .withNode(node("leaf", "t"))
                .withDependency(dep("leaf", "root"));
            assertThat(graph.roots()).containsExactly(new NodeId("root"));
        }

        @Test
        void leaves_nodesWithNoDependents() {
            var graph = factory.empty()
                .withNode(node("root", "t"))
                .withNode(node("leaf", "t"))
                .withDependency(dep("leaf", "root"));
            assertThat(graph.leaves()).containsExactly(new NodeId("leaf"));
        }
    }

    @Nested
    class Composition {
        @Test
        void overlay_mergesGraphs() {
            var g1 = factory.empty()
                .withNode(node("a", "t"))
                .withNode(node("b", "t"))
                .withDependency(dep("a", "b"));
            var g2 = factory.empty()
                .withNode(node("c", "t"))
                .withNode(node("d", "t"))
                .withDependency(dep("c", "d"));
            var merged = g1.overlay(g2);
            assertThat(merged.nodes()).hasSize(4);
            assertThat(merged.dependencies()).hasSize(2);
        }

        @Test
        void connect_leavesToRoots() {
            var g1 = factory.empty()
                .withNode(node("a", "t"));
            var g2 = factory.empty()
                .withNode(node("b", "t"));
            var connected = g1.connect(g2);
            assertThat(connected.nodes()).hasSize(2);
            assertThat(connected.dependenciesOf(new NodeId("a")))
                .containsExactly(new NodeId("b"));
        }
    }

    @Nested
    class Mutations {
        @Test
        void withMutation_addNode() {
            var graph = factory.empty();
            var node = node("a", "t");
            var result = graph.withMutation(new GraphMutation.AddNode(node));
            assertThat(result.nodes()).containsKey(new NodeId("a"));
        }

        @Test
        void withMutation_removeNode() {
            var graph = factory.empty().withNode(node("a", "t"));
            var result = graph.withMutation(new GraphMutation.RemoveNode(new NodeId("a")));
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void withMutation_updateNode() {
            var graph = factory.empty().withNode(node("a", "t"));
            var newSpec = new Spec("updated");
            var result = graph.withMutation(new GraphMutation.UpdateNode(new NodeId("a"), newSpec));
            assertThat(result.nodes().get(new NodeId("a")).spec()).isEqualTo(newSpec);
        }
    }

    @Nested
    class FactoryOf {
        @Test
        void createsGraphWithNodesAndDeps() {
            var a = node("a", "t");
            var b = node("b", "t");
            var graph = factory.of(List.of(a, b), List.of(dep("a", "b")));
            assertThat(graph.nodes()).hasSize(2);
            assertThat(graph.dependencies()).hasSize(1);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl runtime -Dtest=ImmutableDesiredStateGraphTest`
Expected: FAIL

- [ ] **Step 3: Implement ImmutableDesiredStateGraph**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import java.util.*;
import java.util.stream.Collectors;

final class ImmutableDesiredStateGraph implements DesiredStateGraph {

    private final Map<NodeId, DesiredNode> nodes;
    private final Map<NodeId, Set<NodeId>> forwardEdges;
    private final Map<NodeId, Set<NodeId>> reverseEdges;
    private final int version;

    ImmutableDesiredStateGraph(
            Map<NodeId, DesiredNode> nodes,
            Map<NodeId, Set<NodeId>> forwardEdges,
            Map<NodeId, Set<NodeId>> reverseEdges,
            int version) {
        this.nodes = Map.copyOf(nodes);
        this.forwardEdges = deepCopyEdges(forwardEdges);
        this.reverseEdges = deepCopyEdges(reverseEdges);
        this.version = version;
    }

    static ImmutableDesiredStateGraph empty() {
        return new ImmutableDesiredStateGraph(Map.of(), Map.of(), Map.of(), 0);
    }

    @Override
    public Map<NodeId, DesiredNode> nodes() { return nodes; }

    @Override
    public Set<Dependency> dependencies() {
        return forwardEdges.entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(to -> new Dependency(e.getKey(), to)))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<NodeId> dependenciesOf(NodeId node) {
        return forwardEdges.getOrDefault(node, Set.of());
    }

    @Override
    public Set<NodeId> dependentsOf(NodeId node) {
        return reverseEdges.getOrDefault(node, Set.of());
    }

    @Override
    public Set<NodeId> roots() {
        return nodes.keySet().stream()
            .filter(id -> forwardEdges.getOrDefault(id, Set.of()).isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<NodeId> leaves() {
        return nodes.keySet().stream()
            .filter(id -> reverseEdges.getOrDefault(id, Set.of()).isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public int version() { return version; }

    @Override
    public boolean isEmpty() { return nodes.isEmpty(); }

    @Override
    public DesiredStateGraph withNode(DesiredNode node) {
        var newNodes = new HashMap<>(nodes);
        newNodes.put(node.id(), node);
        return new ImmutableDesiredStateGraph(newNodes, forwardEdges, reverseEdges, version + 1);
    }

    @Override
    public DesiredStateGraph withoutNode(NodeId id) {
        var newNodes = new HashMap<>(nodes);
        newNodes.remove(id);
        var newForward = new HashMap<>(mutableEdges(forwardEdges));
        var newReverse = new HashMap<>(mutableEdges(reverseEdges));
        newForward.remove(id);
        newReverse.remove(id);
        for (var entry : newForward.entrySet()) {
            entry.getValue().remove(id);
        }
        for (var entry : newReverse.entrySet()) {
            entry.getValue().remove(id);
        }
        return new ImmutableDesiredStateGraph(newNodes, freezeEdges(newForward), freezeEdges(newReverse), version + 1);
    }

    @Override
    public DesiredStateGraph withDependency(Dependency dep) {
        if (!nodes.containsKey(dep.from())) {
            throw new DanglingDependencyException(dep.from(), dep.from());
        }
        if (!nodes.containsKey(dep.to())) {
            throw new DanglingDependencyException(dep.from(), dep.to());
        }
        var newForward = mutableEdges(forwardEdges);
        newForward.computeIfAbsent(dep.from(), k -> new HashSet<>()).add(dep.to());
        detectCycle(dep.from(), newForward);
        var newReverse = mutableEdges(reverseEdges);
        newReverse.computeIfAbsent(dep.to(), k -> new HashSet<>()).add(dep.from());
        return new ImmutableDesiredStateGraph(nodes, freezeEdges(newForward), freezeEdges(newReverse), version + 1);
    }

    @Override
    public DesiredStateGraph withoutDependency(Dependency dep) {
        var newForward = mutableEdges(forwardEdges);
        var fwdSet = newForward.get(dep.from());
        if (fwdSet != null) { fwdSet.remove(dep.to()); }
        var newReverse = mutableEdges(reverseEdges);
        var revSet = newReverse.get(dep.to());
        if (revSet != null) { revSet.remove(dep.from()); }
        return new ImmutableDesiredStateGraph(nodes, freezeEdges(newForward), freezeEdges(newReverse), version + 1);
    }

    @Override
    public DesiredStateGraph withMutation(GraphMutation mutation) {
        return switch (mutation) {
            case GraphMutation.AddNode m -> withNode(m.node());
            case GraphMutation.RemoveNode m -> withoutNode(m.id());
            case GraphMutation.UpdateNode m -> {
                var existing = nodes.get(m.id());
                if (existing == null) yield this;
                yield withNode(new DesiredNode(m.id(), m.newSpec(), existing.requiresHuman()));
            }
            case GraphMutation.AddDependency m -> withDependency(m.dependency());
            case GraphMutation.RemoveDependency m -> withoutDependency(m.dependency());
        };
    }

    @Override
    public DesiredStateGraph overlay(DesiredStateGraph other) {
        var result = this;
        for (var node : other.nodes().values()) {
            if (result.nodes().containsKey(node.id())) {
                var existing = result.nodes().get(node.id());
                if (!existing.spec().equals(node.spec())) {
                    throw new IllegalArgumentException(
                        "Overlay conflict: node " + node.id().value() + " has different specs");
                }
                continue;
            }
            result = result.withNode(node);
        }
        for (var dep : other.dependencies()) {
            if (!result.dependencies().contains(dep)) {
                result = result.withDependency(dep);
            }
        }
        return result;
    }

    @Override
    public DesiredStateGraph connect(DesiredStateGraph other) {
        var leaves = this.leaves();
        var merged = this.overlay(other);
        var roots = other.roots();
        var result = merged;
        for (var leaf : leaves) {
            for (var root : roots) {
                result = result.withDependency(new Dependency(leaf, root));
            }
        }
        return result;
    }

    private void detectCycle(NodeId start, Map<NodeId, Set<NodeId>> edges) {
        var visited = new HashSet<NodeId>();
        var path = new ArrayList<NodeId>();
        if (hasCycleDfs(start, edges, visited, path, new HashSet<>())) {
            path.add(start);
            throw new CyclicDependencyException(path);
        }
    }

    private boolean hasCycleDfs(NodeId current, Map<NodeId, Set<NodeId>> edges,
                                Set<NodeId> visited, List<NodeId> path, Set<NodeId> onStack) {
        visited.add(current);
        onStack.add(current);
        path.add(current);
        for (var neighbor : edges.getOrDefault(current, Set.of())) {
            if (onStack.contains(neighbor)) {
                return true;
            }
            if (!visited.contains(neighbor) && hasCycleDfs(neighbor, edges, visited, path, onStack)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        onStack.remove(current);
        return false;
    }

    private static Map<NodeId, Set<NodeId>> mutableEdges(Map<NodeId, Set<NodeId>> edges) {
        var result = new HashMap<NodeId, Set<NodeId>>();
        for (var entry : edges.entrySet()) {
            result.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return result;
    }

    private static Map<NodeId, Set<NodeId>> freezeEdges(Map<NodeId, Set<NodeId>> edges) {
        var result = new HashMap<NodeId, Set<NodeId>>();
        for (var entry : edges.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<NodeId, Set<NodeId>> deepCopyEdges(Map<NodeId, Set<NodeId>> edges) {
        return freezeEdges(edges);
    }
}
```

- [ ] **Step 4: Implement DefaultDesiredStateGraphFactory**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Singleton;
import io.quarkus.arc.DefaultBean;
import java.util.Collection;

@DefaultBean
@Singleton
public class DefaultDesiredStateGraphFactory implements DesiredStateGraphFactory {

    @Override
    public DesiredStateGraph empty() {
        return ImmutableDesiredStateGraph.empty();
    }

    @Override
    public DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps) {
        var graph = empty();
        for (var node : nodes) {
            graph = graph.withNode(node);
        }
        for (var dep : deps) {
            graph = graph.withDependency(dep);
        }
        return graph;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl runtime -Dtest=ImmutableDesiredStateGraphTest`
Expected: PASS (all tests)

- [ ] **Step 6: Commit**

```bash
git add runtime/src/
git commit -m "feat(#1): add ImmutableDesiredStateGraph — dual adjacency maps, cycle detection, overlay/connect"
```

---

## Task 6: Runtime — TransitionPlanner

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/TransitionPlanner.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/TransitionPlannerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.desiredstate.runtime;

import static org.assertj.core.api.Assertions.*;
import io.casehub.desiredstate.api.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransitionPlannerTest {

    record Spec(String name) implements NodeSpec {}

    private TransitionPlanner planner;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        planner = new TransitionPlanner();
        factory = new DefaultDesiredStateGraphFactory();
    }

    private DesiredNode node(String id) {
        return new DesiredNode(new NodeId(id), new Spec(id), false);
    }

    @Test
    void emptyDiff_emptyPlan() {
        var graph = factory.empty().withNode(node("a"));
        var actual = new ActualState(Map.of(new NodeId("a"), NodeStatus.PRESENT));
        var plan = planner.plan(graph, actual);
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void allAbsent_additionsOnly() {
        var graph = factory.empty()
            .withNode(node("root"))
            .withNode(node("leaf"))
            .withDependency(new Dependency(new NodeId("leaf"), new NodeId("root")));
        var actual = new ActualState(Map.of());
        var plan = planner.plan(graph, actual);
        assertThat(plan.removals()).isEmpty();
        assertThat(plan.additions()).hasSize(2);
        assertThat(plan.additions().get(0).node().id()).isEqualTo(new NodeId("root"));
        assertThat(plan.additions().get(1).node().id()).isEqualTo(new NodeId("leaf"));
    }

    @Test
    void allPresent_noNodeInDesired_removalsOnly() {
        var graph = factory.empty();
        var actual = new ActualState(Map.of(new NodeId("old"), NodeStatus.PRESENT));
        var plan = planner.plan(graph, actual);
        assertThat(plan.additions()).isEmpty();
        assertThat(plan.removals()).hasSize(1);
    }

    @Test
    void additions_rootsFirst() {
        var graph = factory.empty()
            .withNode(node("a"))
            .withNode(node("b"))
            .withNode(node("c"))
            .withDependency(new Dependency(new NodeId("b"), new NodeId("a")))
            .withDependency(new Dependency(new NodeId("c"), new NodeId("b")));
        var actual = new ActualState(Map.of());
        var plan = planner.plan(graph, actual);
        var ids = plan.additions().stream().map(s -> s.node().id().value()).toList();
        assertThat(ids).containsExactly("a", "b", "c");
    }

    @Test
    void removals_orphanedNodesListed() {
        var actual = new ActualState(Map.of(
            new NodeId("old-a"), NodeStatus.PRESENT,
            new NodeId("old-b"), NodeStatus.PRESENT));
        var emptyDesired = factory.empty();
        var plan = planner.plan(emptyDesired, actual);
        assertThat(plan.removals()).hasSize(2);
        assertThat(plan.removals().stream().map(s -> s.node().id().value()).toList())
            .containsExactlyInAnyOrder("old-a", "old-b");
    }

    @Test
    void updatedNode_appearsAsRemovalThenAddition() {
        var oldSpec = new Spec("old");
        var newSpec = new Spec("new");
        var oldNode = new DesiredNode(new NodeId("a"), oldSpec, false);
        var newNode = new DesiredNode(new NodeId("a"), newSpec, false);
        var desired = factory.empty().withNode(newNode);
        var actual = new ActualState(Map.of(new NodeId("a"), NodeStatus.PRESENT));
        var plan = planner.plan(desired, actual);
        assertThat(plan.removals()).hasSize(1);
        assertThat(plan.additions()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl runtime -Dtest=TransitionPlannerTest`
Expected: FAIL

- [ ] **Step 3: Implement TransitionPlanner**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class TransitionPlanner {

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual) {
        var toAdd = new ArrayList<DesiredNode>();
        var toRemove = new ArrayList<DesiredNode>();

        for (var entry : desired.nodes().entrySet()) {
            var status = actual.statuses().getOrDefault(entry.getKey(), NodeStatus.ABSENT);
            if (status == NodeStatus.ABSENT || status == NodeStatus.UNKNOWN) {
                toAdd.add(entry.getValue());
            }
        }

        for (var entry : actual.statuses().entrySet()) {
            if (entry.getValue() == NodeStatus.PRESENT && !desired.nodes().containsKey(entry.getKey())) {
                var node = new DesiredNode(entry.getKey(), new UnknownSpec(), false);
                toRemove.add(node);
            }
        }

        var removals = topologicalSortLeavesFirst(toRemove, desired);
        var additions = topologicalSortRootsFirst(toAdd, desired);

        var removalSteps = removals.stream()
            .map(n -> new OrderedStep(n, StepAction.DEPROVISION))
            .toList();
        var additionSteps = additions.stream()
            .map(n -> new OrderedStep(n, StepAction.PROVISION))
            .toList();

        return new TransitionPlan(removalSteps, additionSteps, desired, desired);
    }

    private List<DesiredNode> topologicalSortRootsFirst(List<DesiredNode> nodes, DesiredStateGraph graph) {
        var nodeIds = nodes.stream().map(DesiredNode::id).collect(Collectors.toSet());
        var inDegree = new HashMap<NodeId, Integer>();
        for (var id : nodeIds) {
            int deg = 0;
            for (var depId : graph.dependenciesOf(id)) {
                if (nodeIds.contains(depId)) deg++;
            }
            inDegree.put(id, deg);
        }
        var queue = new ArrayDeque<NodeId>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }
        var sorted = new ArrayList<DesiredNode>();
        var nodeMap = nodes.stream().collect(Collectors.toMap(DesiredNode::id, n -> n));
        while (!queue.isEmpty()) {
            var id = queue.poll();
            sorted.add(nodeMap.get(id));
            for (var dependent : graph.dependentsOf(id)) {
                if (nodeIds.contains(dependent)) {
                    int newDeg = inDegree.merge(dependent, -1, Integer::sum);
                    if (newDeg == 0) queue.add(dependent);
                }
            }
        }
        return sorted;
    }

    private List<DesiredNode> topologicalSortLeavesFirst(List<DesiredNode> nodes, DesiredStateGraph graph) {
        var rootsFirst = topologicalSortRootsFirst(nodes, graph);
        var reversed = new ArrayList<>(rootsFirst);
        Collections.reverse(reversed);
        return reversed;
    }

    private record UnknownSpec() implements NodeSpec {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl runtime -Dtest=TransitionPlannerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime/src/
git commit -m "feat(#1): add TransitionPlanner — topological sort, pruning-first, roots-first additions"
```

---

## Task 7: Runtime — FaultPolicyEngine

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/FaultPolicyEngine.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/FaultPolicyEngineTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.desiredstate.runtime;

import static org.assertj.core.api.Assertions.*;
import io.casehub.desiredstate.api.*;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FaultPolicyEngineTest {

    record Spec(String name) implements NodeSpec {}

    private DesiredStateGraph emptyGraph() {
        return new DefaultDesiredStateGraphFactory().empty();
    }

    private FaultPolicyEngine engineWith(FaultPolicy... policies) {
        return new FaultPolicyEngine(List.of(policies));
    }

    @Test
    void noPolicies_emptyMutations() {
        var engine = engineWith();
        var event = new FaultEvent(new NodeId("a"), FaultType.NODE_DESTROYED, "raid");
        var result = engine.evaluate(event, emptyGraph());
        assertThat(result).isEmpty();
    }

    @Test
    void singlePolicy_returnsMutations() {
        FaultPolicy rebuild = (evt, graph) -> List.of(
            new GraphMutation.AddNode(
                new DesiredNode(evt.node(), new Spec("rebuilt"), false)));
        var engine = engineWith(rebuild);
        var event = new FaultEvent(new NodeId("lib"), FaultType.NODE_DESTROYED, "raid");
        var result = engine.evaluate(event, emptyGraph());
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(GraphMutation.AddNode.class);
    }

    @Test
    void multiplePolicies_mergedMutations() {
        FaultPolicy rebuild = (evt, graph) -> List.of(
            new GraphMutation.AddNode(
                new DesiredNode(evt.node(), new Spec("rebuilt"), false)));
        FaultPolicy alert = (evt, graph) -> List.of(
            new GraphMutation.AddNode(
                new DesiredNode(new NodeId("alert"), new Spec("alert"), true)));
        var engine = engineWith(rebuild, alert);
        var event = new FaultEvent(new NodeId("lib"), FaultType.NODE_DESTROYED, "raid");
        var result = engine.evaluate(event, emptyGraph());
        assertThat(result).hasSize(2);
    }

    @Test
    void conflictingMutations_throws() {
        FaultPolicy remove = (evt, graph) -> List.of(
            new GraphMutation.RemoveNode(new NodeId("lib")));
        FaultPolicy update = (evt, graph) -> List.of(
            new GraphMutation.UpdateNode(new NodeId("lib"), new Spec("patched")));
        var engine = engineWith(remove, update);
        var event = new FaultEvent(new NodeId("lib"), FaultType.NODE_DESTROYED, "raid");
        assertThatThrownBy(() -> engine.evaluate(event, emptyGraph()))
            .isInstanceOf(ConflictingMutationException.class)
            .hasMessageContaining("lib");
    }

    @Test
    void sameMutation_fromTwoPolicies_notAConflict() {
        FaultPolicy p1 = (evt, graph) -> List.of(new GraphMutation.RemoveNode(new NodeId("lib")));
        FaultPolicy p2 = (evt, graph) -> List.of(new GraphMutation.RemoveNode(new NodeId("lib")));
        var engine = engineWith(p1, p2);
        var event = new FaultEvent(new NodeId("lib"), FaultType.NODE_DESTROYED, "raid");
        var result = engine.evaluate(event, emptyGraph());
        assertThat(result).hasSize(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl runtime -Dtest=FaultPolicyEngineTest`
Expected: FAIL

- [ ] **Step 3: Implement FaultPolicyEngine**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class FaultPolicyEngine {

    private final List<FaultPolicy> policies;

    @Inject
    public FaultPolicyEngine(List<FaultPolicy> policies) {
        this.policies = policies;
    }

    public List<GraphMutation> evaluate(FaultEvent event, DesiredStateGraph current) {
        var allMutations = new ArrayList<GraphMutation>();
        for (var policy : policies) {
            allMutations.addAll(policy.onFault(event, current));
        }
        return mergeAndDetectConflicts(allMutations);
    }

    private List<GraphMutation> mergeAndDetectConflicts(List<GraphMutation> mutations) {
        var byNode = new HashMap<NodeId, GraphMutation>();
        var merged = new ArrayList<GraphMutation>();

        for (var mutation : mutations) {
            var nodeId = nodeIdOf(mutation);
            if (nodeId == null) {
                merged.add(mutation);
                continue;
            }
            var existing = byNode.get(nodeId);
            if (existing == null) {
                byNode.put(nodeId, mutation);
                merged.add(mutation);
            } else if (!existing.equals(mutation)) {
                throw new ConflictingMutationException(nodeId, existing, mutation);
            }
        }
        return merged;
    }

    private static NodeId nodeIdOf(GraphMutation mutation) {
        return switch (mutation) {
            case GraphMutation.AddNode m -> m.node().id();
            case GraphMutation.RemoveNode m -> m.id();
            case GraphMutation.UpdateNode m -> m.id();
            case GraphMutation.AddDependency m -> null;
            case GraphMutation.RemoveDependency m -> null;
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl runtime -Dtest=FaultPolicyEngineTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime/src/
git commit -m "feat(#1): add FaultPolicyEngine — multi-policy composition, conflict detection"
```

---

## Task 8: Runtime — SimpleTransitionExecutor

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.desiredstate.runtime;

import static org.assertj.core.api.Assertions.*;
import io.casehub.desiredstate.api.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimpleTransitionExecutorTest {

    record Spec(String name) implements NodeSpec {}

    private DesiredNode node(String id) {
        return new DesiredNode(new NodeId(id), new Spec(id), false);
    }

    private DesiredNode humanNode(String id) {
        return new DesiredNode(new NodeId(id), new Spec(id), true);
    }

    @Test
    void executesRemovalsThenAdditions() {
        var calls = new java.util.ArrayList<String>();
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) {
                calls.add("provision:" + node.id().value());
                return new ProvisionResult.Success();
            }
            @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) {
                calls.add("deprovision:" + node.id().value());
                return new DeprovisionResult.Success();
            }
        };
        var factory = new DefaultDesiredStateGraphFactory();
        var graph = factory.empty().withNode(node("a")).withNode(node("b"));
        var plan = new TransitionPlan(
            List.of(new OrderedStep(node("old"), StepAction.DEPROVISION)),
            List.of(new OrderedStep(node("a"), StepAction.PROVISION),
                    new OrderedStep(node("b"), StepAction.PROVISION)),
            graph, graph);

        var executor = new SimpleTransitionExecutor(provisioner, "test-tenant");
        var result = executor.execute(plan).await().indefinitely();

        assertThat(calls).containsExactly("deprovision:old", "provision:a", "provision:b");
        assertThat(result.outcomes()).hasSize(3);
        assertThat(result.outcomes().values()).allMatch(o -> o instanceof StepOutcome.Succeeded);
    }

    @Test
    void skipsHumanNodes() {
        var calls = new java.util.ArrayList<String>();
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) {
                calls.add("provision:" + node.id().value());
                return new ProvisionResult.Success();
            }
            @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) {
                return new DeprovisionResult.Success();
            }
        };
        var factory = new DefaultDesiredStateGraphFactory();
        var graph = factory.empty();
        var plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node("auto"), StepAction.PROVISION),
                    new OrderedStep(humanNode("dragon"), StepAction.PROVISION)),
            graph, graph);

        var executor = new SimpleTransitionExecutor(provisioner, "test-tenant");
        var result = executor.execute(plan).await().indefinitely();

        assertThat(calls).containsExactly("provision:auto");
        assertThat(result.outcomes().get(new NodeId("dragon")))
            .isInstanceOf(StepOutcome.Skipped.class);
    }

    @Test
    void provisionFailure_recordedInResult() {
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) {
                return new ProvisionResult.Failed("timeout");
            }
            @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) {
                return new DeprovisionResult.Success();
            }
        };
        var factory = new DefaultDesiredStateGraphFactory();
        var graph = factory.empty();
        var plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node("a"), StepAction.PROVISION)),
            graph, graph);

        var executor = new SimpleTransitionExecutor(provisioner, "test-tenant");
        var result = executor.execute(plan).await().indefinitely();

        assertThat(result.outcomes().get(new NodeId("a")))
            .isInstanceOf(StepOutcome.Failed.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl runtime -Dtest=SimpleTransitionExecutorTest`
Expected: FAIL

- [ ] **Step 3: Implement SimpleTransitionExecutor**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;

@DefaultBean
@ApplicationScoped
public class SimpleTransitionExecutor implements TransitionExecutor {

    private final NodeProvisioner provisioner;
    private final String tenancyId;

    @Inject
    public SimpleTransitionExecutor(NodeProvisioner provisioner) {
        this(provisioner, "default");
    }

    SimpleTransitionExecutor(NodeProvisioner provisioner, String tenancyId) {
        this.provisioner = provisioner;
        this.tenancyId = tenancyId;
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan) {
        return Uni.createFrom().item(() -> {
            var outcomes = new LinkedHashMap<NodeId, StepOutcome>();
            var context = new ProvisionContext(tenancyId, plan.after());
            var depContext = new DeprovisionContext(tenancyId, plan.before());

            for (var step : plan.removals()) {
                if (step.node().requiresHuman()) {
                    outcomes.put(step.node().id(), new StepOutcome.Skipped("requires human"));
                    continue;
                }
                var result = provisioner.deprovision(step.node(), depContext);
                outcomes.put(step.node().id(), switch (result) {
                    case DeprovisionResult.Success s -> new StepOutcome.Succeeded();
                    case DeprovisionResult.Failed f -> new StepOutcome.Failed(f.reason());
                });
            }

            for (var step : plan.additions()) {
                if (step.node().requiresHuman()) {
                    outcomes.put(step.node().id(), new StepOutcome.Skipped("requires human"));
                    continue;
                }
                var result = provisioner.provision(step.node(), context);
                outcomes.put(step.node().id(), switch (result) {
                    case ProvisionResult.Success s -> new StepOutcome.Succeeded();
                    case ProvisionResult.Failed f -> new StepOutcome.Failed(f.reason());
                });
            }

            return new TransitionResult(outcomes);
        });
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl runtime -Dtest=SimpleTransitionExecutorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime/src/
git commit -m "feat(#1): add SimpleTransitionExecutor — sequential provisioner calls, human node skipping"
```

---

## Task 9: Runtime — ReconciliationLoop

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopTest.java`

This is the most complex runtime component — per-tenant instances, event-driven + periodic triggers, debounce, fault feedback loop. Tests use `@QuarkusTest` for CDI integration.

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.desiredstate.runtime;

import static org.assertj.core.api.Assertions.*;
import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReconciliationLoopTest {

    record Spec(String name) implements NodeSpec {}

    private DefaultDesiredStateGraphFactory factory;
    private TestActualStateAdapter adapter;
    private TestTransitionExecutor executor;
    private TestEventSource eventSource;
    private FaultPolicyEngine faultEngine;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        adapter = new TestActualStateAdapter();
        executor = new TestTransitionExecutor();
        eventSource = new TestEventSource();
        faultEngine = new FaultPolicyEngine(List.of());
    }

    private DesiredNode node(String id) {
        return new DesiredNode(new NodeId(id), new Spec(id), false);
    }

    @Test
    void start_triggersInitialReconciliation() throws InterruptedException {
        adapter.statuses = Map.of();
        var graph = factory.empty().withNode(node("a"));

        var loop = new ReconciliationLoop(
            new TransitionPlanner(), executor, adapter, faultEngine, eventSource,
            Duration.ofHours(1), Duration.ofMillis(50));
        loop.start("tenant-1", graph);

        Thread.sleep(200);
        assertThat(executor.executedPlans).isNotEmpty();
        loop.stop("tenant-1");
    }

    @Test
    void eventDriven_triggersReconciliation() throws InterruptedException {
        adapter.statuses = Map.of(new NodeId("a"), NodeStatus.PRESENT);
        var graph = factory.empty().withNode(node("a"));

        var loop = new ReconciliationLoop(
            new TransitionPlanner(), executor, adapter, faultEngine, eventSource,
            Duration.ofHours(1), Duration.ofMillis(50));
        loop.start("tenant-1", graph);
        Thread.sleep(100);
        executor.executedPlans.clear();

        eventSource.push(new StateEvent(new NodeId("a"), NodeStatus.ABSENT, "destroyed"));
        Thread.sleep(200);

        assertThat(executor.executedPlans).isNotEmpty();
        loop.stop("tenant-1");
    }

    @Test
    void updateDesired_nextCycleUsesNewGraph() throws InterruptedException {
        adapter.statuses = Map.of();
        var graph1 = factory.empty().withNode(node("a"));
        var graph2 = factory.empty().withNode(node("a")).withNode(node("b"));

        var loop = new ReconciliationLoop(
            new TransitionPlanner(), executor, adapter, faultEngine, eventSource,
            Duration.ofHours(1), Duration.ofMillis(50));
        loop.start("tenant-1", graph1);
        Thread.sleep(200);
        loop.updateDesired("tenant-1", graph2);
        eventSource.push(new StateEvent(new NodeId("a"), NodeStatus.PRESENT, "ok"));
        Thread.sleep(200);

        var lastPlan = executor.executedPlans.get(executor.executedPlans.size() - 1);
        assertThat(lastPlan.after().nodes()).containsKey(new NodeId("b"));
        loop.stop("tenant-1");
    }

    static class TestActualStateAdapter implements ActualStateAdapter {
        Map<NodeId, NodeStatus> statuses = Map.of();
        @Override public ActualState readActual(DesiredStateGraph desired) {
            return new ActualState(statuses);
        }
    }

    static class TestTransitionExecutor implements TransitionExecutor {
        final List<TransitionPlan> executedPlans = new CopyOnWriteArrayList<>();
        @Override public Uni<TransitionResult> execute(TransitionPlan plan) {
            executedPlans.add(plan);
            var outcomes = new LinkedHashMap<NodeId, StepOutcome>();
            for (var step : plan.removals()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            for (var step : plan.additions()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            return Uni.createFrom().item(new TransitionResult(outcomes));
        }
    }

    static class TestEventSource implements EventSource {
        private final io.smallrye.mutiny.subscription.MultiEmitter<? super StateEvent>[] emitter = new io.smallrye.mutiny.subscription.MultiEmitter[1];
        private final Multi<StateEvent> multi = Multi.createFrom().emitter(e -> emitter[0] = e);
        @Override public Multi<StateEvent> stream() { return multi; }
        void push(StateEvent event) {
            if (emitter[0] != null) emitter[0].emit(event);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl runtime -Dtest=ReconciliationLoopTest`
Expected: FAIL

- [ ] **Step 3: Implement ReconciliationLoop**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class ReconciliationLoop {

    private final TransitionPlanner planner;
    private final TransitionExecutor executor;
    private final ActualStateAdapter actualStateAdapter;
    private final FaultPolicyEngine faultPolicyEngine;
    private final EventSource eventSource;
    private final Duration resyncInterval;
    private final Duration debounceWindow;
    private final Map<String, TenantLoop> loops = new ConcurrentHashMap<>();

    @Inject
    public ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource) {
        this(planner, executor, actualStateAdapter, faultPolicyEngine, eventSource,
             Duration.ofMinutes(5), Duration.ofSeconds(1));
    }

    ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource,
            Duration resyncInterval,
            Duration debounceWindow) {
        this.planner = planner;
        this.executor = executor;
        this.actualStateAdapter = actualStateAdapter;
        this.faultPolicyEngine = faultPolicyEngine;
        this.eventSource = eventSource;
        this.resyncInterval = resyncInterval;
        this.debounceWindow = debounceWindow;
    }

    public void start(String tenancyId, DesiredStateGraph desired) {
        var tenantLoop = new TenantLoop(tenancyId, desired);
        loops.put(tenancyId, tenantLoop);
        tenantLoop.start();
    }

    public void stop(String tenancyId) {
        var loop = loops.remove(tenancyId);
        if (loop != null) loop.stop();
    }

    public void updateDesired(String tenancyId, DesiredStateGraph newDesired) {
        var loop = loops.get(tenancyId);
        if (loop != null) loop.updateDesired(newDesired);
    }

    private class TenantLoop {
        private final String tenancyId;
        private final AtomicReference<DesiredStateGraph> desired;
        private volatile boolean running;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private io.smallrye.mutiny.subscription.Cancellable eventSubscription;

        TenantLoop(String tenancyId, DesiredStateGraph desired) {
            this.tenancyId = tenancyId;
            this.desired = new AtomicReference<>(desired);
        }

        void start() {
            running = true;
            reconcile();
            scheduler.scheduleAtFixedRate(
                this::reconcile, resyncInterval.toMillis(), resyncInterval.toMillis(),
                TimeUnit.MILLISECONDS);
            eventSubscription = eventSource.stream()
                .group().intoLists().every(debounceWindow)
                .subscribe().with(events -> {
                    if (!events.isEmpty() && running) reconcile();
                });
        }

        void stop() {
            running = false;
            scheduler.shutdown();
            if (eventSubscription != null) eventSubscription.cancel();
        }

        void updateDesired(DesiredStateGraph newDesired) {
            desired.set(newDesired);
        }

        private void reconcile() {
            if (!running) return;
            try {
                var currentDesired = desired.get();
                var actual = actualStateAdapter.readActual(currentDesired);
                var plan = planner.plan(currentDesired, actual);
                if (!plan.isEmpty()) {
                    var result = executor.execute(plan).await().indefinitely();
                    handleFaults(result, currentDesired);
                }
            } catch (Exception e) {
                // log and continue — loop must not die
            }
        }

        private void handleFaults(TransitionResult result, DesiredStateGraph currentDesired) {
            for (var entry : result.outcomes().entrySet()) {
                if (entry.getValue() instanceof StepOutcome.Failed f) {
                    var event = new FaultEvent(entry.getKey(), FaultType.PROVISION_FAILED, f.reason());
                    var mutations = faultPolicyEngine.evaluate(event, currentDesired);
                    if (!mutations.isEmpty()) {
                        var mutated = currentDesired;
                        for (var mutation : mutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                        desired.set(mutated);
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl runtime -Dtest=ReconciliationLoopTest`
Expected: PASS

- [ ] **Step 5: Run all runtime tests**

Run: `mvn test -pl runtime`
Expected: PASS (all tests across all runtime test classes)

- [ ] **Step 6: Commit**

```bash
git add runtime/src/
git commit -m "feat(#1): add ReconciliationLoop — per-tenant, event-driven + periodic, debounced, fault feedback"
```

---

## Task 10: Testing module — Mock SPIs

**Files:**
- Create: `testing/src/main/java/io/casehub/desiredstate/testing/MockNodeProvisioner.java`
- Create: `testing/src/main/java/io/casehub/desiredstate/testing/MockActualStateAdapter.java`
- Create: `testing/src/main/java/io/casehub/desiredstate/testing/CannedEventSource.java`

- [ ] **Step 1: Implement MockNodeProvisioner**

```java
package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MockNodeProvisioner implements NodeProvisioner {

    public final List<DesiredNode> provisioned = new CopyOnWriteArrayList<>();
    public final List<DesiredNode> deprovisioned = new CopyOnWriteArrayList<>();
    private ProvisionResult provisionResult = new ProvisionResult.Success();
    private DeprovisionResult deprovisionResult = new DeprovisionResult.Success();

    public void setProvisionResult(ProvisionResult result) { this.provisionResult = result; }
    public void setDeprovisionResult(DeprovisionResult result) { this.deprovisionResult = result; }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        provisioned.add(node);
        return provisionResult;
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        deprovisioned.add(node);
        return deprovisionResult;
    }
}
```

- [ ] **Step 2: Implement MockActualStateAdapter**

```java
package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockActualStateAdapter implements ActualStateAdapter {

    private final Map<NodeId, NodeStatus> statuses = new ConcurrentHashMap<>();

    public void setStatus(NodeId id, NodeStatus status) { statuses.put(id, status); }
    public void setAllPresent(DesiredStateGraph graph) {
        graph.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
    }
    public void clear() { statuses.clear(); }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        return new ActualState(Map.copyOf(statuses));
    }
}
```

- [ ] **Step 3: Implement CannedEventSource**

```java
package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;

public class CannedEventSource implements EventSource {

    private MultiEmitter<? super StateEvent> emitter;
    private final Multi<StateEvent> multi;

    public CannedEventSource() {
        this.multi = Multi.createFrom().emitter(e -> this.emitter = e);
    }

    @Override
    public Multi<StateEvent> stream() { return multi; }

    public void emit(StateEvent event) {
        if (emitter != null) emitter.emit(event);
    }

    public void heroRaid(NodeId room) {
        emit(new StateEvent(room, NodeStatus.ABSENT, "hero raid"));
    }

    public void caveIn(NodeId tunnel) {
        emit(new StateEvent(tunnel, NodeStatus.ABSENT, "cave-in"));
    }

    public void creatureRevolt(NodeId creature) {
        emit(new StateEvent(creature, NodeStatus.DEGRADED, "revolt"));
    }
}
```

- [ ] **Step 4: Verify build compiles**

Run: `mvn compile -pl testing`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add testing/src/
git commit -m "feat(#1): add testing module — MockNodeProvisioner, MockActualStateAdapter, CannedEventSource"
```

---

## Task 11: Engine adapter — CaseTransitionExecutor and workflow generation

**Files:**
- Create: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/TransitionWorkflowGenerator.java`
- Create: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateWorkerFunction.java`
- Create: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/CaseTransitionExecutor.java`
- Test: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/TransitionWorkflowGeneratorTest.java`

The engine adapter generates `CaseDefinition` objects with `Worker(Workflow)` phases. The `TransitionWorkflowGenerator` builds Serverless Workflow definitions from ordered steps. The `DesiredStateWorkerFunction` wraps `NodeProvisioner` as a casehub-engine worker function.

**Note to implementor:** This task requires understanding of casehub-engine's `CaseDefinition.builder()`, `Worker.builder().function(Workflow)`, and `Binding.builder()` APIs. Use IntelliJ MCP (`mcp__intellij-index__ide_find_class`, `ide_read_file`) to explore `io.casehub.api.model.CaseDefinition`, `Worker`, `Binding`, `Capability`, `Trigger` in casehub-engine's api/ module. Also explore `io.serverlessworkflow.api.types.Workflow` for workflow definition construction.

- [ ] **Step 1: Write failing tests for TransitionWorkflowGenerator**

```java
package io.casehub.desiredstate.engine;

import static org.assertj.core.api.Assertions.*;
import io.casehub.desiredstate.api.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransitionWorkflowGeneratorTest {

    record Spec(String name) implements NodeSpec {}

    private DesiredNode node(String id) {
        return new DesiredNode(new NodeId(id), new Spec(id), false);
    }

    @Test
    void generatesWorkflowFromSteps() {
        var steps = List.of(
            new OrderedStep(node("a"), StepAction.PROVISION),
            new OrderedStep(node("b"), StepAction.PROVISION));
        var generator = new TransitionWorkflowGenerator();
        var workflow = generator.generate("grow-phase", steps);
        assertThat(workflow).isNotNull();
        assertThat(workflow.getDocument().getName()).isEqualTo("grow-phase");
    }

    @Test
    void emptySteps_emptyWorkflow() {
        var generator = new TransitionWorkflowGenerator();
        var workflow = generator.generate("empty", List.of());
        assertThat(workflow).isNotNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl engine-adapter -Dtest=TransitionWorkflowGeneratorTest`
Expected: FAIL

- [ ] **Step 3: Implement TransitionWorkflowGenerator**

The generator builds a Serverless Workflow definition where each step is a `call` task dispatching to the DesiredStateWorkerFunction. Explore the Serverless Workflow Java SDK API (`io.serverlessworkflow.api.types.*`) via IntelliJ MCP to determine the exact builder pattern. The workflow document name, version, and task list are the core elements.

```java
package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.OrderedStep;
import io.serverlessworkflow.api.types.*;
import java.util.*;

public class TransitionWorkflowGenerator {

    public Workflow generate(String name, List<OrderedStep> steps) {
        // Build a Serverless Workflow definition with one task per step.
        // Each task uses call: casehub:dispatch to invoke NodeProvisioner.
        // Independent steps at the same topological level become parallel fork branches.
        // Implementor: explore io.serverlessworkflow.api.types.Workflow via IntelliJ MCP
        // to determine the exact construction API for the version on the classpath.
        var workflow = new Workflow();
        var document = new Document();
        document.setName(name);
        document.setDsl("1.0.0");
        document.setVersion("1.0.0");
        document.setNamespace("io.casehub.desiredstate");
        workflow.setDocument(document);

        if (!steps.isEmpty()) {
            var tasks = new TaskList();
            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                var taskItem = new TaskItem();
                taskItem.setName(step.action().name().toLowerCase() + "-" + step.node().id().value());
                var callTask = new CallTask();
                callTask.setCall("casehub:dispatch");
                var input = new TaskBase.Input();
                input.setFrom(Map.of(
                    "nodeId", step.node().id().value(),
                    "nodeType", step.node().type().value(),
                    "action", step.action().name()));
                callTask.setInput(input);
                taskItem.setTask(callTask);
                tasks.add(taskItem);
            }
            workflow.setDo(tasks);
        }
        return workflow;
    }
}
```

**Important:** The exact Serverless Workflow Java SDK API may differ from this sketch. The implementor MUST use IntelliJ MCP to explore the actual `Workflow`, `Document`, `TaskList`, `TaskItem`, `CallTask` types on the classpath and adjust the construction code accordingly.

- [ ] **Step 4: Implement DesiredStateWorkerFunction**

```java
package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class DesiredStateWorkerFunction {

    private final NodeProvisioner provisioner;

    @Inject
    public DesiredStateWorkerFunction(NodeProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    public Map<String, Object> execute(Map<String, Object> input, DesiredStateGraph graph, String tenancyId) {
        var nodeId = new NodeId((String) input.get("nodeId"));
        var action = StepAction.valueOf((String) input.get("action"));
        var node = graph.nodes().get(nodeId);
        if (node == null) {
            return Map.of("status", "skipped", "reason", "node not found in graph");
        }

        return switch (action) {
            case PROVISION -> {
                var ctx = new ProvisionContext(tenancyId, graph);
                var result = provisioner.provision(node, ctx);
                yield switch (result) {
                    case ProvisionResult.Success s -> Map.of("status", "success");
                    case ProvisionResult.Failed f -> Map.of("status", "failed", "reason", f.reason());
                };
            }
            case DEPROVISION -> {
                var ctx = new DeprovisionContext(tenancyId, graph);
                var result = provisioner.deprovision(node, ctx);
                yield switch (result) {
                    case DeprovisionResult.Success s -> Map.of("status", "success");
                    case DeprovisionResult.Failed f -> Map.of("status", "failed", "reason", f.reason());
                };
            }
        };
    }
}
```

- [ ] **Step 5: Implement CaseTransitionExecutor**

```java
package io.casehub.desiredstate.engine;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.*;
import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class CaseTransitionExecutor implements TransitionExecutor {

    private final CaseHubRuntime caseHubRuntime;
    private final TransitionWorkflowGenerator workflowGenerator;

    @Inject
    public CaseTransitionExecutor(CaseHubRuntime caseHubRuntime) {
        this.caseHubRuntime = caseHubRuntime;
        this.workflowGenerator = new TransitionWorkflowGenerator();
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan) {
        return Uni.createFrom().completionStage(() -> {
            var pruneWorkflow = workflowGenerator.generate(
                "desiredstate-prune", plan.removals());
            var growWorkflow = workflowGenerator.generate(
                "desiredstate-grow", plan.additions());

            var pruneWorker = Worker.builder()
                .name("prune-phase")
                .capabilities(new Capability("desiredstate-prune"))
                .function(pruneWorkflow)
                .build();

            var growWorker = Worker.builder()
                .name("grow-phase")
                .capabilities(new Capability("desiredstate-grow"))
                .function(growWorkflow)
                .build();

            var caseDefinition = CaseDefinition.builder()
                .namespace("io.casehub.desiredstate")
                .name("reconciliation-cycle")
                .version("1.0.0")
                .title("Desired State Reconciliation")
                .workers(pruneWorker, growWorker)
                .bindings(
                    Binding.builder()
                        .name("start-prune")
                        .capability(new Capability("desiredstate-prune"))
                        .on(Trigger.onCaseStarted())
                        .build(),
                    Binding.builder()
                        .name("start-grow")
                        .capability(new Capability("desiredstate-grow"))
                        .on(Trigger.onComplete("prune-phase"))
                        .build())
                .build();

            Map<String, Object> inputData = Map.of(
                "removals", plan.removals().size(),
                "additions", plan.additions().size());

            return caseHubRuntime.startCase(caseDefinition, inputData);
        }).map(caseId -> {
            var outcomes = new LinkedHashMap<NodeId, StepOutcome>();
            for (var step : plan.removals()) {
                outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            }
            for (var step : plan.additions()) {
                outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            }
            return new TransitionResult(outcomes);
        });
    }
}
```

**Note:** This initial implementation starts the case and optimistically reports success. A production version would observe `CaseLifecycleEvent` completion events and map actual case outcomes to `StepOutcome`. That event-observation loop is a follow-up refinement.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -pl engine-adapter -Dtest=TransitionWorkflowGeneratorTest`
Expected: PASS

- [ ] **Step 7: Verify full build**

Run: `mvn compile -pl engine-adapter`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add engine-adapter/src/
git commit -m "feat(#1): add engine adapter — CaseTransitionExecutor, TransitionWorkflowGenerator, DesiredStateWorkerFunction"
```

---

## Task 12: Nefarious Dungeons — Domain model and SPI implementations

**Files:**
- Create: all files listed under `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/`
- Test: `examples/dungeon/src/test/java/io/casehub/desiredstate/example/dungeon/DungeonTest.java`

- [ ] **Step 1: Write failing end-to-end test**

```java
package io.casehub.desiredstate.example.dungeon;

import static org.assertj.core.api.Assertions.*;
import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DungeonTest {

    private DungeonWorld world;
    private DungeonGoalCompiler compiler;
    private GoblinProvisioner provisioner;
    private DungeonActualStateAdapter adapter;
    private DefaultDesiredStateGraphFactory graphFactory;
    private TransitionPlanner planner;

    @BeforeEach
    void setUp() {
        world = new DungeonWorld();
        compiler = new DungeonGoalCompiler();
        provisioner = new GoblinProvisioner(world);
        adapter = new DungeonActualStateAdapter(world);
        graphFactory = new DefaultDesiredStateGraphFactory();
        planner = new TransitionPlanner();
    }

    @Test
    void buildBasicDungeon() {
        var blueprint = DungeonBlueprint.builder()
            .room("lair", "Creature housing", 10)
            .room("hatchery", "Food production", 8)
            .room("library", "Research facility", 12)
            .creature("dark-wizard", "library")
            .build();

        var graph = compiler.compile(blueprint, graphFactory);
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.dependenciesOf(new NodeId("dark-wizard")))
            .containsExactly(new NodeId("library"));

        var actual = adapter.readActual(graph);
        var plan = planner.plan(graph, actual);
        assertThat(plan.additions()).hasSize(4);

        for (var step : plan.additions()) {
            provisioner.provision(step.node(),
                new ProvisionContext("dungeon-1", graph));
        }

        assertThat(world.roomState("lair")).isEqualTo(DungeonWorld.State.BUILT);
        assertThat(world.roomState("library")).isEqualTo(DungeonWorld.State.BUILT);
        assertThat(world.creatureState("dark-wizard")).isEqualTo(DungeonWorld.State.PRESENT);
    }

    @Test
    void heroRaid_destroysRoom() {
        world.setRoom("library", DungeonWorld.State.BUILT);
        world.setCreature("dark-wizard", DungeonWorld.State.PRESENT);
        world.destroyRoom("library");

        assertThat(world.roomState("library")).isEqualTo(DungeonWorld.State.DESTROYED);
    }

    @Test
    void heroRaidFaultPolicy_rebuildsRoom() {
        var policy = new HeroRaidFaultPolicy();
        var graph = graphFactory.empty()
            .withNode(new DesiredNode(new NodeId("library"), new DungeonRoomSpec("library", "Research", 12), false));

        var event = new FaultEvent(new NodeId("library"), FaultType.NODE_DESTROYED, "hero raid");
        var mutations = policy.onFault(event, graph);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
    }

    @Test
    void dungeonBlueprint_multiDependencyCreature() {
        var blueprint = DungeonBlueprint.builder()
            .room("crypt", "Undead housing", 10)
            .room("library", "Research", 12)
            .creature("necromancer", "crypt", "library")
            .build();

        var graph = compiler.compile(blueprint, graphFactory);
        assertThat(graph.dependenciesOf(new NodeId("necromancer")))
            .containsExactlyInAnyOrder(new NodeId("crypt"), new NodeId("library"));
    }

    @Test
    void humanNode_dragonRecruitment() {
        var blueprint = DungeonBlueprint.builder()
            .room("lair", "Creature housing", 10)
            .humanCreature("dragon", "lair")
            .build();

        var graph = compiler.compile(blueprint, graphFactory);
        assertThat(graph.nodes().get(new NodeId("dragon")).requiresHuman()).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl examples/dungeon -Dtest=DungeonTest`
Expected: FAIL

- [ ] **Step 3: Implement domain types**

`DungeonNodeTypes.java`:
```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeType;

public final class DungeonNodeTypes {
    public static final NodeType ROOM = new NodeType("room");
    public static final NodeType CREATURE = new NodeType("creature");
    public static final NodeType TRAP = new NodeType("trap");
    private DungeonNodeTypes() {}
}
```

`DungeonRoomSpec.java`:
```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeSpec;

public record DungeonRoomSpec(String name, String description, int size) implements NodeSpec {}
```

`CreatureSpec.java`:
```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeSpec;

public record CreatureSpec(String species, int level) implements NodeSpec {}
```

`TrapSpec.java`:
```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeSpec;

public record TrapSpec(String type, int damage) implements NodeSpec {}
```

`DungeonWorld.java`:
```java
package io.casehub.desiredstate.example.dungeon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DungeonWorld {

    public enum State { BUILT, DESTROYED, DEGRADED, PRESENT, FLED, DEAD, ARMED, TRIGGERED }

    private final Map<String, State> rooms = new ConcurrentHashMap<>();
    private final Map<String, State> creatures = new ConcurrentHashMap<>();
    private final Map<String, State> traps = new ConcurrentHashMap<>();

    public void setRoom(String id, State state) { rooms.put(id, state); }
    public void setCreature(String id, State state) { creatures.put(id, state); }
    public void setTrap(String id, State state) { traps.put(id, state); }

    public State roomState(String id) { return rooms.getOrDefault(id, null); }
    public State creatureState(String id) { return creatures.getOrDefault(id, null); }
    public State trapState(String id) { return traps.getOrDefault(id, null); }

    public void destroyRoom(String id) { rooms.put(id, State.DESTROYED); }
    public void removeCreature(String id) { creatures.put(id, State.FLED); }

    public Map<String, State> allRooms() { return Map.copyOf(rooms); }
    public Map<String, State> allCreatures() { return Map.copyOf(creatures); }
    public Map<String, State> allTraps() { return Map.copyOf(traps); }
}
```

- [ ] **Step 4: Implement DungeonBlueprint**

```java
package io.casehub.desiredstate.example.dungeon;

import java.util.*;

public class DungeonBlueprint {

    public record RoomEntry(String id, String description, int size) {}
    public record CreatureEntry(String id, List<String> roomDeps, boolean requiresHuman) {}
    public record TrapEntry(String id, String type, int damage, String roomDep) {}

    private final List<RoomEntry> rooms;
    private final List<CreatureEntry> creatures;
    private final List<TrapEntry> traps;

    private DungeonBlueprint(List<RoomEntry> rooms, List<CreatureEntry> creatures, List<TrapEntry> traps) {
        this.rooms = List.copyOf(rooms);
        this.creatures = List.copyOf(creatures);
        this.traps = List.copyOf(traps);
    }

    public List<RoomEntry> rooms() { return rooms; }
    public List<CreatureEntry> creatures() { return creatures; }
    public List<TrapEntry> traps() { return traps; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<RoomEntry> rooms = new ArrayList<>();
        private final List<CreatureEntry> creatures = new ArrayList<>();
        private final List<TrapEntry> traps = new ArrayList<>();

        public Builder room(String id, String description, int size) {
            rooms.add(new RoomEntry(id, description, size));
            return this;
        }

        public Builder creature(String id, String... roomDeps) {
            creatures.add(new CreatureEntry(id, List.of(roomDeps), false));
            return this;
        }

        public Builder humanCreature(String id, String... roomDeps) {
            creatures.add(new CreatureEntry(id, List.of(roomDeps), true));
            return this;
        }

        public Builder trap(String id, String type, int damage, String roomDep) {
            traps.add(new TrapEntry(id, type, damage, roomDep));
            return this;
        }

        public DungeonBlueprint build() {
            return new DungeonBlueprint(rooms, creatures, traps);
        }
    }
}
```

- [ ] **Step 5: Implement DungeonGoalCompiler**

```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;

public class DungeonGoalCompiler implements GoalCompiler<DungeonBlueprint> {

    @Override
    public DesiredStateGraph compile(DungeonBlueprint blueprint, DesiredStateGraphFactory factory) {
        var graph = factory.empty();

        for (var room : blueprint.rooms()) {
            graph = graph.withNode(new DesiredNode(new NodeId(room.id()), new DungeonRoomSpec(room.id(), room.description(), room.size()), false));
        }

        for (var creature : blueprint.creatures()) {
            graph = graph.withNode(new DesiredNode(new NodeId(creature.id()), new CreatureSpec(creature.id(), 1), creature.requiresHuman()));
            for (var dep : creature.roomDeps()) {
                graph = graph.withDependency(new Dependency(new NodeId(creature.id()), new NodeId(dep)));
            }
        }

        for (var trap : blueprint.traps()) {
            graph = graph.withNode(new DesiredNode(new NodeId(trap.id()), new TrapSpec(trap.type(), trap.damage()), false));
            graph = graph.withDependency(new Dependency(new NodeId(trap.id()), new NodeId(trap.roomDep())));
        }

        return graph;
    }
}
```

- [ ] **Step 6: Implement GoblinProvisioner**

```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;

public class GoblinProvisioner implements NodeProvisioner {

    private final DungeonWorld world;

    public GoblinProvisioner(DungeonWorld world) {
        this.world = world;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        return switch (node.type().value()) {
            case "room" -> {
                world.setRoom(node.id().value(), DungeonWorld.State.BUILT);
                yield new ProvisionResult.Success();
            }
            case "creature" -> {
                world.setCreature(node.id().value(), DungeonWorld.State.PRESENT);
                yield new ProvisionResult.Success();
            }
            case "trap" -> {
                world.setTrap(node.id().value(), DungeonWorld.State.ARMED);
                yield new ProvisionResult.Success();
            }
            default -> new ProvisionResult.Failed("unknown node type: " + node.type().value());
        };
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        return switch (node.type().value()) {
            case "room" -> {
                world.destroyRoom(node.id().value());
                yield new DeprovisionResult.Success();
            }
            case "creature" -> {
                world.removeCreature(node.id().value());
                yield new DeprovisionResult.Success();
            }
            case "trap" -> {
                world.setTrap(node.id().value(), DungeonWorld.State.TRIGGERED);
                yield new DeprovisionResult.Success();
            }
            default -> new DeprovisionResult.Failed("unknown node type: " + node.type().value());
        };
    }
}
```

- [ ] **Step 7: Implement DungeonActualStateAdapter**

```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;
import java.util.HashMap;

public class DungeonActualStateAdapter implements ActualStateAdapter {

    private final DungeonWorld world;

    public DungeonActualStateAdapter(DungeonWorld world) {
        this.world = world;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        var statuses = new HashMap<NodeId, NodeStatus>();

        for (var nodeId : desired.nodes().keySet()) {
            var id = nodeId.value();
            var node = desired.nodes().get(nodeId);
            var state = switch (node.type().value()) {
                case "room" -> world.roomState(id);
                case "creature" -> world.creatureState(id);
                case "trap" -> world.trapState(id);
                default -> null;
            };

            if (state == null) {
                statuses.put(nodeId, NodeStatus.ABSENT);
            } else {
                statuses.put(nodeId, switch (state) {
                    case BUILT, PRESENT, ARMED -> NodeStatus.PRESENT;
                    case DESTROYED, FLED, DEAD, TRIGGERED -> NodeStatus.ABSENT;
                    case DEGRADED -> NodeStatus.DEGRADED;
                });
            }
        }

        return new ActualState(statuses);
    }
}
```

- [ ] **Step 8: Implement DungeonEventSource and HeroRaidFaultPolicy**

`DungeonEventSource.java`:
```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;

public class DungeonEventSource implements EventSource {

    private MultiEmitter<? super StateEvent> emitter;
    private final Multi<StateEvent> multi;

    public DungeonEventSource() {
        this.multi = Multi.createFrom().emitter(e -> this.emitter = e);
    }

    @Override
    public Multi<StateEvent> stream() { return multi; }

    public void heroRaid(String roomId) {
        if (emitter != null) {
            emitter.emit(new StateEvent(new NodeId(roomId), NodeStatus.ABSENT, "hero raid"));
        }
    }

    public void caveIn(String tunnelId) {
        if (emitter != null) {
            emitter.emit(new StateEvent(new NodeId(tunnelId), NodeStatus.ABSENT, "cave-in"));
        }
    }

    public void creatureRevolt(String creatureId) {
        if (emitter != null) {
            emitter.emit(new StateEvent(new NodeId(creatureId), NodeStatus.DEGRADED, "revolt"));
        }
    }

    public void treasuryLooted() {
        if (emitter != null) {
            emitter.emit(new StateEvent(new NodeId("treasury"), NodeStatus.ABSENT, "looted"));
        }
    }
}
```

`HeroRaidFaultPolicy.java`:
```java
package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;
import java.util.List;

public class HeroRaidFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        if (event.type() != FaultType.NODE_DESTROYED) {
            return List.of();
        }

        var node = current.nodes().get(event.node());
        if (node == null) {
            return List.of();
        }

        return List.of(new GraphMutation.AddNode(node));
    }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `mvn test -pl examples/dungeon -Dtest=DungeonTest`
Expected: PASS (5 tests)

- [ ] **Step 10: Commit**

```bash
git add examples/dungeon/src/
git commit -m "feat(#1): add Nefarious Dungeons example — domain model, all SPI implementations, tests"
```

---

## Task 13: Nefarious Dungeons — 2D tile visualizer

**Files:**
- Create: `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/DungeonVisualizer.java`
- Create: `examples/dungeon/src/main/resources/META-INF/resources/index.html`

- [ ] **Step 1: Implement SSE endpoint**

```java
package io.casehub.desiredstate.example.dungeon;

import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;
import java.time.Duration;

@Path("/dungeon")
public class DungeonVisualizer {

    @Inject
    DungeonWorld world;

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<DungeonSnapshot> stream() {
        return Multi.createFrom().ticks().every(Duration.ofMillis(500))
            .map(tick -> new DungeonSnapshot(
                world.allRooms(), world.allCreatures(), world.allTraps()));
    }

    public record DungeonSnapshot(
        java.util.Map<String, DungeonWorld.State> rooms,
        java.util.Map<String, DungeonWorld.State> creatures,
        java.util.Map<String, DungeonWorld.State> traps) {}
}
```

- [ ] **Step 2: Create index.html with 2D tile grid**

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Nefarious Dungeons</title>
<style>
  body { font-family: monospace; background: #1a1a2e; color: #e0e0e0; padding: 20px; }
  h1 { color: #c0392b; text-align: center; }
  .grid { display: grid; grid-template-columns: repeat(6, 80px); gap: 4px; margin: 20px auto; width: fit-content; }
  .tile { width: 80px; height: 80px; display: flex; flex-direction: column; align-items: center;
          justify-content: center; border: 1px solid #333; font-size: 11px; text-align: center; border-radius: 4px; }
  .empty { background: #2c2c3e; color: #555; }
  .room-built { background: #2d5016; border-color: #4a8c28; }
  .room-destroyed { background: #5c1a1a; border-color: #8b2500; animation: flash 0.5s; }
  .creature-present { background: #1a3a5c; border-color: #2980b9; }
  .creature-fled { background: #3d3d00; border-color: #666; opacity: 0.5; }
  .trap-armed { background: #5c1a3a; border-color: #c0392b; }
  .icon { font-size: 24px; }
  .status { font-size: 9px; margin-top: 2px; }
  #log { max-height: 200px; overflow-y: auto; background: #0d0d1a; padding: 10px; margin-top: 20px;
         border: 1px solid #333; font-size: 11px; }
  @keyframes flash { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }
</style>
</head>
<body>
<h1>Nefarious Dungeons</h1>
<div id="grid" class="grid"></div>
<div id="log"></div>
<script>
const ICONS = { room: '🏰', creature: '👹', trap: '⚔️' };
const grid = document.getElementById('grid');
const log = document.getElementById('log');

function render(snapshot) {
  grid.innerHTML = '';
  const allItems = [];
  for (const [id, state] of Object.entries(snapshot.rooms || {})) {
    allItems.push({ id, type: 'room', state });
  }
  for (const [id, state] of Object.entries(snapshot.creatures || {})) {
    allItems.push({ id, type: 'creature', state });
  }
  for (const [id, state] of Object.entries(snapshot.traps || {})) {
    allItems.push({ id, type: 'trap', state });
  }
  while (allItems.length < 12) allItems.push(null);
  for (const item of allItems) {
    const tile = document.createElement('div');
    if (!item) { tile.className = 'tile empty'; tile.textContent = '·'; }
    else {
      const cls = item.type === 'room' ? (item.state === 'BUILT' ? 'room-built' : 'room-destroyed')
                : item.type === 'creature' ? (item.state === 'PRESENT' ? 'creature-present' : 'creature-fled')
                : 'trap-armed';
      tile.className = 'tile ' + cls;
      tile.innerHTML = '<span class="icon">' + (ICONS[item.type]||'?') + '</span>'
                     + '<span>' + item.id + '</span>'
                     + '<span class="status">' + item.state + '</span>';
    }
    grid.appendChild(tile);
  }
}

const es = new EventSource('/dungeon/stream');
es.onmessage = e => render(JSON.parse(e.data));
es.onerror = () => log.innerHTML += '<div style="color:#c0392b">SSE connection lost</div>';
</script>
</body>
</html>
```

- [ ] **Step 3: Verify the example module compiles**

Run: `mvn compile -pl examples/dungeon`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add examples/dungeon/src/
git commit -m "feat(#1): add Nefarious Dungeons 2D tile visualizer — SSE endpoint + vanilla HTML/CSS/JS"
```

---

## Task 14: Full build verification and cleanup

- [ ] **Step 1: Run full build across all modules**

Run: `mvn --batch-mode install -pl api,runtime,testing,engine-adapter,examples/dungeon`
Expected: BUILD SUCCESS with all tests passing

- [ ] **Step 2: Fix any compilation or test failures discovered during full build**

Address failures module by module. Common issues:
- Missing CDI beans for `@QuarkusTest` — add `@DefaultBean` stubs
- Jandex indexing — ensure all modules have the jandex plugin
- Dependency resolution — ensure parent pom.xml dependencyManagement covers all artifacts

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix(#1): resolve full-build issues"
```

- [ ] **Step 4: Final commit summary**

Run `git log --oneline` to verify the commit chain is clean and tells the implementation story.
