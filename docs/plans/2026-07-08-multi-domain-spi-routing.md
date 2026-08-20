# Multi-Domain SPI Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #51 — Multi-domain: ActualStateAdapter routing for multi-provisioner deployments
**Issue group:** #51, #52

**Goal:** Enable multi-domain deployments by routing ActualStateAdapter calls by NodeType
and composing EventSource streams from multiple domains.

**Architecture:** Mirror the NodeProvisionerRouter pattern for ActualStateAdapter (SPI interface
→ Router interface → Default impl → CDI impl). Introduce MergedEventSource as a separate
consumer-facing interface that composes multiple EventSource streams via Multi.merge() with
per-stream error isolation. Add DesiredStateGraph.filterByTypes() as a shared default method.

**Tech Stack:** Java 21, Quarkus 3.x CDI (ArC), SmallRye Mutiny, JUnit 5, AssertJ

## Global Constraints

- Pre-release: breaking changes are free. Fix the design, don't protect callers.
- All CDI-scoped classes: Quarkus ArC generates proxy constructors automatically — do NOT add
  explicit protected no-args constructors.
- `handledTypes()` on SPIs has no default — every implementation must declare its types.
- Use `ide_find_references`, `ide_search_text`, `ide_find_class` for navigation — never bash grep.
- Use `ide_refactor_rename`, `ide_move_file` for structural changes — never bash mv/rm.

---

### Task 1: API — ActualStateAdapter.handledTypes() and ActualStateAdapterRouter

Add `handledTypes()` to the `ActualStateAdapter` interface, create the new
`ActualStateAdapterRouter` interface, and update the SPI contract test.

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/ActualStateAdapter.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ActualStateAdapterRouter.java`
- Modify: `api/src/test/java/io/casehub/desiredstate/api/SpiContractTest.java`

**Interfaces:**
- Produces: `ActualStateAdapter.handledTypes() → Set<NodeType>` (abstract, no default)
- Produces: `ActualStateAdapterRouter.readActual(DesiredStateGraph, String) → ActualState`
- Produces: `ActualStateAdapterRouter.allHandledTypes() → Set<NodeType>`

- [ ] **Step 1: Write the failing SPI contract test**

```java
@Test void actualStateAdapter_requiresHandledTypes() {
    ActualStateAdapter adapter = new ActualStateAdapter() {
        @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return new ActualState(Map.of());
        }
        @Override public Set<NodeType> handledTypes() {
            return Set.of(NodeType.of("test-type"));
        }
    };
    assertThat(adapter.handledTypes()).containsExactly(NodeType.of("test-type"));
    assertThat(adapter.readActual(null, "t")).isNotNull();
}
```

Update the existing `actualStateAdapter_canBeImplemented` test — it currently uses a lambda,
which will fail to compile once `handledTypes()` is added (two abstract methods = not a
functional interface).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=SpiContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure — `handledTypes()` does not exist on `ActualStateAdapter`

- [ ] **Step 3: Add handledTypes() to ActualStateAdapter**

```java
package io.casehub.desiredstate.api;

import java.util.Set;

public interface ActualStateAdapter {
    ActualState readActual(DesiredStateGraph desired, String tenancyId);
    Set<NodeType> handledTypes();
}
```

- [ ] **Step 4: Create ActualStateAdapterRouter interface**

```java
package io.casehub.desiredstate.api;

import java.util.Set;

public interface ActualStateAdapterRouter {
    ActualState readActual(DesiredStateGraph desired, String tenancyId);
    Set<NodeType> allHandledTypes();
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=SpiContractTest`
Expected: PASS

Note: Other modules will now have compilation errors (ActualStateAdapter implementations
missing `handledTypes()`). This is expected — they are fixed in later tasks.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/casehub/desiredstate/api/ActualStateAdapter.java \
        api/src/main/java/io/casehub/desiredstate/api/ActualStateAdapterRouter.java \
        api/src/test/java/io/casehub/desiredstate/api/SpiContractTest.java
git commit -m "feat(#51): add handledTypes() to ActualStateAdapter and ActualStateAdapterRouter interface"
```

---

### Task 2: API — MergedEventSource interface and DesiredStateGraph.filterByTypes()

Create the `MergedEventSource` consumer-facing interface and add `filterByTypes()` as a
default method on `DesiredStateGraph`.

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/MergedEventSource.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/DesiredStateGraph.java`
- Modify: `api/src/test/java/io/casehub/desiredstate/api/SpiContractTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ImmutableDesiredStateGraphTest.java`

**Interfaces:**
- Produces: `MergedEventSource.stream() → Multi<StateEvent>` (functional interface)
- Produces: `DesiredStateGraph.filterByTypes(Set<NodeType>) → DesiredStateGraph` (default method)

- [ ] **Step 1: Write failing test for filterByTypes**

Add to `ImmutableDesiredStateGraphTest`:

```java
@Test void filterByTypes_retainsMatchingNodesAndInternalDeps() {
    NodeType typeA = NodeType.of("a");
    NodeType typeB = NodeType.of("b");
    DesiredNode n1 = new DesiredNode(NodeId.of("n1"), new TestSpec("x"), false);
    DesiredNode n2 = new DesiredNode(NodeId.of("n2"), new TestSpec("x"), false);
    DesiredNode n3 = new DesiredNode(NodeId.of("n3"), new TestSpec("x"), false);

    DesiredStateGraph graph = ImmutableDesiredStateGraph.empty()
        .withNode(n1).withNode(n2).withNode(n3)
        .withDependency(new Dependency(NodeId.of("n2"), NodeId.of("n1")))
        .withDependency(new Dependency(NodeId.of("n3"), NodeId.of("n1")));

    DesiredStateGraph filtered = graph.filterByTypes(Set.of(typeA));

    assertThat(filtered.nodes()).containsOnlyKeys(NodeId.of("n1"), NodeId.of("n2"));
    assertThat(filtered.dependencies()).containsExactly(
        new Dependency(NodeId.of("n2"), NodeId.of("n1")));
}

@Test void filterByTypes_emptySetProducesEmptyGraph() {
    DesiredNode n1 = new DesiredNode(NodeId.of("n1"), new TestSpec("x"), false);
    DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(n1);

    DesiredStateGraph filtered = graph.filterByTypes(Set.of());
    assertThat(filtered.isEmpty()).isTrue();
}

@Test void filterByTypes_allTypesRetainsFullGraph() {
    NodeType typeA = NodeType.of("a");
    DesiredNode n1 = new DesiredNode(NodeId.of("n1"), new TestSpec("x"), false);
    DesiredNode n2 = new DesiredNode(NodeId.of("n2"), new TestSpec("x"), false);
    DesiredStateGraph graph = ImmutableDesiredStateGraph.empty()
        .withNode(n1).withNode(n2)
        .withDependency(new Dependency(NodeId.of("n2"), NodeId.of("n1")));

    DesiredStateGraph filtered = graph.filterByTypes(Set.of(typeA));
    assertThat(filtered.nodes()).hasSize(2);
    assertThat(filtered.dependencies()).hasSize(1);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=ImmutableDesiredStateGraphTest#filterByTypes* -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure — `filterByTypes` does not exist

- [ ] **Step 3: Add filterByTypes default method to DesiredStateGraph**

```java
default DesiredStateGraph filterByTypes(Set<NodeType> types) {
    DesiredStateGraph result = this;
    for (Map.Entry<NodeId, DesiredNode> entry : nodes().entrySet()) {
        if (!types.contains(entry.getValue().type())) {
            result = result.withoutNode(entry.getKey());
        }
    }
    return result;
}
```

This uses the subtractive approach — `withoutNode()` already handles dependency cleanup.

- [ ] **Step 4: Create MergedEventSource interface**

```java
package io.casehub.desiredstate.api;

import io.smallrye.mutiny.Multi;

public interface MergedEventSource {
    Multi<StateEvent> stream();
}
```

- [ ] **Step 5: Add SPI contract test for MergedEventSource**

```java
@Test void mergedEventSource_canBeImplemented() {
    MergedEventSource merged = () -> Multi.createFrom().empty();
    assertThat(merged.stream()).isNotNull();
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api,runtime -Dtest="SpiContractTest,ImmutableDesiredStateGraphTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (api + runtime tests for these classes)

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/casehub/desiredstate/api/DesiredStateGraph.java \
        api/src/main/java/io/casehub/desiredstate/api/MergedEventSource.java \
        api/src/test/java/io/casehub/desiredstate/api/SpiContractTest.java \
        runtime/src/test/java/io/casehub/desiredstate/runtime/ImmutableDesiredStateGraphTest.java
git commit -m "feat(#51,#52): add MergedEventSource interface and DesiredStateGraph.filterByTypes() default method"
```

---

### Task 3: Runtime — DefaultActualStateAdapterRouter and CdiActualStateAdapterRouter

Implement the router that dispatches `readActual()` to the correct adapter by NodeType,
and its CDI wiring subclass.

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultActualStateAdapterRouter.java`
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/CdiActualStateAdapterRouter.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultActualStateAdapterRouterTest.java`

**Interfaces:**
- Consumes: `ActualStateAdapter.handledTypes()` (from Task 1)
- Consumes: `ActualStateAdapterRouter` (from Task 1)
- Consumes: `DesiredStateGraph.filterByTypes()` (from Task 2)
- Produces: `DefaultActualStateAdapterRouter(Collection<ActualStateAdapter>)` constructor
- Produces: `CdiActualStateAdapterRouter` — `@ApplicationScoped` CDI bean

- [ ] **Step 1: Write failing tests for DefaultActualStateAdapterRouter**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class DefaultActualStateAdapterRouterTest {

    record TestSpec(String name) implements NodeSpec {}

    static final NodeType TYPE_A = NodeType.of("type-a");
    static final NodeType TYPE_B = NodeType.of("type-b");

    @Test void routesToCorrectAdapterByNodeType() {
        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };
        ActualStateAdapter adapterB = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_B); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.ABSENT));
                return new ActualState(statuses);
            }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA, adapterB));
        var nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("x"), false);
        var nodeB = new DesiredNode(NodeId.of("b"), new TestSpec("x"), false);
        var graph = ImmutableDesiredStateGraph.empty().withNode(nodeA).withNode(nodeB);

        ActualState result = router.readActual(graph, "tenant-1");

        assertThat(result.statuses()).containsEntry(NodeId.of("a"), NodeStatus.PRESENT);
        assertThat(result.statuses()).containsEntry(NodeId.of("b"), NodeStatus.ABSENT);
    }

    @Test void rejectsOverlappingTypes() {
        ActualStateAdapter adapter1 = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };
        ActualStateAdapter adapter2 = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };

        assertThatThrownBy(() -> new DefaultActualStateAdapterRouter(List.of(adapter1, adapter2)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type-a");
    }

    @Test void uncoveredNodesGetUnknownStatus() {
        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) {
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                d.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA));
        var nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("x"), false);
        var nodeUnknown = new DesiredNode(NodeId.of("u"), new TestSpec("x"), false);
        var graph = ImmutableDesiredStateGraph.empty().withNode(nodeA).withNode(nodeUnknown);

        ActualState result = router.readActual(graph, "tenant-1");

        assertThat(result.statuses()).containsEntry(NodeId.of("a"), NodeStatus.PRESENT);
        assertThat(result.statuses()).containsEntry(NodeId.of("u"), NodeStatus.UNKNOWN);
    }

    @Test void allHandledTypesReturnsUnionOfAdapterTypes() {
        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };
        ActualStateAdapter adapterB = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_B); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA, adapterB));
        assertThat(router.allHandledTypes()).containsExactlyInAnyOrder(TYPE_A, TYPE_B);
    }

    @Test void emptyGraphProducesEmptyActualState() {
        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA));
        var graph = ImmutableDesiredStateGraph.empty();

        ActualState result = router.readActual(graph, "tenant-1");
        assertThat(result.statuses()).isEmpty();
    }

    @Test void adapterReceivesFilteredGraph() {
        var receivedNodes = new ArrayList<Set<NodeId>>();

        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph desired, String t) {
                receivedNodes.add(desired.nodes().keySet());
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };
        ActualStateAdapter adapterB = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_B); }
            @Override public ActualState readActual(DesiredStateGraph desired, String t) {
                receivedNodes.add(desired.nodes().keySet());
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA, adapterB));
        var nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("x"), false);
        var nodeB = new DesiredNode(NodeId.of("b"), new TestSpec("x"), false);
        var graph = ImmutableDesiredStateGraph.empty().withNode(nodeA).withNode(nodeB);

        router.readActual(graph, "tenant-1");

        assertThat(receivedNodes).hasSize(2);
        assertThat(receivedNodes.get(0)).containsExactly(NodeId.of("a"));
        assertThat(receivedNodes.get(1)).containsExactly(NodeId.of("b"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultActualStateAdapterRouterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure — `DefaultActualStateAdapterRouter` does not exist

- [ ] **Step 3: Implement DefaultActualStateAdapterRouter**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;

import java.util.*;

public class DefaultActualStateAdapterRouter implements ActualStateAdapterRouter {

    private final Map<NodeType, ActualStateAdapter> routing;

    public DefaultActualStateAdapterRouter(Collection<ActualStateAdapter> adapters) {
        Map<NodeType, ActualStateAdapter> table = new LinkedHashMap<>();
        for (ActualStateAdapter adapter : adapters) {
            for (NodeType type : adapter.handledTypes()) {
                ActualStateAdapter existing = table.put(type, adapter);
                if (existing != null) {
                    throw new IllegalArgumentException(
                        "NodeType " + type.value() + " claimed by both "
                        + existing.getClass().getName() + " and "
                        + adapter.getClass().getName());
                }
            }
        }
        this.routing = Map.copyOf(table);
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        // Partition nodes by adapter
        Map<ActualStateAdapter, Set<NodeType>> adapterTypes = new LinkedHashMap<>();
        Set<NodeId> uncoveredNodes = new LinkedHashSet<>();

        for (DesiredNode node : desired.nodes().values()) {
            ActualStateAdapter adapter = routing.get(node.type());
            if (adapter != null) {
                adapterTypes.computeIfAbsent(adapter, k -> new LinkedHashSet<>()).add(node.type());
            } else {
                uncoveredNodes.add(node.id());
            }
        }

        // Call each adapter with its filtered subgraph
        Map<NodeId, NodeStatus> merged = new HashMap<>();
        for (Map.Entry<ActualStateAdapter, Set<NodeType>> entry : adapterTypes.entrySet()) {
            DesiredStateGraph filtered = desired.filterByTypes(entry.getValue());
            ActualState partial = entry.getKey().readActual(filtered, tenancyId);
            merged.putAll(partial.statuses());
        }

        // Uncovered nodes get UNKNOWN
        for (NodeId uncovered : uncoveredNodes) {
            merged.put(uncovered, NodeStatus.UNKNOWN);
        }

        return new ActualState(merged);
    }

    @Override
    public Set<NodeType> allHandledTypes() {
        return routing.keySet();
    }
}
```

- [ ] **Step 4: Create CdiActualStateAdapterRouter**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualStateAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

@ApplicationScoped
public class CdiActualStateAdapterRouter extends DefaultActualStateAdapterRouter {

    @Inject
    public CdiActualStateAdapterRouter(Instance<ActualStateAdapter> adapters) {
        super(StreamSupport.stream(adapters.spliterator(), false).toList());
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultActualStateAdapterRouterTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultActualStateAdapterRouter.java \
        runtime/src/main/java/io/casehub/desiredstate/runtime/CdiActualStateAdapterRouter.java \
        runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultActualStateAdapterRouterTest.java
git commit -m "feat(#51): implement DefaultActualStateAdapterRouter and CdiActualStateAdapterRouter"
```

---

### Task 4: Runtime — DefaultMergedEventSource and CdiMergedEventSource

Implement the event source compositor that merges multiple domain EventSource streams
with per-stream error isolation.

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultMergedEventSource.java`
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/CdiMergedEventSource.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultMergedEventSourceTest.java`

**Interfaces:**
- Consumes: `MergedEventSource` (from Task 2)
- Consumes: `EventSource` (existing api/)
- Produces: `DefaultMergedEventSource(Collection<EventSource>)` constructor
- Produces: `CdiMergedEventSource` — `@ApplicationScoped` CDI bean

- [ ] **Step 1: Write failing tests for DefaultMergedEventSource**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class DefaultMergedEventSourceTest {

    @Test void emptySourcesProducesEmptyStream() {
        var merged = new DefaultMergedEventSource(List.of());
        List<StateEvent> events = merged.stream()
            .collect().asList().await().atMost(Duration.ofSeconds(1));
        assertThat(events).isEmpty();
    }

    @Test void singleSourcePassesThrough() {
        var event = new StateEvent(NodeId.of("n1"), NodeStatus.PRESENT, "ok");
        EventSource source = () -> Multi.createFrom().item(event);

        var merged = new DefaultMergedEventSource(List.of(source));
        List<StateEvent> events = merged.stream()
            .collect().asList().await().atMost(Duration.ofSeconds(1));
        assertThat(events).containsExactly(event);
    }

    @Test void multipleSourcesMergeEvents() {
        var e1 = new StateEvent(NodeId.of("n1"), NodeStatus.PRESENT, "from-a");
        var e2 = new StateEvent(NodeId.of("n2"), NodeStatus.ABSENT, "from-b");
        EventSource sourceA = () -> Multi.createFrom().item(e1);
        EventSource sourceB = () -> Multi.createFrom().item(e2);

        var merged = new DefaultMergedEventSource(List.of(sourceA, sourceB));
        List<StateEvent> events = merged.stream()
            .collect().asList().await().atMost(Duration.ofSeconds(1));
        assertThat(events).containsExactlyInAnyOrder(e1, e2);
    }

    @Test void failedStreamDoesNotKillOtherStreams() {
        var e1 = new StateEvent(NodeId.of("n1"), NodeStatus.PRESENT, "ok");
        EventSource healthy = () -> Multi.createFrom().item(e1);
        EventSource failing = () -> Multi.createFrom().failure(new RuntimeException("boom"));

        var merged = new DefaultMergedEventSource(List.of(healthy, failing));
        List<StateEvent> events = merged.stream()
            .collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(events).contains(e1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultMergedEventSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure — `DefaultMergedEventSource` does not exist

- [ ] **Step 3: Implement DefaultMergedEventSource**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.MergedEventSource;
import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.Multi;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

public class DefaultMergedEventSource implements MergedEventSource {

    private static final Logger LOG = Logger.getLogger(DefaultMergedEventSource.class.getName());

    private final List<EventSource> sources;

    public DefaultMergedEventSource(Collection<EventSource> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public Multi<StateEvent> stream() {
        if (sources.isEmpty()) return Multi.createFrom().empty();
        if (sources.size() == 1) return sources.getFirst().stream();
        return Multi.createBy().merging().streams(
            sources.stream()
                .map(s -> s.stream()
                    .onFailure().retry()
                        .withBackOff(Duration.ofSeconds(1))
                        .atMost(3)
                    .onFailure().recoverWithMulti(failure -> {
                        LOG.warning("EventSource stream failed after retries: "
                            + failure.getMessage());
                        return Multi.createFrom().empty();
                    }))
                .toList()
        );
    }
}
```

- [ ] **Step 4: Create CdiMergedEventSource**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.EventSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

@ApplicationScoped
public class CdiMergedEventSource extends DefaultMergedEventSource {

    @Inject
    public CdiMergedEventSource(Instance<EventSource> sources) {
        super(StreamSupport.stream(sources.spliterator(), false).toList());
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultMergedEventSourceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultMergedEventSource.java \
        runtime/src/main/java/io/casehub/desiredstate/runtime/CdiMergedEventSource.java \
        runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultMergedEventSourceTest.java
git commit -m "feat(#52): implement DefaultMergedEventSource and CdiMergedEventSource with per-stream error isolation"
```

---

### Task 5: Runtime — Migrate ReconciliationLoop to ActualStateAdapterRouter and MergedEventSource

Replace `ActualStateAdapter` and `EventSource` fields/injection in ReconciliationLoop with
`ActualStateAdapterRouter` and `MergedEventSource`. Replace `filterGraph()` with
`desired.filterByTypes()`. Add provisioner-adapter cross-validation at startup.

**Files:**
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`

**Interfaces:**
- Consumes: `ActualStateAdapterRouter` (from Task 1/3)
- Consumes: `MergedEventSource` (from Task 2/4)
- Consumes: `DesiredStateGraph.filterByTypes()` (from Task 2)

This is a breaking change to ReconciliationLoop constructors — all test files will need
updating (Task 6). Implement this task first, then fix tests.

- [ ] **Step 1: Replace fields and master constructor**

Change the fields:
- `ActualStateAdapter actualStateAdapter` → `ActualStateAdapterRouter actualStateAdapterRouter`
- `EventSource eventSource` → `MergedEventSource mergedEventSource`

Update the private master constructor signature and all delegating constructors.

CDI constructor signature becomes:
```java
@Inject
public ReconciliationLoop(
        TransitionPlanner planner,
        TransitionExecutor executor,
        ActualStateAdapterRouter actualStateAdapterRouter,
        FaultPolicyEngine faultPolicyEngine,
        MergedEventSource mergedEventSource,
        NodeProvisionerRouter router,
        Event<CloudEvent> cloudEventSink)
```

Add startup cross-validation in the CDI constructor (after the `this(...)` call):
```java
if (router != null && actualStateAdapterRouter instanceof DefaultActualStateAdapterRouter) {
    Set<NodeType> provisionerTypes = router.allHandledTypes();
    Set<NodeType> adapterTypes = actualStateAdapterRouter.allHandledTypes();
    if (!adapterTypes.containsAll(provisionerTypes)) {
        Set<NodeType> uncovered = new LinkedHashSet<>(provisionerTypes);
        uncovered.removeAll(adapterTypes);
        throw new IllegalArgumentException(
            "NodeTypes handled by provisioners but not by any ActualStateAdapter: " + uncovered);
    }
}
```

Test constructors: change `ActualStateAdapter` param to `ActualStateAdapterRouter`,
change `EventSource` param to `MergedEventSource`.

- [ ] **Step 2: Replace filterGraph() with filterByTypes()**

Delete the `filterGraph()` private method. Replace the two call sites in
`TenantLoop.reconcileTypes()` and `computeIntervalGroups()`:

```java
// Before: DesiredStateGraph filteredDesired = filterGraph(fullDesired, types);
// After:
DesiredStateGraph filteredDesired = fullDesired.filterByTypes(types);
```

- [ ] **Step 3: Update field references throughout**

Replace all references:
- `actualStateAdapter.readActual(` → `actualStateAdapterRouter.readActual(`
- `eventSource.stream()` → `mergedEventSource.stream()`

The `readActual()` helper method in TenantLoop becomes:
```java
private ActualState readActual(DesiredStateGraph desired, String tenancyId) {
    // ... tracing unchanged ...
    ActualState actual = actualStateAdapterRouter.readActual(desired, tenancyId);
    // ...
}
```

- [ ] **Step 4: Verify compilation**

Run: `mvn --batch-mode compile -pl runtime`
Expected: Compilation failures in test files (expected — fixed in Task 6)

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java
git commit -m "refactor(#51,#52): migrate ReconciliationLoop to ActualStateAdapterRouter and MergedEventSource"
```

---

### Task 6: Runtime — Migrate all runtime tests to new constructor signatures

Update all ReconciliationLoop test files and LifecycleManagerTest to use
`ActualStateAdapterRouter` and `MergedEventSource`.

**Files:**
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopCloudEventTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopLifecycleTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopRequestReconciliationTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopSchedulingTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationTracingTest.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/LifecycleManagerTest.java`

**Interfaces:**
- Consumes: `DefaultActualStateAdapterRouter(Collection<ActualStateAdapter>)` (from Task 3)
- Consumes: `MergedEventSource` (from Task 2) — functional interface, accepts `testEventSource::stream`

**Migration pattern for each test file:**

1. Add `handledTypes()` to `TestActualStateAdapter` inner classes or inline adapter implementations.
   Use a configurable set or `Set.of(NodeType.of("t"))` for simple cases.

2. Wrap the adapter: `var adapterRouter = new DefaultActualStateAdapterRouter(List.of(testAdapter));`

3. For lambda-style adapters (`ActualStateAdapter adapter = (desired, tenancyId) -> { ... }`):
   convert to a full inner class with `handledTypes()`, or use an anonymous class.

4. Change `EventSource` references to `MergedEventSource`:
   - `TestEventSource` inner classes: wrap as `MergedEventSource merged = testEventSource::stream;`
   - Lambda-style event sources: cast or wrap as `MergedEventSource`

5. Pass `adapterRouter` and `merged` to ReconciliationLoop constructors.

- [ ] **Step 1: Fix ReconciliationLoopTest**

The test has a `TestActualStateAdapter` inner class. Add `handledTypes()` returning
`Set.of(NodeType.of("t"))`. Wrap in `DefaultActualStateAdapterRouter`. Convert
`TestEventSource` to `MergedEventSource` via `testEventSource::stream`.

- [ ] **Step 2: Fix ReconciliationLoopCloudEventTest**

Same pattern — add `handledTypes()` to `TestActualStateAdapter`, wrap in router,
convert `TestEventSource` to `MergedEventSource`.

- [ ] **Step 3: Fix ReconciliationLoopLifecycleTest**

Same pattern for `TestActualStateAdapter` and `TestEventSource`.

- [ ] **Step 4: Fix ReconciliationLoopRequestReconciliationTest**

This test uses lambda-style adapter: `ActualStateAdapter adapter = (desired, tenancyId) -> { ... }`.
Convert to anonymous class with `handledTypes()`, wrap in router.

- [ ] **Step 5: Fix ReconciliationLoopSchedulingTest**

This test uses `FAST_TYPE` and `SLOW_TYPE`. The adapter lambdas need
`handledTypes()` returning `Set.of(FAST_TYPE, SLOW_TYPE)`. Wrap in router.

- [ ] **Step 6: Fix ReconciliationTracingTest**

Same pattern for `TestActualStateAdapter` and `TestEventSource`.

- [ ] **Step 7: Fix LifecycleManagerTest**

`TrackingActualStateAdapter` gains `handledTypes()`. Wrap in router. Event source
wraps to `MergedEventSource`.

- [ ] **Step 8: Run all runtime tests**

Run: `mvn --batch-mode test -pl runtime`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add runtime/src/test/
git commit -m "test(#51,#52): migrate runtime tests to ActualStateAdapterRouter and MergedEventSource"
```

---

### Task 7: Testing module — MockActualStateAdapter.handledTypes()

Add configurable `handledTypes()` to `MockActualStateAdapter`, matching the pattern in
`MockNodeProvisioner`.

**Files:**
- Modify: `testing/src/main/java/io/casehub/desiredstate/testing/MockActualStateAdapter.java`

**Interfaces:**
- Produces: `MockActualStateAdapter.setHandledTypes(Set<NodeType>)` — setter
- Produces: `MockActualStateAdapter.handledTypes()` — returns configured set

- [ ] **Step 1: Add handledTypes field and methods**

```java
private Set<NodeType> handledTypes = Set.of();

@Override
public Set<NodeType> handledTypes() {
    return handledTypes;
}

public void setHandledTypes(Set<NodeType> types) {
    this.handledTypes = Set.copyOf(types);
}
```

Update `clear()` to also reset `handledTypes = Set.of()`.

- [ ] **Step 2: Verify compilation**

Run: `mvn --batch-mode compile -pl testing`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add testing/src/main/java/io/casehub/desiredstate/testing/MockActualStateAdapter.java
git commit -m "feat(#51): add handledTypes() to MockActualStateAdapter"
```

---

### Task 8: Example modules — Add handledTypes() to all ActualStateAdapter implementations

Add `handledTypes()` to all four example adapters, returning the same types as their
corresponding provisioners.

**Files:**
- Modify: `examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/DungeonActualStateAdapter.java`
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineActualStateAdapter.java`
- Modify: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/world/BattlefieldActualStateAdapter.java`
- Modify: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionActualStateAdapter.java`

**Interfaces:**
- Each adapter produces: `handledTypes() → Set<NodeType>` matching its provisioner

- [ ] **Step 1: DungeonActualStateAdapter**

```java
@Override
public Set<NodeType> handledTypes() {
    return Set.of(DungeonNodeTypes.ROOM, DungeonNodeTypes.CREATURE, DungeonNodeTypes.TRAP);
}
```

- [ ] **Step 2: PipelineActualStateAdapter**

```java
@Override
public Set<NodeType> handledTypes() {
    return Set.of(
        PipelineNodeTypes.DATA_SOURCE, PipelineNodeTypes.SCHEMA,
        PipelineNodeTypes.INGESTION, PipelineNodeTypes.CLEANSER,
        PipelineNodeTypes.ENRICHER, PipelineNodeTypes.VALIDATOR,
        PipelineNodeTypes.TRANSFORMER, PipelineNodeTypes.SINK,
        PipelineNodeTypes.AI_REVIEW, PipelineNodeTypes.HUMAN_REVIEW
    );
}
```

- [ ] **Step 3: BattlefieldActualStateAdapter**

```java
@Override
public Set<NodeType> handledTypes() {
    return Set.of(SpatialNodeTypes.CELL, SpatialNodeTypes.UNIT,
                  SpatialNodeTypes.SCOUT, SpatialNodeTypes.ZONE);
}
```

- [ ] **Step 4: ExpansionActualStateAdapter**

```java
@Override
public Set<NodeType> handledTypes() {
    return Set.of(
        ExpansionNodeTypes.PROBE, ExpansionNodeTypes.NEXUS,
        ExpansionNodeTypes.PYLON, ExpansionNodeTypes.CANNON,
        ExpansionNodeTypes.PATROL, ExpansionNodeTypes.MONITOR,
        ExpansionNodeTypes.RESPONSE
    );
}
```

- [ ] **Step 5: Run full build**

Run: `mvn --batch-mode install`
Expected: ALL PASS — full project compiles and all tests pass

- [ ] **Step 6: Commit**

```bash
git add examples/dungeon/src/main/java/io/casehub/desiredstate/example/dungeon/DungeonActualStateAdapter.java \
        examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineActualStateAdapter.java \
        examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/world/BattlefieldActualStateAdapter.java \
        examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionActualStateAdapter.java
git commit -m "feat(#51): add handledTypes() to all example ActualStateAdapter implementations"
```

---

### Task 9: CLAUDE.md and ARC42STORIES.MD updates

Update project documentation to reflect the new SPI routing types.

**Files:**
- Modify: `CLAUDE.md` — add new types to Core SPIs table, Core Runtime Types, Module Structure
- Modify: `ARC42STORIES.MD` — update relevant sections

- [ ] **Step 1: Update CLAUDE.md**

Add to the Core SPIs table:
- `ActualStateAdapter` — add `handledTypes() → Set<NodeType>` description
- `ActualStateAdapterRouter` — new entry: `readActual(DesiredStateGraph, String) → ActualState`
  and `allHandledTypes() → Set<NodeType>`
- `MergedEventSource` — new entry: `stream() → Multi<StateEvent>`

Add to Core Runtime Types:
- `DefaultActualStateAdapterRouter` — Runtime implementation of ActualStateAdapterRouter
- `CdiActualStateAdapterRouter` — CDI-wired subclass
- `DefaultMergedEventSource` — Runtime implementation with per-stream error isolation
- `CdiMergedEventSource` — CDI-wired subclass

- [ ] **Step 2: Update ARC42STORIES.MD**

Update §5 (Building Block View) to reflect the new router and compositor types.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md ARC42STORIES.MD
git commit -m "docs(#51,#52): update CLAUDE.md and ARC42STORIES.MD for multi-domain SPI routing"
```
