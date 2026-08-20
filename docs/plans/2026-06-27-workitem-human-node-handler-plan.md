# WorkItem Human Node Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hard-coded `requiresHuman` skip in `SimpleTransitionExecutor` with a `HumanNodeHandler` SPI, and provide a work-adapter module that creates WorkItems via the `WorkItemCreator` SPI.

**Architecture:** New `HumanNodeHandler` SPI in `api/`, `@DefaultBean` no-op in `runtime/`, STE delegates to it. New `work-adapter/` module provides `WorkItemHumanNodeHandler` that creates WorkItems via `WorkItemCreator` (from `casehub-work-api`, extracted in work#275). Follows the `engine-adapter/` pattern: adapter modules depend on API modules only.

**Tech Stack:** Java 21, Quarkus 3.32.2, Mutiny, CDI (Arc), JUnit 5, AssertJ

## Global Constraints

- All dependencies in adapter modules must be API/SPI modules — zero runtime deps
- `@DefaultBean` for fallback implementations, `@ApplicationScoped` for displacing implementations
- `StepOutcome.Skipped` is the return convention for human nodes (not `Succeeded`, not a new variant)
- Hand-written mocks in tests (no Mockito — matches project style)
- Every commit references issue #43

---

## File Structure

**api/ module:**
| File | Action | Responsibility |
|------|--------|---------------|
| `api/src/main/java/io/casehub/desiredstate/api/HumanNodeHandler.java` | Create | SPI interface — single method `onProvision` |

**runtime/ module:**
| File | Action | Responsibility |
|------|--------|---------------|
| `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandler.java` | Create | `@DefaultBean` fallback — returns `Skipped` |
| `runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java` | Modify | New constructor param, delegate `requiresHuman` to handler |
| `runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java` | Modify | Update constructor calls, add delegation tests |
| `runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandlerTest.java` | Create | Verify Skipped outcome with expected message |

**work-adapter/ module (new):**
| File | Action | Responsibility |
|------|--------|---------------|
| `work-adapter/pom.xml` | Create | Module POM — deps on desiredstate-api + work-api only |
| `work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandler.java` | Create | Creates WorkItems via `WorkItemCreator` SPI |
| `work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandlerTest.java` | Create | Tests with mock `WorkItemCreator` |

**Parent:**
| File | Action | Responsibility |
|------|--------|---------------|
| `pom.xml` | Modify | Add `<module>work-adapter</module>` + managed dep |

**Cross-repo (casehub-work, work#275):**
| File | Action | Responsibility |
|------|--------|---------------|
| `api/src/main/java/io/casehub/work/api/WorkItemCreator.java` | Create | SPI interface for WorkItem creation + lookup |
| `api/src/main/java/io/casehub/work/api/WorkItemRef.java` | Create | Typed response — `record WorkItemRef(UUID id)` |
| `api/src/main/java/io/casehub/work/api/WorkItemCreateRequest.java` | Move from runtime | Pure value type with builder |
| `api/src/main/java/io/casehub/work/api/WorkItemPriority.java` | Move from runtime | Simple enum |
| `api/src/main/java/io/casehub/work/api/WorkItemLabelRequest.java` | Move from runtime | Record, depends on `LabelPersistence` (already in api) |
| `runtime/src/main/java/io/casehub/work/runtime/service/WorkItemService.java` | Modify | Implements `WorkItemCreator`, adds `findActiveByCallerRef` |

---

### Task 1: HumanNodeHandler SPI + STE Delegation + NoOp Fallback

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/HumanNodeHandler.java`
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandler.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandlerTest.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java`

**Interfaces:**
- Consumes: `DesiredNode`, `ProvisionContext`, `StepOutcome` (existing API types)
- Produces: `HumanNodeHandler` interface (consumed by Task 3), `NoOpHumanNodeHandler` (consumed by STE)

- [ ] **Step 1: Create HumanNodeHandler interface**

```java
// api/src/main/java/io/casehub/desiredstate/api/HumanNodeHandler.java
package io.casehub.desiredstate.api;

public interface HumanNodeHandler {
    StepOutcome onProvision(DesiredNode node, ProvisionContext context);
}
```

- [ ] **Step 2: Create NoOpHumanNodeHandler**

```java
// runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandler.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpHumanNodeHandler implements HumanNodeHandler {
    @Override
    public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
        return new StepOutcome.Skipped("requires human — no HumanNodeHandler configured");
    }
}
```

- [ ] **Step 3: Write failing test — STE delegates human nodes to handler**

Add to `SimpleTransitionExecutorTest.java`:

```java
@Test
void delegatesHumanNodesToHandler() {
    HumanNodeHandler handler = (node, context) ->
        new StepOutcome.Skipped("test handler: " + node.id().value());

    SimpleTransitionExecutor handlerExecutor =
        new SimpleTransitionExecutor(mockProvisioner, handler);

    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec("human"), true);
    DesiredNode normalNode = new DesiredNode(NodeId.of("n1"), new TestSpec("normal"), false);

    DesiredStateGraph graph = factory.of(List.of(humanNode, normalNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(
            new OrderedStep(humanNode, StepAction.PROVISION),
            new OrderedStep(normalNode, StepAction.PROVISION)
        ),
        graph, graph
    );

    TransitionResult result = handlerExecutor.execute(plan, "tenant1")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem()
        .getItem();

    StepOutcome humanOutcome = result.outcomes().get(NodeId.of("h1"));
    assertInstanceOf(StepOutcome.Skipped.class, humanOutcome);
    assertEquals("test handler: h1", ((StepOutcome.Skipped) humanOutcome).reason());

    assertTrue(result.outcomes().get(NodeId.of("n1")) instanceof StepOutcome.Succeeded);

    assertEquals(1, mockProvisioner.callOrder.size());
    assertEquals("provision:n1", mockProvisioner.callOrder.get(0));
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=SimpleTransitionExecutorTest#delegatesHumanNodesToHandler`
Expected: COMPILATION ERROR — `SimpleTransitionExecutor` constructor does not accept `HumanNodeHandler`

- [ ] **Step 5: Modify SimpleTransitionExecutor to accept and use HumanNodeHandler**

Replace the constructor and `executeProvision` method in `SimpleTransitionExecutor.java`:

```java
private final NodeProvisioner provisioner;
private final HumanNodeHandler humanNodeHandler;

public SimpleTransitionExecutor(NodeProvisioner provisioner, HumanNodeHandler humanNodeHandler) {
    this.provisioner = provisioner;
    this.humanNodeHandler = humanNodeHandler;
}
```

In `executeProvision`, move `ProvisionContext` above the `requiresHuman` check and delegate:

```java
private StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
    Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("provision")
            .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
            .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
            .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman())
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
        ProvisionContext context = new ProvisionContext(tenancyId, graph);
        if (node.requiresHuman()) {
            return humanNodeHandler.onProvision(node, context);
        }

        ProvisionResult result = provisioner.provision(node, context);

        return switch (result) {
            case ProvisionResult.Success ignored -> new StepOutcome.Succeeded();
            case ProvisionResult.Failed f -> {
                span.setStatus(StatusCode.ERROR, f.reason());
                yield new StepOutcome.Failed(f.reason());
            }
            case ProvisionResult.PendingApproval pa ->
                new StepOutcome.Skipped("pending approval: " + pa.planReference());
        };
    } finally {
        span.end();
    }
}
```

- [ ] **Step 6: Fix existing tests — update constructor calls**

In `SimpleTransitionExecutorTest.setUp()`:

```java
@BeforeEach
void setUp() {
    factory = new DefaultDesiredStateGraphFactory();
    mockProvisioner = new MockNodeProvisioner();
    executor = new SimpleTransitionExecutor(mockProvisioner, new NoOpHumanNodeHandler());
}
```

Add import: `import io.casehub.desiredstate.api.HumanNodeHandler;`

Update the existing `skipsHumanNodes()` test — it should now verify the NoOp message:

```java
@Test
void skipsHumanNodesWithNoOpHandler() {
    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec("human"), true);
    DesiredNode normalNode = new DesiredNode(NodeId.of("n1"), new TestSpec("normal"), false);

    DesiredStateGraph graph = factory.of(List.of(humanNode, normalNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(
            new OrderedStep(humanNode, StepAction.PROVISION),
            new OrderedStep(normalNode, StepAction.PROVISION)
        ),
        graph, graph
    );

    TransitionResult result = executor.execute(plan, "default")
        .subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem()
        .getItem();

    StepOutcome humanOutcome = result.outcomes().get(NodeId.of("h1"));
    assertTrue(humanOutcome instanceof StepOutcome.Skipped);
    assertEquals("requires human — no HumanNodeHandler configured",
        ((StepOutcome.Skipped) humanOutcome).reason());

    assertTrue(result.outcomes().get(NodeId.of("n1")) instanceof StepOutcome.Succeeded);

    assertEquals(1, mockProvisioner.callOrder.size());
    assertEquals("provision:n1", mockProvisioner.callOrder.get(0));
}
```

- [ ] **Step 7: Write NoOpHumanNodeHandler test**

```java
// runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandlerTest.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoOpHumanNodeHandlerTest {

    record TestSpec(String value) implements NodeSpec {}

    @Test
    void returnsSkippedWithConfigurationMessage() {
        NoOpHumanNodeHandler handler = new NoOpHumanNodeHandler();
        DesiredNode node = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), true);
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(node), List.of());
        ProvisionContext context = new ProvisionContext("tenant1", graph);

        StepOutcome outcome = handler.onProvision(node, context);

        assertInstanceOf(StepOutcome.Skipped.class, outcome);
        assertEquals("requires human — no HumanNodeHandler configured",
            ((StepOutcome.Skipped) outcome).reason());
    }
}
```

- [ ] **Step 8: Write test — handler receives correct ProvisionContext**

Add to `SimpleTransitionExecutorTest.java`:

```java
@Test
void handlerReceivesCorrectProvisionContext() {
    String[] capturedTenancyId = {null};
    DesiredStateGraph[] capturedGraph = {null};

    HumanNodeHandler capturingHandler = (node, context) -> {
        capturedTenancyId[0] = context.tenancyId();
        capturedGraph[0] = context.graph();
        return new StepOutcome.Skipped("captured");
    };

    SimpleTransitionExecutor capturingExecutor =
        new SimpleTransitionExecutor(mockProvisioner, capturingHandler);

    DesiredNode humanNode = new DesiredNode(NodeId.of("h1"), new TestSpec("human"), true);

    DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());

    TransitionPlan plan = new TransitionPlan(
        List.of(),
        List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
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

- [ ] **Step 9: Run all tests**

Run: `mvn --batch-mode test -pl api,runtime`
Expected: ALL PASS

- [ ] **Step 10: Commit**

```bash
git add api/src/main/java/io/casehub/desiredstate/api/HumanNodeHandler.java \
       runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandler.java \
       runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java \
       runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java \
       runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpHumanNodeHandlerTest.java
git commit -m "feat(#43): HumanNodeHandler SPI — STE delegates requiresHuman to handler"
```

---

### Task 2: WorkItemCreator SPI Extraction (cross-repo: casehub-work, work#275)

> **Cross-repo work.** This task modifies `casehub-work` (`/Users/mdproctor/claude/casehub/work`). Create a branch `issue-275-workitem-creator-spi` there. After completing, run `mvn --batch-mode install -DskipTests` in casehub-work so the updated `casehub-work-api` artifact is available in local Maven for Task 3.

**Files:**
- Create: `api/src/main/java/io/casehub/work/api/WorkItemCreator.java`
- Create: `api/src/main/java/io/casehub/work/api/WorkItemRef.java`
- Move: `runtime/.../WorkItemPriority.java` → `api/src/main/java/io/casehub/work/api/WorkItemPriority.java`
- Move: `runtime/.../WorkItemLabelRequest.java` → `api/src/main/java/io/casehub/work/api/WorkItemLabelRequest.java`
- Move: `runtime/.../WorkItemCreateRequest.java` → `api/src/main/java/io/casehub/work/api/WorkItemCreateRequest.java`
- Modify: `runtime/src/main/java/io/casehub/work/runtime/service/WorkItemService.java`

**Interfaces:**
- Consumes: `Outcome`, `LabelPersistence` (existing casehub-work-api types)
- Produces: `WorkItemCreator` interface, `WorkItemRef`, `WorkItemCreateRequest`, `WorkItemPriority` (consumed by Task 3)

- [ ] **Step 1: Create WorkItemRef response type**

```java
// api/src/main/java/io/casehub/work/api/WorkItemRef.java
package io.casehub.work.api;

import java.util.UUID;

public record WorkItemRef(UUID id) {}
```

- [ ] **Step 2: Create WorkItemCreator interface**

```java
// api/src/main/java/io/casehub/work/api/WorkItemCreator.java
package io.casehub.work.api;

import java.util.Optional;

public interface WorkItemCreator {
    WorkItemRef create(WorkItemCreateRequest request);
    Optional<WorkItemRef> findActiveByCallerRef(String callerRef);
}
```

- [ ] **Step 3: Move value types from runtime to api**

Use IntelliJ's Move refactoring (preferred) or manually move these three files from `runtime/src/main/java/io/casehub/work/runtime/model/` to `api/src/main/java/io/casehub/work/api/`:

1. `WorkItemPriority.java` — change package to `io.casehub.work.api`
2. `WorkItemLabelRequest.java` — change package to `io.casehub.work.api`
3. `WorkItemCreateRequest.java` — change package to `io.casehub.work.api`

Update all import statements across the casehub-work codebase. The runtime module already depends on the api module, so imports resolve cleanly.

Verify: `WorkItemCreateRequest` references `Outcome` (already in api/) and `WorkItemLabelRequest` references `LabelPersistence` (already in api/). No circular dependencies.

- [ ] **Step 4: Run casehub-work tests to verify move didn't break anything**

Run: `mvn --batch-mode test` (from casehub-work root)
Expected: ALL PASS — the move is a package rename; all internal callers get updated imports.

- [ ] **Step 5: WorkItemService implements WorkItemCreator**

In `WorkItemService.java`, add the interface and implement `findActiveByCallerRef`:

```java
@ApplicationScoped
public class WorkItemService implements WorkItemCreator {
    // ... existing fields and constructor unchanged ...

    @Override
    @Transactional
    public WorkItemRef create(final WorkItemCreateRequest request) {
        // Delegate to existing create logic, wrap return
        WorkItem item = createInternal(request);
        return new WorkItemRef(item.id);
    }

    @Override
    public Optional<WorkItemRef> findActiveByCallerRef(final String callerRef) {
        return workItemStore.scanAll().stream()
                .filter(w -> callerRef.equals(w.callerRef))
                .filter(w -> w.status.isActive())
                .findFirst()
                .map(w -> new WorkItemRef(w.id));
    }
```

Rename the existing `create(WorkItemCreateRequest)` method that returns `WorkItem` to `createInternal`. Internal callers (REST endpoints, engine integration) continue to call `createInternal` for the full `WorkItem` return. The SPI's `create()` returns `WorkItemRef`.

- [ ] **Step 6: Run all casehub-work tests**

Run: `mvn --batch-mode test` (from casehub-work root)
Expected: ALL PASS

- [ ] **Step 7: Commit and install**

```bash
git add -A
git commit -m "feat(#275): extract WorkItemCreator SPI into casehub-work-api"
mvn --batch-mode install -DskipTests
```

The `mvn install` makes the updated `casehub-work-api` available in local Maven for Task 3.

---

### Task 3: work-adapter Module — WorkItemHumanNodeHandler

> **Prerequisite:** Task 2 must be complete and `mvn install`ed. Switch back to the desiredstate repo and the `issue-43-workitem-requires-human` branch.

**Files:**
- Create: `work-adapter/pom.xml`
- Modify: `pom.xml` (parent — add module + managed dep)
- Create: `work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandler.java`
- Create: `work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandlerTest.java`

**Interfaces:**
- Consumes: `HumanNodeHandler` (from Task 1), `WorkItemCreator`, `WorkItemCreateRequest`, `WorkItemPriority`, `WorkItemRef` (from Task 2)
- Produces: `WorkItemHumanNodeHandler` — `@ApplicationScoped` bean that displaces `NoOpHumanNodeHandler` when on classpath

- [ ] **Step 1: Add module to parent POM**

In `pom.xml`, add to `<modules>`:

```xml
<module>work-adapter</module>
```

Add to `<dependencyManagement>`:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-work</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: Create work-adapter/pom.xml**

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

    <artifactId>casehub-desiredstate-work</artifactId>

    <name>CaseHub Desired State :: Work Adapter</name>
    <description>WorkItem-backed HumanNodeHandler — creates WorkItems for requiresHuman
        nodes via the WorkItemCreator SPI. Displaces NoOpHumanNodeHandler by classpath
        presence when casehub-work is deployed.</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-desiredstate-api</artifactId>
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
            <artifactId>casehub-desiredstate</artifactId>
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

- [ ] **Step 3: Write the failing test — first call creates WorkItem**

```java
// work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandlerTest.java
package io.casehub.desiredstate.work;

import io.casehub.desiredstate.api.*;
import io.casehub.work.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemHumanNodeHandlerTest {

    record TestSpec(String value) implements NodeSpec {}

    private MockWorkItemCreator mockCreator;
    private WorkItemHumanNodeHandler handler;
    private DesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        mockCreator = new MockWorkItemCreator();
        handler = new WorkItemHumanNodeHandler(mockCreator);
        graphFactory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void firstCall_createsWorkItem_returnsSkippedWithId() {
        DesiredNode node = new DesiredNode(NodeId.of("thermo-1"), new TestSpec("install"), true);
        DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
        ProvisionContext context = new ProvisionContext("tenant1", graph);

        StepOutcome outcome = handler.onProvision(node, context);

        assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) outcome).reason())
            .startsWith("pending human action: WorkItem ");

        assertThat(mockCreator.lastCreateRequest).isNotNull();
        assertThat(mockCreator.lastCreateRequest.title).isEqualTo("Provision: thermo-1");
        assertThat(mockCreator.lastCreateRequest.description)
            .isEqualTo("Human provisioning required for node thermo-1 (type: iot-device)");
        assertThat(mockCreator.lastCreateRequest.category).isEqualTo("desiredstate-provision");
        assertThat(mockCreator.lastCreateRequest.callerRef)
            .isEqualTo("desiredstate:tenant1:thermo-1");
        assertThat(mockCreator.lastCreateRequest.priority).isEqualTo(WorkItemPriority.MEDIUM);
        assertThat(mockCreator.lastCreateRequest.createdBy).isEqualTo("desiredstate");
    }

    static class MockWorkItemCreator implements WorkItemCreator {
        WorkItemCreateRequest lastCreateRequest;
        WorkItemRef activeRef;

        @Override
        public WorkItemRef create(WorkItemCreateRequest request) {
            lastCreateRequest = request;
            return new WorkItemRef(UUID.randomUUID());
        }

        @Override
        public Optional<WorkItemRef> findActiveByCallerRef(String callerRef) {
            return Optional.ofNullable(activeRef);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn --batch-mode test -pl work-adapter -Dtest=WorkItemHumanNodeHandlerTest#firstCall_createsWorkItem_returnsSkippedWithId`
Expected: COMPILATION ERROR — `WorkItemHumanNodeHandler` does not exist

- [ ] **Step 5: Implement WorkItemHumanNodeHandler**

```java
// work-adapter/src/main/java/io/casehub/desiredstate/work/WorkItemHumanNodeHandler.java
package io.casehub.desiredstate.work;

import io.casehub.desiredstate.api.*;
import io.casehub.work.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@ApplicationScoped
public class WorkItemHumanNodeHandler implements HumanNodeHandler {

    private final WorkItemCreator workItemCreator;

    @Inject
    public WorkItemHumanNodeHandler(WorkItemCreator workItemCreator) {
        this.workItemCreator = workItemCreator;
    }

    @Override
    public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
        String callerRef = "desiredstate:" + context.tenancyId() + ":" + node.id().value();

        Optional<WorkItemRef> active = workItemCreator.findActiveByCallerRef(callerRef);
        if (active.isPresent()) {
            return new StepOutcome.Skipped(
                "pending human action: WorkItem " + active.get().id());
        }

        WorkItemRef created = workItemCreator.create(WorkItemCreateRequest.builder()
            .title("Provision: " + node.id().value())
            .description("Human provisioning required for node "
                + node.id().value() + " (type: " + node.type().value() + ")")
            .category("desiredstate-provision")
            .callerRef(callerRef)
            .priority(WorkItemPriority.MEDIUM)
            .createdBy("desiredstate")
            .build());

        return new StepOutcome.Skipped(
            "pending human action: WorkItem " + created.id());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn --batch-mode test -pl work-adapter -Dtest=WorkItemHumanNodeHandlerTest#firstCall_createsWorkItem_returnsSkippedWithId`
Expected: PASS

- [ ] **Step 7: Write test — subsequent call finds active WorkItem, no duplicate**

Add to `WorkItemHumanNodeHandlerTest.java`:

```java
@Test
void subsequentCall_findsActiveWorkItem_doesNotCreateDuplicate() {
    UUID existingId = UUID.randomUUID();
    mockCreator.activeRef = new WorkItemRef(existingId);

    DesiredNode node = new DesiredNode(NodeId.of("thermo-1"), new TestSpec("install"), true);
    DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
    ProvisionContext context = new ProvisionContext("tenant1", graph);

    StepOutcome outcome = handler.onProvision(node, context);

    assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
    assertThat(((StepOutcome.Skipped) outcome).reason())
        .isEqualTo("pending human action: WorkItem " + existingId);

    assertThat(mockCreator.lastCreateRequest).isNull();
}
```

- [ ] **Step 8: Run test — passes (already implemented)**

Run: `mvn --batch-mode test -pl work-adapter -Dtest=WorkItemHumanNodeHandlerTest#subsequentCall_findsActiveWorkItem_doesNotCreateDuplicate`
Expected: PASS

- [ ] **Step 9: Write test — re-provision after completion creates new WorkItem**

Add to `WorkItemHumanNodeHandlerTest.java`:

```java
@Test
void reProvisionAfterCompletion_noActiveWorkItem_createsNew() {
    // activeRef is null — simulates all prior WorkItems being terminal
    mockCreator.activeRef = null;

    DesiredNode node = new DesiredNode(NodeId.of("thermo-1"), new TestSpec("install"), true);
    DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());
    ProvisionContext context = new ProvisionContext("tenant1", graph);

    StepOutcome outcome = handler.onProvision(node, context);

    assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
    assertThat(((StepOutcome.Skipped) outcome).reason())
        .startsWith("pending human action: WorkItem ");

    assertThat(mockCreator.lastCreateRequest).isNotNull();
    assertThat(mockCreator.lastCreateRequest.callerRef)
        .isEqualTo("desiredstate:tenant1:thermo-1");
}
```

- [ ] **Step 10: Write test — different tenancyId produces different callerRef**

Add to `WorkItemHumanNodeHandlerTest.java`:

```java
@Test
void differentTenancy_differentCallerRef() {
    DesiredNode node = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), true);
    DesiredStateGraph graph = graphFactory.of(List.of(node), List.of());

    handler.onProvision(node, new ProvisionContext("tenantA", graph));
    String refA = mockCreator.lastCreateRequest.callerRef;

    mockCreator.lastCreateRequest = null;
    handler.onProvision(node, new ProvisionContext("tenantB", graph));
    String refB = mockCreator.lastCreateRequest.callerRef;

    assertThat(refA).isEqualTo("desiredstate:tenantA:n1");
    assertThat(refB).isEqualTo("desiredstate:tenantB:n1");
    assertThat(refA).isNotEqualTo(refB);
}
```

- [ ] **Step 11: Write test — different nodeId produces different callerRef**

Add to `WorkItemHumanNodeHandlerTest.java`:

```java
@Test
void differentNodeId_differentCallerRef() {
    DesiredStateGraph graph = graphFactory.empty();
    ProvisionContext context = new ProvisionContext("tenant1", graph);

    DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("v"), true);
    DesiredNode nodeB = new DesiredNode(NodeId.of("b"), new TestSpec("v"), true);

    handler.onProvision(nodeA, context);
    String refA = mockCreator.lastCreateRequest.callerRef;

    mockCreator.lastCreateRequest = null;
    handler.onProvision(nodeB, context);
    String refB = mockCreator.lastCreateRequest.callerRef;

    assertThat(refA).isEqualTo("desiredstate:tenant1:a");
    assertThat(refB).isEqualTo("desiredstate:tenant1:b");
    assertThat(refA).isNotEqualTo(refB);
}
```

- [ ] **Step 12: Run all work-adapter tests**

Run: `mvn --batch-mode test -pl work-adapter`
Expected: ALL PASS

- [ ] **Step 13: Run full project build**

Run: `mvn --batch-mode test`
Expected: ALL PASS across all modules

- [ ] **Step 14: Commit**

```bash
git add work-adapter/ pom.xml
git commit -m "feat(#43): work-adapter module — WorkItemHumanNodeHandler creates WorkItems for human nodes"
```
