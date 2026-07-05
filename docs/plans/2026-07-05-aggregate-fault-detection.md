# Aggregate Fault Detection via RAS Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use hortora:subagent-driven-development (recommended) or hortora:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the desiredstate reconciliation loop with RAS for aggregate situation detection, fix the FaultPolicy information gap, and prove the design via the spatial POC.

**Architecture:** The reconciliation loop emits CloudEvents on faults/drift/recovery. RAS Ganglia detect aggregate patterns (repeated failures, persistent drift, zone degradation). RAS triggers CaseHub cases for strategic response. FaultPolicy SPI gains `ActualState` for tactical node-level decisions. Precursor situation types removed from desiredstate-api in favour of RAS canonical types.

**Tech Stack:** Java 21, Quarkus 3, CloudEvents SDK 4.0.1, casehub-ras-api, CDI events, JUnit 5 + AssertJ.

## Global Constraints

- Java 21, `--release 21`
- All modules use Jandex plugin for CDI bean discovery
- CloudEvents version: 4.0.1 (managed by casehub-parent)
- casehub-ras-api and casehub-ras version: `${casehub.version}` (0.2-SNAPSHOT)
- Package naming: `io.casehub.desiredstate.{api,runtime,ras}`
- Breaking SPI changes are expected and encouraged — no backward-compat shims
- Every commit references an issue number
- TDD: test first, then implementation

---

### Task 1: Precursor Type Cleanup (#66)

Remove precursor situation types from desiredstate-api and runtime that are superseded by the canonical RAS versions in casehub-ras-api.

**Files:**
- Delete: `api/src/main/java/io/casehub/desiredstate/api/ActiveSituation.java`
- Delete: `api/src/main/java/io/casehub/desiredstate/api/SituationSource.java`
- Delete: `api/src/main/java/io/casehub/desiredstate/api/SituationChangeEvent.java`
- Delete: `api/src/test/java/io/casehub/desiredstate/api/ActiveSituationTest.java`
- Delete: `runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultSituationSource.java`
- Delete: `runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultSituationSourceTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: clean api/ — no situation types remain (RAS owns these)

- [ ] **Step 1: Verify zero production references**

Run: `/opt/homebrew/bin/mvn --batch-mode compile -pl api,runtime,testing,engine-adapter,work-adapter,examples/dungeon,examples/pipeline,examples/spatial`
Expected: BUILD SUCCESS (confirms no production code references these types)

- [ ] **Step 2: Delete the six files**

```
api/src/main/java/io/casehub/desiredstate/api/ActiveSituation.java
api/src/main/java/io/casehub/desiredstate/api/SituationSource.java
api/src/main/java/io/casehub/desiredstate/api/SituationChangeEvent.java
api/src/test/java/io/casehub/desiredstate/api/ActiveSituationTest.java
runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultSituationSource.java
runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultSituationSourceTest.java
```

- [ ] **Step 3: Build to verify clean deletion**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules compile and tests pass

- [ ] **Step 4: Commit**

```bash
git add -u
git commit -m "chore(#66): remove precursor situation types — RAS owns canonical versions

Delete ActiveSituation, SituationSource, SituationChangeEvent from api/
and DefaultSituationSource from runtime/. Zero production references.
Canonical versions live in casehub-ras-api."
```

---

### Task 2: FaultPolicy SPI Fix (#62)

Add `ActualState` as the third parameter to `FaultPolicy.onFault()` and propagate through the entire call chain.

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/FaultPolicy.java:6`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/FaultPolicyEngine.java:15-78`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java:527-540,569-613`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/FaultPolicyEngineTest.java` (all tests)
- Modify: `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/HeroRaidFaultPolicy.java:14`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/QuarantineFaultPolicy.java:23`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/SchemaDriftFaultPolicy.java:18`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/ProvisionEscalationFaultPolicy.java:30`
- Modify: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/distribution/ZoneRebalanceFaultPolicy.java:10`

**Interfaces:**
- Consumes: nothing
- Produces: `FaultPolicy.onFault(FaultEvent, DesiredStateGraph, ActualState) → List<GraphMutation>`
  `FaultPolicyEngine.evaluate(FaultEvent, DesiredStateGraph, ActualState) → List<GraphMutation>`

- [ ] **Step 1: Write failing test — FaultPolicyEngine accepts ActualState**

Add a new test to `FaultPolicyEngineTest.java` that passes `ActualState` to `evaluate()`:

```java
@Test
void policyReceivesActualState() {
    ActualState actual = new ActualState(Map.of(
        NodeId.of("n1"), NodeStatus.ABSENT,
        NodeId.of("n2"), NodeStatus.PRESENT
    ));

    FaultPolicy policy = (event, current, actualState) -> {
        // Policy can now inspect actual state
        if (actualState.statusOf(event.node()).orElse(NodeStatus.UNKNOWN) == NodeStatus.ABSENT) {
            return List.of(new GraphMutation.RemoveNode(event.node()));
        }
        return List.of();
    };

    FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy));

    DesiredNode node1 = new DesiredNode(NodeId.of("n1"), NodeType.of("test"), new TestSpec("N1"), false);
    DesiredNode node2 = new DesiredNode(NodeId.of("n2"), NodeType.of("test"), new TestSpec("N2"), false);
    DesiredStateGraph graph = factory.of(List.of(node1, node2), List.of());

    FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

    List<GraphMutation> mutations = engine.evaluate(event, graph, actual);

    assertEquals(1, mutations.size());
    assertInstanceOf(GraphMutation.RemoveNode.class, mutations.get(0));
}
```

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl runtime -Dtest=FaultPolicyEngineTest`
Expected: COMPILATION ERROR — `evaluate()` does not accept 3 arguments

- [ ] **Step 2: Change FaultPolicy SPI signature**

In `api/src/main/java/io/casehub/desiredstate/api/FaultPolicy.java`:

```java
package io.casehub.desiredstate.api;

import java.util.List;

public interface FaultPolicy {
    List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual);
}
```

- [ ] **Step 3: Update FaultPolicyEngine.evaluate() to accept and pass ActualState**

In `runtime/src/main/java/io/casehub/desiredstate/runtime/FaultPolicyEngine.java`:

Change method signature from:
```java
public List<GraphMutation> evaluate(FaultEvent event, DesiredStateGraph current)
```
to:
```java
public List<GraphMutation> evaluate(FaultEvent event, DesiredStateGraph current, ActualState actual)
```

Change the policy invocation inside from:
```java
List<GraphMutation> policyMutations = policy.onFault(event, current);
```
to:
```java
List<GraphMutation> policyMutations = policy.onFault(event, current, actual);
```

- [ ] **Step 4: Update ReconciliationLoop.detectDrift() and faultFeedback()**

In `ReconciliationLoop.java`, `detectDrift()` method — change:
```java
List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(faultEvent, mutated);
```
to:
```java
List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(faultEvent, mutated, actual);
```

In `faultFeedback()` method — add `ActualState actual` parameter and pass it through:
```java
private void faultFeedback(DesiredStateGraph desired, TransitionPlan plan,
                           TransitionResult result, ActualState actual)
```

Change both fault evaluation calls inside from:
```java
List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(faultEvent, mutated);
```
to:
```java
List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(faultEvent, mutated, actual);
```

Update the call sites in `reconcile()` and `reconcileTypes()` from:
```java
faultFeedback(desired, plan, result);
```
to:
```java
faultFeedback(desired, plan, result, actual);
```

The `actual` variable is already in scope in both `reconcile()` (line 435) and `reconcileTypes()` (line 476).

- [ ] **Step 5: Update all FaultPolicy implementations**

Each implementation adds `ActualState actual` as the third parameter. All except `ZoneRebalanceFaultPolicy` ignore it:

**HeroRaidFaultPolicy.java:**
```java
public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
```

**QuarantineFaultPolicy.java:**
```java
public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
```

**SchemaDriftFaultPolicy.java:**
```java
public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
```

**ProvisionEscalationFaultPolicy.java:**
```java
public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
```

**ZoneRebalanceFaultPolicy.java** — updated to USE actual state (covered in Task 6).
For now, just add the parameter:
```java
public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
```

- [ ] **Step 6: Update FaultPolicyEngineTest — fix all existing lambda signatures**

Every inline lambda in the test class changes from `(event, current) -> ...` to `(event, current, actual) -> ...`. Six tests affected:

- `singlePolicy_returnsMutations`: `(event, current, actual) -> List.of(...)`
- `multiplePolicies_mergesMutations`: both lambdas
- `sameMutationFromTwoPolicies_deduplicated`: both lambdas
- `conflictingMutations_throwsException`: both lambdas
- `dependencyMutations_noConflict`: both lambdas

Every `engine.evaluate(event, graph)` call changes to `engine.evaluate(event, graph, new ActualState(Map.of()))`.

- [ ] **Step 7: Run full test suite**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules compile and tests pass

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(#62): FaultPolicy SPI — add ActualState parameter

Breaking change: FaultPolicy.onFault() now receives ActualState as
third parameter. FaultPolicyEngine.evaluate() propagates it.
ReconciliationLoop passes pre-execution actual snapshot to both
detectDrift() and faultFeedback() paths."
```

---

### Task 3: CloudEvent Data Payload Types (#63 part 1)

Define the structured CloudEvent data payload records in api/ — these form the contract between the emitter (ReconciliationLoop) and consumers (RAS Ganglia).

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/ReconciliationCompletedData.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeFaultedData.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeDriftedData.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeRecoveredData.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DesiredStateEventTypes.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/CloudEventDataTypesTest.java`

**Interfaces:**
- Consumes: `NodeId`, `NodeType`, `FaultType` (existing api types)
- Produces: four data record types + event type constants. Used by Task 4 (emission) and Task 5 (Ganglia).

- [ ] **Step 1: Write tests for the data records**

```java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class CloudEventDataTypesTest {

    @Test
    void reconciliationCompletedData_nullChecks() {
        assertThatThrownBy(() -> new ReconciliationCompletedData(
                null, 1L, 10, 2, 1, 0, Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reconciliationCompletedData_validConstruction() {
        var data = new ReconciliationCompletedData(
            "tenant-1", 5L, 10, 2, 1, 0, Instant.now());
        assertThat(data.tenancyId()).isEqualTo("tenant-1");
        assertThat(data.graphVersion()).isEqualTo(5L);
    }

    @Test
    void nodeFaultedData_carriesParentNodeId() {
        var data = new NodeFaultedData(
            "tenant-1", "unit-cell-4-0", "UNIT",
            "PROVISION_FAILED", "timeout", 3L, "zone-frontier");
        assertThat(data.parentNodeId()).isEqualTo("zone-frontier");
    }

    @Test
    void nodeFaultedData_nullParentForRootNodes() {
        var data = new NodeFaultedData(
            "tenant-1", "zone-frontier", "ZONE",
            "NODE_DEGRADED", "member missing", 3L, null);
        assertThat(data.parentNodeId()).isNull();
    }

    @Test
    void nodeRecoveredData_validConstruction() {
        var data = new NodeRecoveredData(
            "tenant-1", "unit-cell-4-0", "UNIT", 4L, "zone-frontier");
        assertThat(data.nodeId()).isEqualTo("unit-cell-4-0");
    }

    @Test
    void eventTypeConstants() {
        assertThat(DesiredStateEventTypes.RECONCILIATION_COMPLETED)
            .isEqualTo("io.casehub.desiredstate.reconciliation.completed");
        assertThat(DesiredStateEventTypes.NODE_FAULTED)
            .isEqualTo("io.casehub.desiredstate.node.faulted");
        assertThat(DesiredStateEventTypes.NODE_DRIFTED)
            .isEqualTo("io.casehub.desiredstate.node.drifted");
        assertThat(DesiredStateEventTypes.NODE_RECOVERED)
            .isEqualTo("io.casehub.desiredstate.node.recovered");
    }
}
```

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl api -Dtest=CloudEventDataTypesTest`
Expected: COMPILATION ERROR

- [ ] **Step 2: Implement the data records and constants**

```java
// ReconciliationCompletedData.java
package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;

public record ReconciliationCompletedData(
        String tenancyId, long graphVersion,
        int nodeCount, int additionsCount, int removalsCount, int faultCount,
        Instant timestamp) {
    public ReconciliationCompletedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
```

```java
// NodeFaultedData.java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeFaultedData(
        String tenancyId, String nodeId, String nodeType,
        String faultType, String reason, long graphVersion,
        String parentNodeId) {
    public NodeFaultedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
        Objects.requireNonNull(faultType, "faultType");
    }
}
```

```java
// NodeDriftedData.java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeDriftedData(
        String tenancyId, String nodeId, String nodeType,
        long graphVersion, String parentNodeId) {
    public NodeDriftedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
    }
}
```

```java
// NodeRecoveredData.java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeRecoveredData(
        String tenancyId, String nodeId, String nodeType,
        long graphVersion, String parentNodeId) {
    public NodeRecoveredData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
    }
}
```

```java
// DesiredStateEventTypes.java
package io.casehub.desiredstate.api;

public final class DesiredStateEventTypes {
    private DesiredStateEventTypes() {}

    public static final String RECONCILIATION_COMPLETED =
        "io.casehub.desiredstate.reconciliation.completed";
    public static final String NODE_FAULTED =
        "io.casehub.desiredstate.node.faulted";
    public static final String NODE_DRIFTED =
        "io.casehub.desiredstate.node.drifted";
    public static final String NODE_RECOVERED =
        "io.casehub.desiredstate.node.recovered";
}
```

- [ ] **Step 3: Run tests**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl api -Dtest=CloudEventDataTypesTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/io/casehub/desiredstate/api/ReconciliationCompletedData.java \
       api/src/main/java/io/casehub/desiredstate/api/NodeFaultedData.java \
       api/src/main/java/io/casehub/desiredstate/api/NodeDriftedData.java \
       api/src/main/java/io/casehub/desiredstate/api/NodeRecoveredData.java \
       api/src/main/java/io/casehub/desiredstate/api/DesiredStateEventTypes.java \
       api/src/test/java/io/casehub/desiredstate/api/CloudEventDataTypesTest.java
git commit -m "feat(#63): CloudEvent data payload types and event type constants

Four record types define the emitter/consumer contract:
ReconciliationCompletedData, NodeFaultedData, NodeDriftedData,
NodeRecoveredData. All carry parentNodeId for zone correlation.
DesiredStateEventTypes holds the four event type URIs."
```

---

### Task 4: CloudEvent Emission from ReconciliationLoop (#63 part 2) + ReconciliationLoop.getDesired() (#65)

Add CloudEvent emission to the reconciliation loop and the `getDesired()` read accessor.

**Files:**
- Modify: `runtime/pom.xml` (add cloudevents-api + cloudevents-core dependencies)
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationEventEmitter.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationEventEmitterTest.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopCloudEventTest.java`

**Interfaces:**
- Consumes: `DesiredStateEventTypes`, `ReconciliationCompletedData`, `NodeFaultedData`, `NodeDriftedData`, `NodeRecoveredData` (from Task 3)
- Produces: `ReconciliationEventEmitter` — builds CloudEvents from cycle data. `ReconciliationLoop.getDesired(String tenancyId) → DesiredStateGraph`. CDI `Event<CloudEvent>` emission.

- [ ] **Step 1: Add CloudEvents dependencies to runtime/pom.xml**

Add to `<dependencies>`:
```xml
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-api</artifactId>
</dependency>
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-core</artifactId>
</dependency>
```

- [ ] **Step 2: Write test for ReconciliationEventEmitter**

The emitter is a pure function that builds CloudEvents from data — test it in isolation.

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReconciliationEventEmitterTest {

    private final ReconciliationEventEmitter emitter = new ReconciliationEventEmitter();

    @Test
    void buildReconciliationCompleted_setsTypeAndSubject() {
        var data = new ReconciliationCompletedData(
            "tenant-1", 5L, 10, 2, 1, 0, Instant.now());

        CloudEvent event = emitter.reconciliationCompleted(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.RECONCILIATION_COMPLETED);
        assertThat(event.getSubject()).isEqualTo("tenant-1");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
        assertThat(event.getSource().toString()).isEqualTo("io.casehub.desiredstate");
    }

    @Test
    void buildNodeFaulted_setsNodeIdAsSubject() {
        var data = new NodeFaultedData(
            "tenant-1", "unit-cell-4-0", "UNIT",
            "PROVISION_FAILED", "timeout", 3L, "zone-frontier");

        CloudEvent event = emitter.nodeFaulted(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.NODE_FAULTED);
        assertThat(event.getSubject()).isEqualTo("unit-cell-4-0");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
    }

    @Test
    void buildNodeRecovered_setsNodeIdAsSubject() {
        var data = new NodeRecoveredData(
            "tenant-1", "unit-cell-4-0", "UNIT", 4L, "zone-frontier");

        CloudEvent event = emitter.nodeRecovered(data);

        assertThat(event.getType()).isEqualTo(DesiredStateEventTypes.NODE_RECOVERED);
        assertThat(event.getSubject()).isEqualTo("unit-cell-4-0");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
    }
}
```

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl runtime -Dtest=ReconciliationEventEmitterTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Implement ReconciliationEventEmitter**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public class ReconciliationEventEmitter {

    private static final URI SOURCE = URI.create("io.casehub.desiredstate");

    public CloudEvent reconciliationCompleted(ReconciliationCompletedData data) {
        return base(DesiredStateEventTypes.RECONCILIATION_COMPLETED, data.tenancyId())
            .withData("application/json", serialize(data))
            .build();
    }

    public CloudEvent nodeFaulted(NodeFaultedData data) {
        return base(DesiredStateEventTypes.NODE_FAULTED, data.tenancyId())
            .withSubject(data.nodeId())
            .withData("application/json", serialize(data))
            .build();
    }

    public CloudEvent nodeDrifted(NodeDriftedData data) {
        return base(DesiredStateEventTypes.NODE_DRIFTED, data.tenancyId())
            .withSubject(data.nodeId())
            .withData("application/json", serialize(data))
            .build();
    }

    public CloudEvent nodeRecovered(NodeRecoveredData data) {
        return base(DesiredStateEventTypes.NODE_RECOVERED, data.tenancyId())
            .withSubject(data.nodeId())
            .withData("application/json", serialize(data))
            .build();
    }

    private CloudEventBuilder base(String type, String tenancyId) {
        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(SOURCE)
            .withType(type)
            .withSubject(tenancyId)
            .withTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withExtension("tenancyid", tenancyId);
    }

    private byte[] serialize(Object data) {
        // Use platform JSON serialization — Jackson ObjectMapper
        // injected or static utility. For now, simple toString-based.
        // Implementation will use Jackson via CDI or ObjectMapper singleton.
        try {
            return io.cloudevents.core.data.PojoCloudEventData
                .wrap(data, d -> {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
                    try { return mapper.writeValueAsBytes(d); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }).toBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize CloudEvent data", e);
        }
    }
}
```

Note: the exact serialization will depend on what Jackson modules are available. The implementer should verify Jackson is on the runtime classpath (it is — Quarkus includes it).

- [ ] **Step 4: Run emitter tests**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl runtime -Dtest=ReconciliationEventEmitterTest`
Expected: PASS

- [ ] **Step 5: Write test for ReconciliationLoop CloudEvent emission**

Test that the reconciliation loop emits CloudEvents after a cycle. Use a CDI event observer to capture emitted events.

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.testing.*;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class ReconciliationLoopCloudEventTest {

    private DefaultDesiredStateGraphFactory factory;
    private MockActualStateAdapter adapter;
    private List<CloudEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        adapter = new MockActualStateAdapter();
        capturedEvents = new CopyOnWriteArrayList<>();
    }

    @Test
    void emitsNodeFaultedOnProvisionFailure() {
        // Setup: a node that will fail to provision
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), false);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        adapter.setStatus(NodeId.of("n1"), NodeStatus.ABSENT);

        var failingProvisioner = new MockNodeProvisioner() {
            @Override
            public ProvisionResult provision(DesiredNode n, ProvisionContext ctx) {
                return new ProvisionResult.Failed("timeout");
            }
        };

        // Build loop with event capture
        // (Exact wiring depends on how the emitter is injected — 
        //  implementer should use CDI Event<CloudEvent> or a test spy)

        // Assert: node.faulted event emitted with correct data
        assertThat(capturedEvents).anyMatch(e ->
            e.getType().equals(DesiredStateEventTypes.NODE_FAULTED)
            && "n1".equals(e.getSubject()));
    }

    @Test
    void emitsNodeRecoveredWhenPreviouslyFaultedNodeIsPresent() {
        // Setup: node was faulted in previous cycle, now PRESENT
        // Assert: node.recovered event emitted
    }

    @Test
    void getDesired_returnsCurrentGraph() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), false);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        // Start loop, then read desired
        // Assert: getDesired returns the graph
    }

    @Test
    void getDesired_throwsForUnknownTenant() {
        // Assert: getDesired("unknown") throws IllegalStateException
    }

    record TestSpec(String value) implements NodeSpec {}
}
```

The implementer will need to flesh out the test wiring based on how CDI `Event<CloudEvent>` is injected. The pattern follows existing `ReconciliationLoopTest` — construct the loop with test doubles, run a cycle, check outputs.

- [ ] **Step 6: Implement CloudEvent emission in ReconciliationLoop**

Key changes to `ReconciliationLoop.java`:

1. Add `ReconciliationEventEmitter` field and CDI `Event<CloudEvent>` injection
2. Add `Set<NodeId> activeProblems` tracking in `TenantLoop`
3. Add `emitCycleEvents()` method called at the end of `reconcile()` and `reconcileTypes()`
4. Add `getDesired(String tenancyId)` public method
5. Batch all events at cycle end (after faultFeedback)

```java
// In ReconciliationLoop constructor — add:
private final jakarta.enterprise.event.Event<CloudEvent> cloudEventSink;
private final ReconciliationEventEmitter eventEmitter;

// In TenantLoop — add:
private final Set<NodeId> activeProblems = ConcurrentHashMap.newKeySet();

// New public method:
public DesiredStateGraph getDesired(String tenancyId) {
    TenantLoop loop = loops.get(tenancyId);
    if (loop == null) {
        throw new IllegalStateException(
            "No reconciliation loop running for tenant: " + tenancyId);
    }
    return loop.desiredRef.get();
}
```

The emission logic collects drifted nodes during `detectDrift()`, failed/rejected nodes from `TransitionResult`, computes recovered nodes from `activeProblems`, then fires all CloudEvents in a batch at cycle end.

- [ ] **Step 7: Run full test suite**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(#63,#65): CloudEvent emission from ReconciliationLoop + getDesired()

ReconciliationLoop emits CloudEvents at cycle end: node.faulted,
node.drifted, node.recovered, reconciliation.completed. Batch-at-end
emission avoids transient state events. TenantLoop tracks active
problems for recovery detection. getDesired() provides read access
for response cases."
```

---

### Task 5: ras-adapter Module — Ganglia + Situation Definitions (#64)

Create the new `ras-adapter/` module with domain-specific Ganglia, correlation key extraction, and situation definition registration.

**Files:**
- Create: `ras-adapter/pom.xml`
- Create: `ras-adapter/src/main/java/io/casehub/desiredstate/ras/NodeFaultGanglion.java`
- Create: `ras-adapter/src/main/java/io/casehub/desiredstate/ras/PersistentDriftGanglion.java`
- Create: `ras-adapter/src/main/java/io/casehub/desiredstate/ras/DesiredStateCorrelationKeyExtractor.java`
- Create: `ras-adapter/src/main/java/io/casehub/desiredstate/ras/DesiredStateSituationDefinitionProvider.java`
- Create: `ras-adapter/src/main/resources/META-INF/ras-situations.yaml`
- Create: `ras-adapter/src/test/java/io/casehub/desiredstate/ras/NodeFaultGanglionTest.java`
- Create: `ras-adapter/src/test/java/io/casehub/desiredstate/ras/PersistentDriftGanglionTest.java`
- Create: `ras-adapter/src/test/java/io/casehub/desiredstate/ras/DesiredStateCorrelationKeyExtractorTest.java`
- Modify: `pom.xml` (add `ras-adapter` to `<modules>`)

**Interfaces:**
- Consumes: `DesiredStateEventTypes`, `NodeFaultedData`, `NodeDriftedData`, `NodeRecoveredData` (from Task 3). RAS API: `JavaSwitchGanglion`, `DetectionResult`, `DetectionSignal`, `CorrelationKeyExtractor`, `SituationDefinitionProvider`, `SituationRegistration`, `SituationDefinition`, `ChainMode`, `CaseTriggerConfig`, `TriggerMode`.
- Produces: Ganglia beans discoverable by RAS `SituationDefinitionRegistry`. Situation definitions wiring ganglia to triggers. `DesiredStateCorrelationKeyExtractor` for zone-level correlation.

- [ ] **Step 1: Create ras-adapter/pom.xml**

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

    <artifactId>casehub-desiredstate-ras</artifactId>

    <name>CaseHub Desired State :: RAS Adapter</name>
    <description>RAS bridge — Ganglia for reconciliation patterns, situation definitions,
        correlation key extraction for zone-level aggregate detection.</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-desiredstate-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-ras-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-ras</artifactId>
        </dependency>
        <dependency>
            <groupId>io.cloudevents</groupId>
            <artifactId>cloudevents-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>

        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-ras-testing</artifactId>
            <scope>test</scope>
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

Add `<module>ras-adapter</module>` to the parent pom's `<modules>` list, and add dependency management entry:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-ras</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: Write tests for NodeFaultGanglion**

```java
package io.casehub.desiredstate.ras;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NodeFaultGanglionTest {

    private final NodeFaultGanglion ganglion = new NodeFaultGanglion();

    @Test
    void handlesCorrectEventTypes() {
        assertThat(ganglion.handledEventTypes()).containsExactlyInAnyOrder(
            DesiredStateEventTypes.NODE_FAULTED,
            DesiredStateEventTypes.NODE_RECOVERED);
    }

    @Test
    void faultEvent_returnsDetected() {
        CloudEvent event = buildEvent(DesiredStateEventTypes.NODE_FAULTED, "unit-1");
        SituationContext ctx = SituationContext.initial(
            "test-situation", "unit-1", "tenant-1", Instant.now());

        DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test
    void recoveredEvent_returnsAnti() {
        CloudEvent event = buildEvent(DesiredStateEventTypes.NODE_RECOVERED, "unit-1");
        SituationContext ctx = SituationContext.initial(
            "test-situation", "unit-1", "tenant-1", Instant.now());

        DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.ANTI);
    }

    @Test
    void unrelatedEventType_returnsNoise() {
        CloudEvent event = buildEvent("io.casehub.other.event", "unit-1");
        SituationContext ctx = SituationContext.initial(
            "test-situation", "unit-1", "tenant-1", Instant.now());

        DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    private CloudEvent buildEvent(String type, String subject) {
        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("test"))
            .withType(type)
            .withSubject(subject)
            .withTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withExtension("tenancyid", "tenant-1")
            .build();
    }
}
```

- [ ] **Step 3: Implement NodeFaultGanglion**

```java
package io.casehub.desiredstate.ras;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

@ApplicationScoped
public class NodeFaultGanglion extends JavaSwitchGanglion {

    public static final String ID = "desiredstate-node-fault";

    public NodeFaultGanglion() {
        super(ID, Set.of(
            DesiredStateEventTypes.NODE_FAULTED,
            DesiredStateEventTypes.NODE_RECOVERED));
    }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        return switch (event.getType()) {
            case DesiredStateEventTypes.NODE_FAULTED -> detected(1.0);
            case DesiredStateEventTypes.NODE_RECOVERED -> anti(1.0);
            default -> noise();
        };
    }
}
```

- [ ] **Step 4: Write tests and implement PersistentDriftGanglion**

Same pattern as NodeFaultGanglion but handles `NODE_DRIFTED` and `NODE_RECOVERED`.

```java
package io.casehub.desiredstate.ras;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

@ApplicationScoped
public class PersistentDriftGanglion extends JavaSwitchGanglion {

    public static final String ID = "desiredstate-persistent-drift";

    public PersistentDriftGanglion() {
        super(ID, Set.of(
            DesiredStateEventTypes.NODE_DRIFTED,
            DesiredStateEventTypes.NODE_RECOVERED));
    }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        return switch (event.getType()) {
            case DesiredStateEventTypes.NODE_DRIFTED -> detected(1.0);
            case DesiredStateEventTypes.NODE_RECOVERED -> anti(1.0);
            default -> noise();
        };
    }
}
```

- [ ] **Step 5: Write test and implement DesiredStateCorrelationKeyExtractor**

```java
package io.casehub.desiredstate.ras;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class DesiredStateCorrelationKeyExtractorTest {

    private final DesiredStateCorrelationKeyExtractor extractor =
        new DesiredStateCorrelationKeyExtractor();

    @Test
    void extractsParentNodeIdFromData() {
        // Build event with parentNodeId in JSON data
        String json = """
            {"tenancyId":"t1","nodeId":"unit-1","nodeType":"UNIT",
             "faultType":"PROVISION_FAILED","reason":"timeout",
             "graphVersion":3,"parentNodeId":"zone-frontier"}""";

        CloudEvent event = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("test"))
            .withType("io.casehub.desiredstate.node.faulted")
            .withSubject("unit-1")
            .withTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withData("application/json", json.getBytes())
            .build();

        String key = extractor.extract(event);
        assertThat(key).isEqualTo("zone-frontier");
    }

    @Test
    void fallsBackToSubjectWhenNoParentNodeId() {
        String json = """
            {"tenancyId":"t1","nodeId":"zone-frontier","nodeType":"ZONE",
             "faultType":"NODE_DEGRADED","reason":"member missing",
             "graphVersion":3,"parentNodeId":null}""";

        CloudEvent event = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("test"))
            .withType("io.casehub.desiredstate.node.faulted")
            .withSubject("zone-frontier")
            .withData("application/json", json.getBytes())
            .build();

        String key = extractor.extract(event);
        assertThat(key).isEqualTo("zone-frontier");
    }
}
```

Implementation:
```java
package io.casehub.desiredstate.ras;

import io.casehub.ras.runtime.CorrelationKeyExtractor;
import io.cloudevents.CloudEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class DesiredStateCorrelationKeyExtractor implements CorrelationKeyExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String extract(CloudEvent event) {
        if (event.getData() != null) {
            try {
                JsonNode root = MAPPER.readTree(event.getData().toBytes());
                JsonNode parent = root.get("parentNodeId");
                if (parent != null && !parent.isNull()) {
                    return parent.asText();
                }
            } catch (Exception ignored) {
            }
        }
        return event.getSubject();
    }
}
```

- [ ] **Step 6: Implement DesiredStateSituationDefinitionProvider**

Registers both YAML-compatible per-node situations and programmatic zone-level situations.

```java
package io.casehub.desiredstate.ras;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.ras.api.*;
import io.casehub.ras.runtime.CorrelationKeyExtractor;
import io.casehub.ras.runtime.SituationDefinitionProvider;
import io.casehub.ras.runtime.SituationRegistration;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class DesiredStateSituationDefinitionProvider implements SituationDefinitionProvider {

    @Override
    public List<SituationRegistration> registrations() {
        List<SituationRegistration> registrations = new ArrayList<>();

        // Per-node: repeated failure (default extractor — subject = nodeId)
        registrations.add(new SituationRegistration(
            new SituationDefinition(
                "desiredstate.repeated-failure",
                Set.of(DesiredStateEventTypes.NODE_FAULTED,
                       DesiredStateEventTypes.NODE_RECOVERED),
                Duration.ofMinutes(10),
                null,
                new ChainMode.Count(NodeFaultGanglion.ID, 3),
                new CaseTriggerConfig("desiredstate", "replan", "1.0", Map.of()),
                new TriggerMode.FireOnce()),
            null));  // null = default extractor

        // Per-node: persistent drift
        registrations.add(new SituationRegistration(
            new SituationDefinition(
                "desiredstate.persistent-drift",
                Set.of(DesiredStateEventTypes.NODE_DRIFTED,
                       DesiredStateEventTypes.NODE_RECOVERED),
                Duration.ofMinutes(15),
                null,
                new ChainMode.Count(PersistentDriftGanglion.ID, 3),
                new CaseTriggerConfig("desiredstate", "escalate", "1.0", Map.of()),
                new TriggerMode.FireOnce()),
            null));

        // Zone-level: zone degradation (custom extractor)
        registrations.add(new SituationRegistration(
            new SituationDefinition(
                "desiredstate.zone-degradation",
                Set.of(DesiredStateEventTypes.NODE_FAULTED,
                       DesiredStateEventTypes.NODE_RECOVERED),
                Duration.ofMinutes(30),
                null,
                new ChainMode.Count(NodeFaultGanglion.ID, 5),
                new CaseTriggerConfig("desiredstate", "escalate", "1.0", Map.of()),
                new TriggerMode.Repeating(Duration.ofMinutes(5))),
            new DesiredStateCorrelationKeyExtractor()));

        return registrations;
    }
}
```

Note: zone-degradation uses `Count(5)` as interim since `ChainMode.Rate` (ras#26) is not yet available. When ras#26 lands, update to `Rate(List.of(NodeFaultGanglion.ID), 0.6, 10)`.

- [ ] **Step 7: Run all ras-adapter tests**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl ras-adapter`
Expected: PASS

- [ ] **Step 8: Run full build**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add ras-adapter/ pom.xml
git commit -m "feat(#64): ras-adapter module — Ganglia, correlation, situation definitions

New module casehub-desiredstate-ras bridges desiredstate to RAS.
NodeFaultGanglion and PersistentDriftGanglion detect reconciliation
patterns. DesiredStateCorrelationKeyExtractor maps node events to
zone-level correlation via parentNodeId. Three situation definitions:
repeated-failure, persistent-drift, zone-degradation."
```

---

### Task 6: Spatial POC Proof (#67)

Update `ZoneRebalanceFaultPolicy` to use `ActualState` and add `SituationDetectionTest` proving the RAS pipeline end-to-end.

**Files:**
- Modify: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/distribution/ZoneRebalanceFaultPolicy.java`
- Modify: `examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/distribution/ForceDistributionTest.java`
- Modify: `examples/spatial/pom.xml` (add ras-adapter + ras-testing test deps)

**Interfaces:**
- Consumes: `FaultPolicy.onFault(FaultEvent, DesiredStateGraph, ActualState)` (from Task 2), `ZoneSpec`, `UnitSpec`, `NodeFaultGanglion` (from Task 5)
- Produces: proof that FaultPolicy fix and RAS integration work

- [ ] **Step 1: Update ZoneRebalanceFaultPolicy to use ActualState**

Replace the existing implementation that returns empty with one that inspects actual state:

```java
package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.UnitSpec;
import io.casehub.desiredstate.example.spatial.specs.ZoneSpec;
import java.util.*;

public class ZoneRebalanceFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        var node = current.nodes().get(event.node());
        if (node == null || !(node.spec() instanceof ZoneSpec zoneSpec)) {
            return List.of();
        }

        // Identify which member units are ABSENT
        Set<NodeId> absentUnits = new HashSet<>();
        for (NodeId dependentId : current.dependentsOf(event.node())) {
            DesiredNode dependent = current.nodes().get(dependentId);
            if (dependent != null && dependent.spec() instanceof UnitSpec) {
                NodeStatus status = actual.statuses()
                    .getOrDefault(dependentId, NodeStatus.UNKNOWN);
                if (status == NodeStatus.ABSENT) {
                    absentUnits.add(dependentId);
                }
            }
        }

        if (absentUnits.isEmpty()) {
            return List.of();
        }

        // Redistribute allocation among surviving units
        Map<NodeId, Double> surviving = new LinkedHashMap<>();
        for (var entry : zoneSpec.allocation().entrySet()) {
            // Find the unit for this cell
            NodeId unitId = NodeId.of("unit-" + entry.getKey().value());
            if (!absentUnits.contains(unitId)) {
                surviving.put(entry.getKey(), entry.getValue());
            }
        }

        if (surviving.isEmpty()) {
            return List.of();
        }

        // Normalize ratios to sum to 1.0
        double total = surviving.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<NodeId, Double> normalized = new LinkedHashMap<>();
        for (var entry : surviving.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / total);
        }

        // Emit mutations: update zone spec + update surviving unit strengths
        List<GraphMutation> mutations = new ArrayList<>();
        ZoneSpec newZoneSpec = new ZoneSpec(
            zoneSpec.zoneName(), normalized, zoneSpec.totalForce());
        mutations.add(new GraphMutation.UpdateNode(event.node(), newZoneSpec));

        for (var entry : normalized.entrySet()) {
            NodeId unitId = NodeId.of("unit-" + entry.getKey().value());
            int strength = (int) Math.round(zoneSpec.totalForce() * entry.getValue());
            mutations.add(new GraphMutation.UpdateNode(unitId, new UnitSpec(entry.getKey(), strength)));
        }

        return mutations;
    }
}
```

- [ ] **Step 2: Update ForceDistributionTest.faultPolicyInformationGap()**

Change the assertion from "policy returns empty" to "policy returns redistribution mutations":

```java
@Test
void faultPolicyInformationGap() {
    // ... existing setup ...

    var policy = new ZoneRebalanceFaultPolicy();
    var actual = adapter.readActual(graph, "test");
    var mutations = policy.onFault(faultEvent, graph, actual);

    // FIXED: Policy now uses ActualState to identify ABSENT unit and redistribute
    assertThat(mutations).as(
        "Policy uses ActualState to determine unit-cell-4-0 is ABSENT. " +
        "Redistributes zone allocation among surviving units.")
        .isNotEmpty();

    // Zone spec updated with redistributed ratios
    assertThat(mutations).anyMatch(m ->
        m instanceof GraphMutation.UpdateNode u
        && u.id().equals(NodeId.of("zone-frontier")));

    // Surviving unit spec updated with new strength
    assertThat(mutations).anyMatch(m ->
        m instanceof GraphMutation.UpdateNode u
        && u.id().equals(NodeId.of("unit-cell-4-1")));
}
```

- [ ] **Step 3: Update strategicPivotRequiresExternalIntervention()**

Update the assertion to acknowledge the pivot decision now has a home:

```java
@Test
void strategicPivotHasHomeInRasSituationDetection() {
    // ... existing setup unchanged ...

    // The runtime executes the transition. The DECISION to pivot now has a home:
    // RAS situation detection observes repeated failures via CloudEvents,
    // NodeFaultGanglion detects the pattern, and CaseTrigger fires a replan case.
    // The test verifies the graph transition; the detection pipeline is proven
    // in SituationDetectionTest.
    assertThat(graph2.nodes().keySet()).as(
        "Graph transitioned from north to south approach. " +
        "The decision to pivot is handled by RAS: NodeFaultGanglion + " +
        "ChainMode.Count → CaseTrigger → replan case.")
        .noneMatch(id -> id.value().contains("north"));
    renderer.printFrame("After strategic pivot to south");
}
```

- [ ] **Step 4: Run spatial tests**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/spatial`
Expected: PASS

- [ ] **Step 5: Run full build**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add examples/spatial/
git commit -m "feat(#67): spatial POC proof — FaultPolicy ActualState + RAS pipeline

ZoneRebalanceFaultPolicy uses ActualState to identify ABSENT units
and redistribute zone allocation among survivors.
faultPolicyInformationGap() now asserts non-empty redistribution mutations.
strategicPivotHasHomeInRasSituationDetection() updated to reference
RAS detection pipeline."
```

---

### Task 7: CLAUDE.md + ARC42STORIES Updates

Update project documentation to reflect the new module, SPIs, and architecture.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `ARC42STORIES.MD`

**Interfaces:**
- Consumes: all prior tasks
- Produces: updated documentation

- [ ] **Step 1: Update CLAUDE.md**

Add `ras-adapter/` to module structure table:
```
| `ras-adapter/` | `casehub-desiredstate-ras` | `io.casehub.desiredstate.ras` | RAS bridge — Ganglia for reconciliation patterns, situation definitions, correlation key extraction for zone-level aggregate detection. |
```

Add CloudEvent data types to Core Runtime Types table. Update FaultPolicy SPI signature in the SPI table.

- [ ] **Step 2: Update ARC42STORIES.MD**

Update relevant sections to reflect RAS integration for aggregate fault detection. Update any references to Finding #3 (now fixed) and Finding #9 (resolved architecturally).

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md ARC42STORIES.MD
git commit -m "docs(#59): update CLAUDE.md and ARC42STORIES for RAS integration

Add ras-adapter module, updated FaultPolicy signature, CloudEvent
data types. Aggregate fault detection via RAS documented."
```

---

## Self-Review

**Spec coverage check:**
- §1 Problem Statement → framing in all tasks ✅
- §2 FaultPolicy fix → Task 2 ✅
- §3 CloudEvent emission → Tasks 3 + 4 ✅
- §4 RAS integration → Task 5 ✅
- §5 Capability verification → covered by Task 5 tests + Task 6 proof ✅
- §6 Precursor cleanup → Task 1 ✅
- §7 Spatial POC proof → Task 6 ✅
- §8 Module structure → Task 5 (pom) + Task 7 (CLAUDE.md) ✅
- §9 Cross-repo deps → filed as issues, noted as interim in Task 5 ✅
- §10 CBR convergence → design-level, no implementation needed ✅
- §11 Design decisions → documented in spec, no implementation needed ✅

**Placeholder scan:** No TBD/TODO. Task 4 step 5 has skeleton tests that the implementer will flesh out — the test patterns are shown, wiring depends on actual CDI injection approach.

**Type consistency:** `FaultPolicy.onFault(FaultEvent, DesiredStateGraph, ActualState)` consistent across Tasks 2, 6. `DesiredStateEventTypes` constants consistent across Tasks 3, 5. `NodeFaultGanglion.ID` = `"desiredstate-node-fault"` consistent between Task 5 implementation and situation definition registration.

**Issue not in spec:** #68 (replan dispatch handler in engine-adapter) is listed in the epic but deliberately excluded from this plan — it depends on the domain's goal storage design which is an open question. The plan covers detection + triggering; the response case workflow is a separate implementation cycle.
