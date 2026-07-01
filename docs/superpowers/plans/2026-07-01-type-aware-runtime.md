# Type-Aware Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the desired-state runtime type-aware — route provisioner calls by NodeType and schedule reconciliation per provisioner-declared intervals.

**Architecture:** `NodeProvisioner` gains `handledTypes()` and `resyncInterval()`. A new `NodeProvisionerRouter` interface (api) + `DefaultNodeProvisionerRouter` (runtime) builds a routing table from all provisioner beans, validates conflicts at startup, and dispatches provision/deprovision calls by NodeType. `ReconciliationLoop` replaces its single resync timer with interval-grouped timers. The CAS race in fault mutation commits is fixed with a retry loop.

**Tech Stack:** Java 21+, Quarkus Arc CDI, Smallrye Mutiny, JUnit 5, AssertJ

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-01-type-aware-runtime-design.md`
- `api/` module is pure Java — no CDI, no Quarkus, no Mutiny dependencies in production code
- `runtime/` is a Quarkus library — CDI annotations allowed
- `engine-adapter/` depends on `api` at compile scope, `runtime` at test scope only
- `testing/` provides mock SPIs — no CDI required
- Breaking changes are fine — this platform has no external users
- `SettingsScope.root()` (not `.global()` as spec says — spec typo)
- Protocol: `module-tier-structure.md` — interface in api, implementation in runtime
- Protocol: `spi-default-method-contract-test.md` — contract test for `resyncInterval()` default
- Protocol: `no-workarounds-fix-the-design.md` — no backward-compat shims
- Protocol: `typed-preference-keys.md` — PreferenceKey<DurationPreference> for config
- Protocol: `maven-module-scoping.md` — always `mvn -pl <module>`
- All tests run with: `mvn --batch-mode -pl <module> test`

---

### Task 1: NodeProvisioner SPI additions + ReactiveNodeProvisioner deletion

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/NodeProvisioner.java`
- Delete: `api/src/main/java/io/casehub/desiredstate/api/ReactiveNodeProvisioner.java`
- Modify: `api/src/test/java/io/casehub/desiredstate/api/SpiContractTest.java`

**Interfaces:**
- Produces: `NodeProvisioner.handledTypes()` — `Set<NodeType>` (abstract, no default)
- Produces: `NodeProvisioner.resyncInterval()` — `default Duration.ofMinutes(5)`

- [ ] **Step 1: Write the failing contract test for resyncInterval() default**

Per protocol `spi-default-method-contract-test.md`, verify the default method returns 5 minutes for an anonymous implementation that does NOT override it.

```java
// In SpiContractTest.java — add this test
@Test void nodeProvisioner_resyncInterval_defaultIsFiveMinutes() {
    NodeProvisioner provisioner = new NodeProvisioner() {
        @Override public Set<NodeType> handledTypes() { return Set.of(NodeType.of("test")); }
        @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) { return new ProvisionResult.Success(); }
        @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) { return new DeprovisionResult.Success(); }
    };
    assertThat(provisioner.resyncInterval()).isEqualTo(Duration.ofMinutes(5));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -pl api test -Dtest=SpiContractTest#nodeProvisioner_resyncInterval_defaultIsFiveMinutes`
Expected: FAIL — `resyncInterval()` does not exist on `NodeProvisioner`

- [ ] **Step 3: Add `handledTypes()` and `resyncInterval()` to NodeProvisioner**

```java
package io.casehub.desiredstate.api;

import java.time.Duration;
import java.util.Set;

public interface NodeProvisioner {
    Set<NodeType> handledTypes();
    default Duration resyncInterval() { return Duration.ofMinutes(5); }
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}
```

- [ ] **Step 4: Update existing nodeProvisioner_canBeImplemented test**

The existing test creates an anonymous NodeProvisioner without `handledTypes()` — it will now fail to compile. Update it:

```java
@Test void nodeProvisioner_canBeImplemented() {
    NodeProvisioner provisioner = new NodeProvisioner() {
        @Override public Set<NodeType> handledTypes() { return Set.of(NodeType.of("test")); }
        @Override public ProvisionResult provision(DesiredNode node, ProvisionContext ctx) { return new ProvisionResult.Success(); }
        @Override public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext ctx) { return new DeprovisionResult.Success(); }
    };
    var node = new DesiredNode(new NodeId("a"), new NodeType("t"), new TestSpec("x"), false);
    assertThat(provisioner.provision(node, null)).isInstanceOf(ProvisionResult.Success.class);
}
```

- [ ] **Step 5: Delete ReactiveNodeProvisioner and its test**

Delete `api/src/main/java/io/casehub/desiredstate/api/ReactiveNodeProvisioner.java`.

Remove the `reactiveNodeProvisioner_canBeImplemented` test from `SpiContractTest.java`. Remove the `Uni` import if no longer used.

- [ ] **Step 6: Run api tests**

Run: `mvn --batch-mode -pl api test`
Expected: PASS — contract tests pass, ReactiveNodeProvisioner gone

- [ ] **Step 7: Commit**

```
feat(#18): NodeProvisioner SPI — add handledTypes() + resyncInterval(), delete ReactiveNodeProvisioner
```

---

### Task 2: NodeProvisionerRouter interface

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeProvisionerRouter.java`

**Interfaces:**
- Produces: `NodeProvisionerRouter` — interface consumed by SimpleTransitionExecutor and DesiredStateDispatch
- Methods: `provision`, `deprovision`, `resyncIntervalFor`, `allHandledTypes`

- [ ] **Step 1: Create the interface**

```java
package io.casehub.desiredstate.api;

import java.time.Duration;
import java.util.Set;

public interface NodeProvisionerRouter {
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
    Duration resyncIntervalFor(NodeType type);
    Set<NodeType> allHandledTypes();
}
```

- [ ] **Step 2: Run api tests to verify no regressions**

Run: `mvn --batch-mode -pl api test`
Expected: PASS — interface adds no breaking changes

- [ ] **Step 3: Commit**

```
feat(#18): NodeProvisionerRouter interface in api
```

---

### Task 3: MockNodeProvisioner update

**Files:**
- Modify: `testing/src/main/java/io/casehub/desiredstate/testing/MockNodeProvisioner.java`

**Interfaces:**
- Consumes: `NodeProvisioner.handledTypes()`, `NodeProvisioner.resyncInterval()`
- Produces: `MockNodeProvisioner` with configurable `handledTypes` and `resyncInterval`

- [ ] **Step 1: Update MockNodeProvisioner**

Add `handledTypes` and `resyncInterval` fields with setters. Include them in `clear()`.

```java
package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class MockNodeProvisioner implements NodeProvisioner {

    public final CopyOnWriteArrayList<DesiredNode> provisioned = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<DesiredNode> deprovisioned = new CopyOnWriteArrayList<>();

    private Function<DesiredNode, ProvisionResult> provisionBehavior = node -> new ProvisionResult.Success();
    private Function<DesiredNode, DeprovisionResult> deprovisionBehavior = node -> new DeprovisionResult.Success();
    private Set<NodeType> handledTypes = Set.of();
    private Duration resyncInterval = Duration.ofMinutes(5);

    @Override
    public Set<NodeType> handledTypes() { return handledTypes; }

    @Override
    public Duration resyncInterval() { return resyncInterval; }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        provisioned.add(node);
        return provisionBehavior.apply(node);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        deprovisioned.add(node);
        return deprovisionBehavior.apply(node);
    }

    public void setProvisionBehavior(Function<DesiredNode, ProvisionResult> behavior) {
        this.provisionBehavior = behavior;
    }

    public void setDeprovisionBehavior(Function<DesiredNode, DeprovisionResult> behavior) {
        this.deprovisionBehavior = behavior;
    }

    public void setHandledTypes(Set<NodeType> types) { this.handledTypes = Set.copyOf(types); }

    public void setResyncInterval(Duration interval) { this.resyncInterval = interval; }

    public void clear() {
        provisioned.clear();
        deprovisioned.clear();
        provisionBehavior = node -> new ProvisionResult.Success();
        deprovisionBehavior = node -> new DeprovisionResult.Success();
        handledTypes = Set.of();
        resyncInterval = Duration.ofMinutes(5);
    }
}
```

- [ ] **Step 2: Run testing module tests**

Run: `mvn --batch-mode -pl testing test`
Expected: PASS

- [ ] **Step 3: Commit**

```
feat(#18): MockNodeProvisioner — add handledTypes + resyncInterval support
```

---

### Task 4: DefaultNodeProvisionerRouter

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultNodeProvisionerRouter.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultNodeProvisionerRouterTest.java`

**Interfaces:**
- Consumes: `NodeProvisionerRouter` (api), `NodeProvisioner.handledTypes()`, `NodeProvisioner.resyncInterval()`
- Produces: `DefaultNodeProvisionerRouter` — routing table, conflict detection, resync interval resolution

- [ ] **Step 1: Write failing tests for routing table construction**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class DefaultNodeProvisionerRouterTest {

    static final NodeType TYPE_A = NodeType.of("type-a");
    static final NodeType TYPE_B = NodeType.of("type-b");

    @Test
    void routesToCorrectProvisioner() {
        var provA = mockProvisioner(Set.of(TYPE_A));
        var provB = mockProvisioner(Set.of(TYPE_B));
        var router = new DefaultNodeProvisionerRouter(List.of(provA, provB));

        var nodeA = new DesiredNode(NodeId.of("a"), TYPE_A, new TestSpec("a"), false);
        var result = router.provision(nodeA, new ProvisionContext("t1", null));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(provA.provisioned).hasSize(1);
        assertThat(provB.provisioned).isEmpty();
    }

    @Test
    void failsForUnknownNodeType() {
        var prov = mockProvisioner(Set.of(TYPE_A));
        var router = new DefaultNodeProvisionerRouter(List.of(prov));

        var unknown = new DesiredNode(NodeId.of("x"), NodeType.of("unknown"), new TestSpec("x"), false);
        var result = router.provision(unknown, new ProvisionContext("t1", null));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("No provisioner for node type");
    }

    @Test
    void detectsConflictingNodeTypes() {
        var prov1 = mockProvisioner(Set.of(TYPE_A));
        var prov2 = mockProvisioner(Set.of(TYPE_A));

        assertThatThrownBy(() -> new DefaultNodeProvisionerRouter(List.of(prov1, prov2)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type-a")
            .hasMessageContaining("claimed by both");
    }

    @Test
    void validatesResyncIntervalFloor() {
        var prov = mockProvisioner(Set.of(TYPE_A));
        prov.setResyncInterval(Duration.ZERO);

        assertThatThrownBy(() -> new DefaultNodeProvisionerRouter(List.of(prov)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be");
    }

    @Test
    void resyncIntervalForReturnProvisionerDefault() {
        var prov = mockProvisioner(Set.of(TYPE_A));
        prov.setResyncInterval(Duration.ofSeconds(30));
        var router = new DefaultNodeProvisionerRouter(List.of(prov));

        assertThat(router.resyncIntervalFor(TYPE_A)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void resyncIntervalForUnknownTypeReturnsFiveMinutes() {
        var prov = mockProvisioner(Set.of(TYPE_A));
        var router = new DefaultNodeProvisionerRouter(List.of(prov));

        assertThat(router.resyncIntervalFor(NodeType.of("unknown")))
            .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void allHandledTypesReturnsUnionOfProvisioners() {
        var prov1 = mockProvisioner(Set.of(TYPE_A));
        var prov2 = mockProvisioner(Set.of(TYPE_B));
        var router = new DefaultNodeProvisionerRouter(List.of(prov1, prov2));

        assertThat(router.allHandledTypes()).containsExactlyInAnyOrder(TYPE_A, TYPE_B);
    }

    @Test
    void deprovisionRoutesToCorrectProvisioner() {
        var provA = mockProvisioner(Set.of(TYPE_A));
        var provB = mockProvisioner(Set.of(TYPE_B));
        var router = new DefaultNodeProvisionerRouter(List.of(provA, provB));

        var nodeB = new DesiredNode(NodeId.of("b"), TYPE_B, new TestSpec("b"), false);
        var result = router.deprovision(nodeB, new DeprovisionContext("t1", null));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(provB.deprovisioned).hasSize(1);
        assertThat(provA.deprovisioned).isEmpty();
    }

    private MockNodeProvisioner mockProvisioner(Set<NodeType> types) {
        var mock = new MockNodeProvisioner();
        mock.setHandledTypes(types);
        return mock;
    }

    record TestSpec(String value) implements NodeSpec {}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode -pl runtime test -Dtest=DefaultNodeProvisionerRouterTest`
Expected: FAIL — `DefaultNodeProvisionerRouter` does not exist

- [ ] **Step 3: Implement DefaultNodeProvisionerRouter**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;

import java.time.Duration;
import java.util.*;

public class DefaultNodeProvisionerRouter implements NodeProvisionerRouter {

    static final Duration MIN_RESYNC = Duration.ofSeconds(1);
    static final Duration DEFAULT_RESYNC = Duration.ofMinutes(5);

    private final Map<NodeType, NodeProvisioner> routing;

    public DefaultNodeProvisionerRouter(Collection<NodeProvisioner> provisioners) {
        Map<NodeType, NodeProvisioner> table = new LinkedHashMap<>();
        for (NodeProvisioner p : provisioners) {
            Duration interval = p.resyncInterval();
            if (interval == null || interval.compareTo(MIN_RESYNC) < 0) {
                throw new IllegalArgumentException(
                    p.getClass().getName() + ".resyncInterval() returned " + interval
                    + "; must be ≥ " + MIN_RESYNC);
            }
            for (NodeType type : p.handledTypes()) {
                NodeProvisioner existing = table.put(type, p);
                if (existing != null) {
                    throw new IllegalArgumentException(
                        "NodeType " + type.value() + " claimed by both "
                        + existing.getClass().getName() + " and "
                        + p.getClass().getName());
                }
            }
        }
        this.routing = Map.copyOf(table);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new ProvisionResult.Failed(
                "No provisioner for node type: " + node.type().value());
        }
        return p.provision(node, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new DeprovisionResult.Failed(
                "No provisioner for node type: " + node.type().value());
        }
        return p.deprovision(node, context);
    }

    @Override
    public Duration resyncIntervalFor(NodeType type) {
        NodeProvisioner p = routing.get(type);
        return p != null ? p.resyncInterval() : DEFAULT_RESYNC;
    }

    @Override
    public Set<NodeType> allHandledTypes() {
        return routing.keySet();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode -pl runtime test -Dtest=DefaultNodeProvisionerRouterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```
feat(#18): DefaultNodeProvisionerRouter — type-based provisioner routing with conflict detection
```

---

### Task 5: SimpleTransitionExecutor migration

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutorTest.java`

**Interfaces:**
- Consumes: `NodeProvisionerRouter.provision()`, `NodeProvisionerRouter.deprovision()`

- [ ] **Step 1: Update SimpleTransitionExecutor — replace NodeProvisioner with NodeProvisionerRouter**

Change the constructor and field:

```java
// Field change
private final NodeProvisionerRouter router;

// Constructor change
public SimpleTransitionExecutor(NodeProvisionerRouter router,
                                 HumanNodeHandler humanNodeHandler,
                                 PendingApprovalHandler pendingApprovalHandler) {
    this.router = router;
    this.humanNodeHandler = humanNodeHandler;
    this.pendingApprovalHandler = pendingApprovalHandler;
}
```

Replace all `provisioner.provision(node, context)` with `router.provision(node, context)`.
Replace all `provisioner.deprovision(node, context)` with `router.deprovision(node, context)`.

Two call sites:
- `executeProvision` line ~90: `provisioner.provision(node, context)` → `router.provision(node, context)`
- `executeDeprovision` line ~129: `provisioner.deprovision(node, context)` → `router.deprovision(node, context)`

- [ ] **Step 2: Update SimpleTransitionExecutorTest**

The test creates `SimpleTransitionExecutor` with a `MockNodeProvisioner`. It now needs a `NodeProvisionerRouter`. Use `DefaultNodeProvisionerRouter` wrapping the mock.

Update `setUp()`:

```java
@BeforeEach
void setUp() {
    factory = new DefaultDesiredStateGraphFactory();
    mockProvisioner = new MockNodeProvisioner();
    mockProvisioner.setHandledTypes(Set.of(NodeType.of("test")));
    var router = new DefaultNodeProvisionerRouter(List.of(mockProvisioner));
    executor = new SimpleTransitionExecutor(router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());
}
```

Also update the inner `MockNodeProvisioner` class in the test — add `handledTypes()`:

```java
static class MockNodeProvisioner implements NodeProvisioner {
    List<String> callOrder = new java.util.ArrayList<>();
    boolean shouldFail = false;
    boolean shouldReturnPendingApproval = false;

    @Override
    public Set<NodeType> handledTypes() { return Set.of(NodeType.of("test")); }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        callOrder.add("provision:" + node.id().value());
        if (shouldFail) return new ProvisionResult.Failed("mock failure");
        if (shouldReturnPendingApproval) return new ProvisionResult.PendingApproval(node.id(), "mock-plan");
        return new ProvisionResult.Success();
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        callOrder.add("deprovision:" + node.id().value());
        if (shouldFail) return new DeprovisionResult.Failed("mock failure");
        return new DeprovisionResult.Success();
    }
}
```

Update any test methods that create a `SimpleTransitionExecutor` directly — they must wrap their provisioner in a `DefaultNodeProvisionerRouter`. Search for `new SimpleTransitionExecutor(` in the test file and update each occurrence.

- [ ] **Step 3: Run all executor tests**

Run: `mvn --batch-mode -pl runtime test -Dtest=SimpleTransitionExecutorTest`
Expected: PASS — all existing tests pass with router injection

- [ ] **Step 4: Commit**

```
feat(#18): SimpleTransitionExecutor — inject NodeProvisionerRouter instead of NodeProvisioner
```

---

### Task 6: DesiredStateDispatch migration

**Files:**
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateDispatch.java`
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateDispatchTest.java`

**Interfaces:**
- Consumes: `NodeProvisionerRouter.provision()`, `NodeProvisionerRouter.deprovision()`

- [ ] **Step 1: Update DesiredStateDispatch — replace NodeProvisioner with NodeProvisionerRouter**

Change field and constructor:

```java
private final NodeProvisionerRouter router;

@Inject
public DesiredStateDispatch(NodeProvisionerRouter router,
                             PendingApprovalHandler pendingApprovalHandler,
                             DesiredStateExecutionRegistry executionRegistry,
                             CallableDispatchRegistry callRegistry) {
    this.router = router;
    // rest unchanged
}
```

Replace `provisioner.provision(node, context)` → `router.provision(node, context)` (two call sites: `executeProvision` and `executeDeprovision`).

- [ ] **Step 2: Update DesiredStateDispatchTest**

The test creates inline anonymous `NodeProvisioner` instances. Each needs `handledTypes()` added. Then wrap in `DefaultNodeProvisionerRouter`.

For each test's provisioner lambda/anonymous class, add:
```java
@Override public Set<NodeType> handledTypes() { return Set.of(NodeType.of("test")); }
```

Update the constructor calls: wherever the test creates `new DesiredStateDispatch(provisioner, ...)`, change to pass a `DefaultNodeProvisionerRouter(List.of(provisioner))`.

**Note:** engine-adapter has `casehub-desiredstate` (runtime) at test scope, so `DefaultNodeProvisionerRouter` is available in tests.

- [ ] **Step 3: Run engine-adapter tests**

Run: `mvn --batch-mode -pl engine-adapter test`
Expected: PASS

- [ ] **Step 4: Commit**

```
feat(#18): DesiredStateDispatch — inject NodeProvisionerRouter instead of NodeProvisioner
```

---

### Task 7: ReconciliationLoop scheduling + CAS fix

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopSchedulingTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopRequestReconciliationTest.java`

**Interfaces:**
- Consumes: `NodeProvisionerRouter.resyncIntervalFor()`, `NodeProvisionerRouter.allHandledTypes()`

This is the largest task. It has three sub-deliverables:
1. Interval-grouped timers (replacing single resync timer)
2. CAS merge-and-retry loop (fixing GE-20260616-3d2605)
3. Scheduler pool sizing

- [ ] **Step 1: Write failing test — different types get different reconciliation frequencies**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ReconciliationLoopSchedulingTest {

    static final NodeType FAST_TYPE = NodeType.of("fast");
    static final NodeType SLOW_TYPE = NodeType.of("slow");

    private ReconciliationLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.shutdown();
    }

    @Test
    void fastTypeReconciliesMoreOftenThanSlowType() throws Exception {
        var fastCount = new AtomicInteger(0);
        var slowCount = new AtomicInteger(0);
        var fastLatch = new CountDownLatch(3); // wait for 3 fast cycles

        var fastProv = new MockNodeProvisioner();
        fastProv.setHandledTypes(Set.of(FAST_TYPE));
        fastProv.setResyncInterval(Duration.ofMillis(100));

        var slowProv = new MockNodeProvisioner();
        slowProv.setHandledTypes(Set.of(SLOW_TYPE));
        slowProv.setResyncInterval(Duration.ofMillis(500));

        var router = new DefaultNodeProvisionerRouter(List.of(fastProv, slowProv));

        ActualStateAdapter adapter = (desired, tenancyId) -> {
            for (DesiredNode node : desired.nodes().values()) {
                if (node.type().equals(FAST_TYPE)) fastCount.incrementAndGet();
                if (node.type().equals(SLOW_TYPE)) slowCount.incrementAndGet();
            }
            fastLatch.countDown();
            return new ActualState(Map.of());
        };

        var factory = new DefaultDesiredStateGraphFactory();
        var graph = factory.of(
            List.of(
                new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), false),
                new DesiredNode(NodeId.of("s1"), SLOW_TYPE, new TestSpec("s"), false)
            ),
            List.of()
        );

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource(),
            router,
            Duration.ofMillis(50)
        );

        loop.start("tenant-1", graph);

        assertTrue(fastLatch.await(3, TimeUnit.SECONDS),
            "Fast type should reconcile at least 3 times");
        assertThat(fastCount.get()).isGreaterThan(slowCount.get());
    }

    record TestSpec(String value) implements NodeSpec {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -pl runtime test -Dtest=ReconciliationLoopSchedulingTest`
Expected: FAIL — ReconciliationLoop constructor doesn't accept NodeProvisionerRouter

- [ ] **Step 3: Implement ReconciliationLoop changes**

Major changes to `ReconciliationLoop`:

1. **Add `NodeProvisionerRouter` field** and new constructor parameter
2. **Replace single `resyncFuture`** with `Map<Duration, ScheduledFuture<?>> resyncFutures`
3. **Increase scheduler pool size** to handle concurrent type-group timers
4. **Add `reconcileTypes(Set<NodeType>)` method** — filtered reconciliation
5. **CAS merge-and-retry** in `detectDrift()` and `faultFeedback()`
6. **Recompute timers** in `updateDesired()`

Key implementation points:

**Constructor:** Accept `NodeProvisionerRouter`. Remove `Duration resyncInterval` from the CDI constructor. Add a test-friendly constructor with `Duration resyncOverride` that bypasses interval-grouped scheduling.

**TenantLoop.start():** Compute interval groups from the desired graph's node types + router's `resyncIntervalFor()`. Create one `ScheduledFuture` per interval group. Each fires `reconcileTypes(types)`.

**reconcileTypes(Set<NodeType>):** Filters `desiredRef.get()` to nodes whose type is in the set. Runs the reconcile pipeline (readActual → detectDrift → plan → execute → faultFeedback) on the filtered graph.

**CAS retry (detectDrift + faultFeedback):** Replace single-shot `desiredRef.compareAndSet(desired, mutated)` with:
```java
List<GraphMutation> mutations = /* accumulated */;
if (!mutations.isEmpty()) {
    DesiredStateGraph current;
    DesiredStateGraph updated;
    do {
        current = desiredRef.get();
        updated = current;
        for (GraphMutation mutation : mutations) {
            updated = updated.withMutation(mutation);
        }
    } while (updated != current && !desiredRef.compareAndSet(current, updated));
}
```

**updateDesired():** After swapping the desired graph, check if node types changed. If so, cancel old timers and create new interval groups.

- [ ] **Step 4: Update existing ReconciliationLoop tests**

All existing tests create `ReconciliationLoop` without a router. Add a test-friendly constructor that accepts `Duration resyncOverride` for deterministic timing:

```java
// Test-friendly constructor with override interval
public ReconciliationLoop(
        TransitionPlanner planner,
        TransitionExecutor executor,
        ActualStateAdapter actualStateAdapter,
        FaultPolicyEngine faultPolicyEngine,
        EventSource eventSource,
        Duration debounceWindow,
        Duration resyncOverride) {
    this(planner, executor, actualStateAdapter, faultPolicyEngine, eventSource,
         null, debounceWindow, resyncOverride);
}
```

Update `ReconciliationLoopTest` and `ReconciliationLoopRequestReconciliationTest` to use this constructor. The existing tests don't need multi-provisioner behavior — they pass `null` for the router and use `resyncOverride`.

- [ ] **Step 5: Run all reconciliation loop tests**

Run: `mvn --batch-mode -pl runtime test -Dtest="ReconciliationLoop*"`
Expected: PASS — scheduling test passes, existing tests pass with override constructor

- [ ] **Step 6: Commit**

```
feat(#19): ReconciliationLoop — interval-grouped scheduling + CAS merge-and-retry fix
```

---

### Task 8: CdiNodeProvisionerRouter

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/CdiNodeProvisionerRouter.java`

**Interfaces:**
- Consumes: `Instance<NodeProvisioner>` (CDI), `DefaultNodeProvisionerRouter`
- Produces: `@ApplicationScoped NodeProvisionerRouter` CDI bean

- [ ] **Step 1: Create CdiNodeProvisionerRouter**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NodeProvisioner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

@ApplicationScoped
public class CdiNodeProvisionerRouter extends DefaultNodeProvisionerRouter {

    @Inject
    public CdiNodeProvisionerRouter(Instance<NodeProvisioner> provisioners) {
        super(StreamSupport.stream(provisioners.spliterator(), false).toList());
    }
}
```

- [ ] **Step 2: Run full runtime build**

Run: `mvn --batch-mode -pl runtime test`
Expected: PASS — CDI wiring compiles, existing tests unaffected

- [ ] **Step 3: Commit**

```
feat(#18): CdiNodeProvisionerRouter — CDI bean collecting all NodeProvisioner instances
```

---

### Task 9: Example provisioner updates

**Files:**
- Modify: `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/GoblinProvisioner.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineProvisioner.java`
- Modify: example test files and wiring as needed to pass `NodeProvisionerRouter`

**Interfaces:**
- Consumes: `NodeProvisioner.handledTypes()` (abstract — must implement)

- [ ] **Step 1: Add handledTypes() to GoblinProvisioner**

```java
@Override
public Set<NodeType> handledTypes() {
    return Set.of(DungeonNodeTypes.ROOM, DungeonNodeTypes.CREATURE, DungeonNodeTypes.TRAP);
}
```

Add `import java.util.Set;` to imports.

- [ ] **Step 2: Add handledTypes() to PipelineProvisioner**

```java
@Override
public Set<NodeType> handledTypes() {
    return Set.of(
        PipelineNodeTypes.DATA_SOURCE, PipelineNodeTypes.SCHEMA,
        PipelineNodeTypes.AI_REVIEW, PipelineNodeTypes.HUMAN_REVIEW,
        PipelineNodeTypes.PROCESSING_STAGE);
}
```

Add `import java.util.Set;` to imports. Verify `PipelineNodeTypes.PROCESSING_STAGE` exists — check the file. If processing stages use individual NodeTypes per stage, list all of them.

- [ ] **Step 3: Update example wiring**

Example tests and application classes that create `SimpleTransitionExecutor` directly need to wrap their provisioner in a `DefaultNodeProvisionerRouter`. Search for `new SimpleTransitionExecutor(` in both example modules and update.

- [ ] **Step 4: Run example tests**

Run: `mvn --batch-mode -pl examples/dungeon test`
Run: `mvn --batch-mode -pl examples/pipeline test`
Expected: PASS

- [ ] **Step 5: Commit**

```
feat(#18): example provisioners — implement handledTypes()
```

---

### Task 10: DurationPreference + Preferences integration

**Files:**
- Create: `casehub-platform-api: io/casehub/platform/api/preferences/DurationPreference.java` (cross-repo)
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/DesiredStatePreferenceKeys.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultNodeProvisionerRouter.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/CdiNodeProvisionerRouter.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultNodeProvisionerRouterPreferencesTest.java`

**Interfaces:**
- Consumes: `PreferenceProvider`, `Preferences.get(PreferenceKey, String subKey)`
- Produces: `DurationPreference` record, `DesiredStatePreferenceKeys.RESYNC_INTERVAL`

**⚠️ Cross-repo dependency:** This task requires creating `DurationPreference` in `casehub-platform-api` and publishing a SNAPSHOT before the desiredstate runtime can use it. Coordinate with the user on timing.

- [ ] **Step 1: Create DurationPreference in casehub-platform-api (cross-repo)**

```java
package io.casehub.platform.api.preferences;

import java.time.Duration;
import java.util.Objects;

public record DurationPreference(Duration duration) implements MultiValuePreference {
    public DurationPreference {
        Objects.requireNonNull(duration, "duration must not be null");
    }
}
```

Commit and `mvn install` in casehub-platform to publish SNAPSHOT locally.

- [ ] **Step 2: Write failing tests for Preferences override**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import io.casehub.platform.api.preferences.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class DefaultNodeProvisionerRouterPreferencesTest {

    static final NodeType IOT_TYPE = NodeType.of("physical-device");

    @Test
    void preferencesOverrideProvisionerDefault() {
        var prov = mockProvisioner(Set.of(IOT_TYPE), Duration.ofSeconds(30));

        var prefs = new StubPreferences(Map.of(
            "desiredstate.resync.physical-device", "PT10S"
        ));
        var provider = new StubPreferenceProvider(prefs);

        var router = new DefaultNodeProvisionerRouter(List.of(prov), provider);

        assertThat(router.resyncIntervalFor(IOT_TYPE)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void preferencesOverrideBelowFloorThrows() {
        var prov = mockProvisioner(Set.of(IOT_TYPE), Duration.ofSeconds(30));

        var prefs = new StubPreferences(Map.of(
            "desiredstate.resync.physical-device", "PT0S"
        ));
        var provider = new StubPreferenceProvider(prefs);

        var router = new DefaultNodeProvisionerRouter(List.of(prov), provider);

        assertThatThrownBy(() -> router.resyncIntervalFor(IOT_TYPE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be");
    }

    @Test
    void noPreferencesOverrideFallsBackToProvisionerDefault() {
        var prov = mockProvisioner(Set.of(IOT_TYPE), Duration.ofSeconds(30));
        var provider = new StubPreferenceProvider(new StubPreferences(Map.of()));

        var router = new DefaultNodeProvisionerRouter(List.of(prov), provider);

        assertThat(router.resyncIntervalFor(IOT_TYPE)).isEqualTo(Duration.ofSeconds(30));
    }

    private MockNodeProvisioner mockProvisioner(Set<NodeType> types, Duration resync) {
        var mock = new MockNodeProvisioner();
        mock.setHandledTypes(types);
        mock.setResyncInterval(resync);
        return mock;
    }

    // Stub implementations for testing without CDI
    record StubPreferenceProvider(Preferences prefs) implements PreferenceProvider {
        @Override
        public Preferences resolve(SettingsScope scope) { return prefs; }
    }

    static class StubPreferences implements Preferences {
        private final Map<String, String> values;
        StubPreferences(Map<String, String> values) { this.values = values; }

        @Override
        public <T extends SingleValuePreference> T get(PreferenceKey<T> key) { return null; }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends MultiValuePreference> T get(PreferenceKey<T> key, String subKey) {
            String raw = values.get(key.qualifiedName() + "." + subKey);
            if (raw == null) return null;
            return (T) key.parse(raw);
        }

        @Override
        public Map<String, Object> asMap() { return Map.copyOf(values); }
    }
}
```

- [ ] **Step 3: Add PreferenceProvider to DefaultNodeProvisionerRouter**

Add a second constructor that accepts `PreferenceProvider`. The existing single-arg constructor creates a no-op provider (for tests without Preferences):

```java
// Existing constructor — no Preferences (for tests)
public DefaultNodeProvisionerRouter(Collection<NodeProvisioner> provisioners) {
    this(provisioners, scope -> new NoOpPreferences());
}

// Full constructor with Preferences
public DefaultNodeProvisionerRouter(Collection<NodeProvisioner> provisioners,
                                     PreferenceProvider preferenceProvider) {
    // existing routing table construction...
    this.preferenceProvider = preferenceProvider;
}
```

Update `resyncIntervalFor()`:

```java
@Override
public Duration resyncIntervalFor(NodeType type) {
    if (preferenceProvider != null) {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.root());
        DurationPreference override = prefs.get(
            DesiredStatePreferenceKeys.RESYNC_INTERVAL, type.value());
        if (override != null) {
            Duration value = override.duration();
            if (value.compareTo(MIN_RESYNC) < 0) {
                throw new IllegalArgumentException(
                    "Preferences override for " + type.value() + " is "
                    + value + "; must be ≥ " + MIN_RESYNC);
            }
            return value;
        }
    }
    NodeProvisioner p = routing.get(type);
    return p != null ? p.resyncInterval() : DEFAULT_RESYNC;
}
```

- [ ] **Step 4: Create DesiredStatePreferenceKeys**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.platform.api.preferences.DurationPreference;
import io.casehub.platform.api.preferences.PreferenceKey;

import java.time.Duration;

public final class DesiredStatePreferenceKeys {

    private DesiredStatePreferenceKeys() {}

    public static final PreferenceKey<DurationPreference> RESYNC_INTERVAL =
        new PreferenceKey<>("desiredstate", "resync",
            new DurationPreference(Duration.ofMinutes(5)),
            s -> new DurationPreference(Duration.parse(s)));
}
```

- [ ] **Step 5: Update CdiNodeProvisionerRouter to pass PreferenceProvider**

```java
@ApplicationScoped
public class CdiNodeProvisionerRouter extends DefaultNodeProvisionerRouter {

    @Inject
    public CdiNodeProvisionerRouter(Instance<NodeProvisioner> provisioners,
                                     PreferenceProvider preferenceProvider) {
        super(StreamSupport.stream(provisioners.spliterator(), false).toList(),
              preferenceProvider);
    }
}
```

- [ ] **Step 6: Run Preferences tests**

Run: `mvn --batch-mode -pl runtime test -Dtest=DefaultNodeProvisionerRouterPreferencesTest`
Expected: PASS

- [ ] **Step 7: Run full build**

Run: `mvn --batch-mode install`
Expected: PASS — all modules compile and pass tests

- [ ] **Step 8: Commit**

```
feat(#19): Preferences integration — per-NodeType resync interval overrides via DurationPreference
```

---

### Task 11: CLAUDE.md + ARC42STORIES.MD updates

**Files:**
- Modify: `CLAUDE.md` — update SPI table and core runtime types
- Modify: `ARC42STORIES.MD` — update relevant sections

- [ ] **Step 1: Update CLAUDE.md**

Add `NodeProvisionerRouter` to the Core SPIs table. Add `handledTypes()` to the NodeProvisioner row. Remove `ReactiveNodeProvisioner` row. Update module descriptions for runtime (add NodeProvisionerRouter implementation note).

- [ ] **Step 2: Update ARC42STORIES.MD**

Update relevant layer/chapter statuses. Remove ReactiveNodeProvisioner references. Add multi-provisioner dispatch and per-type scheduling to the appropriate section.

- [ ] **Step 3: Commit**

```
docs(#18,#19): update CLAUDE.md + ARC42STORIES.MD for type-aware runtime
```
