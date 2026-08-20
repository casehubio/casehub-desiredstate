# CBR Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #23 — design: CBR integration for desired-state evolution
**Issue group:** #23

**Goal:** Add Case-Based Reasoning integration to the desired-state runtime — retrieve past
successful configurations when faults or situations arise, adapt them to the current context,
and apply them as graph mutations (tactical) or graph replacements (strategic).

**Architecture:** Three new SPIs in `api/` (`ConfigurationRetriever`, `ConfigurationAdapter`,
plus domain types). Two runtime implementations in `runtime/` (`CbrFaultPolicy`, `CbrSituationRecompiler`)
executing the retrieve → adapt → apply chain. A new `SituationRecompilerEngine` aggregating
recompilers via chain-of-responsibility with priority ordering. The existing `SituationRecompiler`
SPI evolves with an `ActualState` parameter and `priority()` default method (breaking change).
`GraphDiff` utility converts adapted graph fragments to `List<GraphMutation>`.

**Tech Stack:** Java 21, Quarkus CDI, casehub-platform-api preferences

**Spec:** `docs/specs/2026-07-10-cbr-integration-design.md`

## Global Constraints

- Pre-release platform — breaking changes are free; prefer fixing the design over protecting callers
- All `NodeSpec` implementations are records (value semantics for `equals()`/`hashCode()`)
- Preference resolution is per-call via `PreferenceProvider.resolve(SettingsScope.root())`, not frozen at startup
- `DoublePreference`/`IntPreference` defined locally in `api/` until promoted to platform-api (tracked as issue)
- CBR SPIs use `@DefaultBean` no-op defaults — CBR is inert when no domain provides implementations
- `SituationRecompiler` signature change is breaking — all implementations update mechanically

---

### Task 1: Domain Types and SPIs (api/)

New types and interfaces that everything else depends on.

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/DoublePreference.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/IntPreference.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/RetrievalContext.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/RetrievedConfiguration.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/AdaptedConfiguration.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/CbrConfiguration.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ConfigurationRetriever.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ConfigurationAdapter.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/RetrievalContextTest.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/RetrievedConfigurationTest.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/CbrConfigurationTest.java`

**Interfaces:**
- Consumes: `DesiredStateGraph`, `ActualState`, `FaultEvent`, `ActiveSituation` (existing api types), `SingleValuePreference` (platform-api)
- Produces: `RetrievalContext`, `RetrievedConfiguration`, `AdaptedConfiguration`, `CbrConfiguration`, `ConfigurationRetriever`, `ConfigurationAdapter`, `DoublePreference`, `IntPreference` — used by Tasks 2-6

- [ ] **Step 1: Write tests for DoublePreference**

```java
// api/src/test/java/io/casehub/desiredstate/api/DoublePreferenceTest.java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DoublePreferenceTest {

    @Test
    void shouldStoreValue() {
        DoublePreference pref = new DoublePreference(0.75);
        assertThat(pref.value()).isEqualTo(0.75);
    }

    @Test
    void shouldParseFromString() {
        DoublePreference pref = DoublePreference.parse("0.42");
        assertThat(pref.value()).isEqualTo(0.42);
    }

    @Test
    void shouldRejectNullParse() {
        assertThatThrownBy(() -> DoublePreference.parse(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveValueSemantics() {
        assertThat(new DoublePreference(0.5)).isEqualTo(new DoublePreference(0.5));
        assertThat(new DoublePreference(0.5).hashCode()).isEqualTo(new DoublePreference(0.5).hashCode());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=DoublePreferenceTest`
Expected: FAIL — class not found

- [ ] **Step 3: Implement DoublePreference and IntPreference**

```java
// api/src/main/java/io/casehub/desiredstate/api/DoublePreference.java
package io.casehub.desiredstate.api;

import io.casehub.platform.api.preferences.SingleValuePreference;
import java.util.Objects;

public record DoublePreference(double value) implements SingleValuePreference {
    public static DoublePreference parse(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        return new DoublePreference(Double.parseDouble(raw));
    }
}
```

```java
// api/src/main/java/io/casehub/desiredstate/api/IntPreference.java
package io.casehub.desiredstate.api;

import io.casehub.platform.api.preferences.SingleValuePreference;
import java.util.Objects;

public record IntPreference(int value) implements SingleValuePreference {
    public static IntPreference parse(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        return new IntPreference(Integer.parseInt(raw));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl api -Dtest=DoublePreferenceTest`
Expected: PASS

- [ ] **Step 5: Write tests for RetrievalContext**

```java
// api/src/test/java/io/casehub/desiredstate/api/RetrievalContextTest.java
package io.casehub.desiredstate.api;

import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RetrievalContextTest {

    private final DesiredStateGraph graph = emptyGraph();
    private final ActualState actual = new ActualState(Map.of());

    @Test
    void forFault_shouldPopulateFaultEvent() {
        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout");
        RetrievalContext ctx = RetrievalContext.forFault(graph, actual, event);

        assertThat(ctx.faultEvent()).isEqualTo(event);
        assertThat(ctx.situation()).isNull();
        assertThat(ctx.currentGraph()).isSameAs(graph);
        assertThat(ctx.actualState()).isSameAs(actual);
    }

    @Test
    void forSituation_shouldPopulateSituation() {
        ActiveSituation situation = new ActiveSituation(
            "sit-1", "zone-A", "tenant-1", 0.95,
            Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);
        RetrievalContext ctx = RetrievalContext.forSituation(graph, actual, situation);

        assertThat(ctx.situation()).isEqualTo(situation);
        assertThat(ctx.faultEvent()).isNull();
    }

    @Test
    void forFault_shouldRejectNullGraph() {
        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x");
        assertThatThrownBy(() -> RetrievalContext.forFault(null, actual, event))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void forFault_shouldRejectNullActual() {
        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x");
        assertThatThrownBy(() -> RetrievalContext.forFault(graph, null, event))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void forFault_shouldRejectNullEvent() {
        assertThatThrownBy(() -> RetrievalContext.forFault(graph, actual, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void forSituation_shouldRejectNullSituation() {
        assertThatThrownBy(() -> RetrievalContext.forSituation(graph, actual, null))
            .isInstanceOf(NullPointerException.class);
    }

    private static DesiredStateGraph emptyGraph() {
        // Minimal stub — only nodes()/dependencies() needed
        return new DesiredStateGraph() {
            public Map<NodeId, DesiredNode> nodes() { return Map.of(); }
            public java.util.Set<Dependency> dependencies() { return java.util.Set.of(); }
            public java.util.Set<NodeId> dependenciesOf(NodeId n) { return java.util.Set.of(); }
            public java.util.Set<NodeId> dependentsOf(NodeId n) { return java.util.Set.of(); }
            public java.util.Set<NodeId> roots() { return java.util.Set.of(); }
            public java.util.Set<NodeId> leaves() { return java.util.Set.of(); }
            public int version() { return 0; }
            public boolean isEmpty() { return true; }
            public DesiredStateGraph withNode(DesiredNode node) { return this; }
            public DesiredStateGraph withoutNode(NodeId id) { return this; }
            public DesiredStateGraph withDependency(Dependency dep) { return this; }
            public DesiredStateGraph withoutDependency(Dependency dep) { return this; }
            public DesiredStateGraph withMutation(GraphMutation m) { return this; }
            public DesiredStateGraph overlay(DesiredStateGraph o) { return this; }
            public DesiredStateGraph connect(DesiredStateGraph o) { return this; }
        };
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=RetrievalContextTest`
Expected: FAIL — class not found

- [ ] **Step 7: Implement RetrievalContext**

```java
// api/src/main/java/io/casehub/desiredstate/api/RetrievalContext.java
package io.casehub.desiredstate.api;

import io.casehub.ras.api.ActiveSituation;
import java.util.Objects;

public record RetrievalContext(
    DesiredStateGraph currentGraph,
    ActualState actualState,
    FaultEvent faultEvent,
    ActiveSituation situation
) {
    RetrievalContext {
        Objects.requireNonNull(currentGraph, "currentGraph must not be null");
        Objects.requireNonNull(actualState, "actualState must not be null");
    }

    public static RetrievalContext forFault(DesiredStateGraph graph, ActualState actual,
                                            FaultEvent event) {
        Objects.requireNonNull(event, "faultEvent must not be null");
        return new RetrievalContext(graph, actual, event, null);
    }

    public static RetrievalContext forSituation(DesiredStateGraph graph, ActualState actual,
                                                 ActiveSituation situation) {
        Objects.requireNonNull(situation, "situation must not be null");
        return new RetrievalContext(graph, actual, null, situation);
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn --batch-mode test -pl api -Dtest=RetrievalContextTest`
Expected: PASS

- [ ] **Step 9: Write tests for RetrievedConfiguration and CbrConfiguration**

```java
// api/src/test/java/io/casehub/desiredstate/api/RetrievedConfigurationTest.java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RetrievedConfigurationTest {

    @Test
    void shouldRejectNullGraph() {
        assertThatThrownBy(() -> new RetrievedConfiguration(null, 0.9, "src-1", Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSourceId() {
        assertThatThrownBy(() -> new RetrievedConfiguration(emptyGraph(), 0.9, null, Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectConfidenceOutOfRange() {
        assertThatThrownBy(() -> new RetrievedConfiguration(emptyGraph(), -0.1, "src-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievedConfiguration(emptyGraph(), 1.1, "src-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievedConfiguration(emptyGraph(), Double.NaN, "src-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptValidConfiguration() {
        RetrievedConfiguration config = new RetrievedConfiguration(
            emptyGraph(), 0.85, "case-42", Map.of("reason", "provision-fix"));
        assertThat(config.confidence()).isEqualTo(0.85);
        assertThat(config.sourceId()).isEqualTo("case-42");
        assertThat(config.metadata()).containsEntry("reason", "provision-fix");
    }

    @Test
    void shouldDefensiveCopyMetadata() {
        java.util.HashMap<String, String> mutable = new java.util.HashMap<>();
        mutable.put("key", "val");
        RetrievedConfiguration config = new RetrievedConfiguration(emptyGraph(), 0.5, "s", mutable);
        mutable.put("extra", "bad");
        assertThat(config.metadata()).doesNotContainKey("extra");
    }

    private static DesiredStateGraph emptyGraph() {
        return RetrievalContextTest.emptyGraph();
    }
}
```

```java
// api/src/test/java/io/casehub/desiredstate/api/CbrConfigurationTest.java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CbrConfigurationTest {

    @Test
    void shouldRejectNegativeRetrievalConfidence() {
        assertThatThrownBy(() -> new CbrConfiguration(-0.1, 0.6, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectRetrievalConfidenceAboveOne() {
        assertThatThrownBy(() -> new CbrConfiguration(1.1, 0.6, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveMaxCandidates() {
        assertThatThrownBy(() -> new CbrConfiguration(0.5, 0.6, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptValidConfiguration() {
        CbrConfiguration config = new CbrConfiguration(0.5, 0.6, 3);
        assertThat(config.minimumRetrievalConfidence()).isEqualTo(0.5);
        assertThat(config.minimumAdaptationConfidence()).isEqualTo(0.6);
        assertThat(config.maxCandidates()).isEqualTo(3);
    }
}
```

- [ ] **Step 10: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest="RetrievedConfigurationTest,CbrConfigurationTest"`
Expected: FAIL — classes not found

- [ ] **Step 11: Implement RetrievedConfiguration, AdaptedConfiguration, CbrConfiguration, ConfigurationRetriever, ConfigurationAdapter**

```java
// api/src/main/java/io/casehub/desiredstate/api/RetrievedConfiguration.java
package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Objects;

public record RetrievedConfiguration(
    DesiredStateGraph graph,
    double confidence,
    String sourceId,
    Map<String, String> metadata
) {
    public RetrievedConfiguration {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0-1.0, got: " + confidence);
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
```

```java
// api/src/main/java/io/casehub/desiredstate/api/AdaptedConfiguration.java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record AdaptedConfiguration(
    DesiredStateGraph graph,
    double confidence,
    String sourceId
) {
    public AdaptedConfiguration {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0-1.0, got: " + confidence);
        }
    }
}
```

```java
// api/src/main/java/io/casehub/desiredstate/api/CbrConfiguration.java
package io.casehub.desiredstate.api;

public record CbrConfiguration(
    double minimumRetrievalConfidence,
    double minimumAdaptationConfidence,
    int maxCandidates
) {
    public CbrConfiguration {
        if (Double.isNaN(minimumRetrievalConfidence) || minimumRetrievalConfidence < 0.0 || minimumRetrievalConfidence > 1.0) {
            throw new IllegalArgumentException("minimumRetrievalConfidence must be 0.0-1.0");
        }
        if (Double.isNaN(minimumAdaptationConfidence) || minimumAdaptationConfidence < 0.0 || minimumAdaptationConfidence > 1.0) {
            throw new IllegalArgumentException("minimumAdaptationConfidence must be 0.0-1.0");
        }
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("maxCandidates must be >= 1");
        }
    }
}
```

```java
// api/src/main/java/io/casehub/desiredstate/api/ConfigurationRetriever.java
package io.casehub.desiredstate.api;

import java.util.List;

public interface ConfigurationRetriever {
    List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults);
}
```

```java
// api/src/main/java/io/casehub/desiredstate/api/ConfigurationAdapter.java
package io.casehub.desiredstate.api;

import java.util.Optional;

public interface ConfigurationAdapter {
    Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context);
}
```

- [ ] **Step 12: Run all api tests**

Run: `mvn --batch-mode test -pl api -Dtest="DoublePreferenceTest,RetrievalContextTest,RetrievedConfigurationTest,CbrConfigurationTest"`
Expected: PASS

Note: make `RetrievalContextTest.emptyGraph()` package-private (not private) so `RetrievedConfigurationTest` can use it.

- [ ] **Step 13: Commit**

```bash
git add api/src/main/java/io/casehub/desiredstate/api/DoublePreference.java api/src/main/java/io/casehub/desiredstate/api/IntPreference.java api/src/main/java/io/casehub/desiredstate/api/RetrievalContext.java api/src/main/java/io/casehub/desiredstate/api/RetrievedConfiguration.java api/src/main/java/io/casehub/desiredstate/api/AdaptedConfiguration.java api/src/main/java/io/casehub/desiredstate/api/CbrConfiguration.java api/src/main/java/io/casehub/desiredstate/api/ConfigurationRetriever.java api/src/main/java/io/casehub/desiredstate/api/ConfigurationAdapter.java api/src/test/java/io/casehub/desiredstate/api/DoublePreferenceTest.java api/src/test/java/io/casehub/desiredstate/api/RetrievalContextTest.java api/src/test/java/io/casehub/desiredstate/api/RetrievedConfigurationTest.java api/src/test/java/io/casehub/desiredstate/api/CbrConfigurationTest.java
git commit -m "feat(#23): CBR domain types and SPIs — RetrievalContext, RetrievedConfiguration, AdaptedConfiguration, CbrConfiguration, ConfigurationRetriever, ConfigurationAdapter"
```

---

### Task 2: SituationRecompiler SPI Evolution

Breaking change: add `ActualState` parameter, reorder parameters, add `priority()` default method.
Update all implementations and callers.

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/SituationRecompiler.java`
- Modify: `api/src/test/java/io/casehub/desiredstate/api/SituationRecompilerTest.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpSituationRecompiler.java`
- Modify: `runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpSituationRecompilerTest.java`
- Modify: `examples/expansion/src/main/java/io/casehub/desiredstate/example/expansion/ExpansionSituationRecompiler.java`
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatch.java`
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatchTest.java`

**Interfaces:**
- Consumes: `ActualState`, `ActualStateAdapterRouter` (existing api types)
- Produces: Evolved `SituationRecompiler` with `recompile(DesiredStateGraph, ActualState, ActiveSituation, DesiredStateGraphFactory)` and `default int priority()` — used by Tasks 3, 5

- [ ] **Step 1: Update SituationRecompiler interface**

Use `ide_edit_member` to replace the `SituationRecompiler` interface declaration.

New signature:
```java
public interface SituationRecompiler {
    Optional<CompilationResult> recompile(
        DesiredStateGraph current,
        ActualState actual,
        ActiveSituation situation,
        DesiredStateGraphFactory factory);

    default int priority() { return 0; }
}
```

Parameter order groups by purpose: state inputs (`current`, `actual`), trigger (`situation`), utility (`factory`).

- [ ] **Step 2: Update SituationRecompilerTest**

Replace lambda signatures `(current, situation, factory) ->` with `(current, actual, situation, factory) ->`. Update call sites:
```java
recompiler.recompile(null, new ActualState(Map.of()), situation, mockFactory)
```

- [ ] **Step 3: Run api tests to verify**

Run: `mvn --batch-mode test -pl api -Dtest=SituationRecompilerTest`
Expected: PASS

- [ ] **Step 4: Update NoOpSituationRecompiler**

Add `ActualState actual` parameter to `recompile()`:
```java
@Override
public Optional<CompilationResult> recompile(
        DesiredStateGraph current,
        ActualState actual,
        ActiveSituation situation,
        DesiredStateGraphFactory factory) {
    return Optional.empty();
}
```

- [ ] **Step 5: Update NoOpSituationRecompilerTest**

```java
Optional<CompilationResult> result = recompiler.recompile(
    current, new ActualState(Map.of()), situation, null);
```

- [ ] **Step 6: Run runtime tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=NoOpSituationRecompilerTest`
Expected: PASS

- [ ] **Step 7: Update ExpansionSituationRecompiler**

Add `ActualState actual` parameter (unused but present):
```java
@Override
public Optional<CompilationResult> recompile(
        DesiredStateGraph current,
        ActualState actual,
        ActiveSituation situation,
        DesiredStateGraphFactory factory) {
    ExpansionGoal revised = originalGoal.withDefensePosture(DefensePosture.FORTIFY);
    return Optional.of(compiler.compile(revised, factory));
}
```

Add import for `ActualState`.

- [ ] **Step 8: Update DesiredStateReplanDispatch**

Inject `ActualStateAdapterRouter`. Read `ActualState` before calling recompiler:
```java
private final ActualStateAdapterRouter actualStateRouter;

// In constructor: add ActualStateAdapterRouter parameter

// In replan():
DesiredStateGraph current = reconciliationLoop.getDesired(tenancyId);
ActualState actual = actualStateRouter.readActual(current, tenancyId);

Optional<CompilationResult> newResult = situationRecompiler.recompile(
    current, actual, situation, graphFactory);
```

- [ ] **Step 9: Update DesiredStateReplanDispatchTest**

Update `mockRecompiler` lambda to accept 4 params. Create a stub `ActualStateAdapterRouter`:
```java
ActualStateAdapterRouter stubRouter = (graph, tid) -> new ActualState(Map.of());

dispatch = new DesiredStateReplanDispatch(
    mockLifecycleManager, mockLoop, mockRecompiler, mockFactory, callRegistry, stubRouter);
```

- [ ] **Step 10: Run all engine-adapter tests**

Run: `mvn --batch-mode test -pl engine-adapter`
Expected: PASS

- [ ] **Step 11: Run full build to catch any missed callers**

Run: `mvn --batch-mode install -DskipTests=false`
Expected: BUILD SUCCESS

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat(#23): evolve SituationRecompiler SPI — add ActualState parameter, priority() default method, reorder parameters"
```

---

### Task 3: SituationRecompilerEngine + NoOpSituationRecompiler Removal

Chain-of-responsibility aggregator for multiple SituationRecompiler beans.
Replaces `NoOpSituationRecompiler` — the engine handles empty lists.

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/SituationRecompilerEngine.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/SituationRecompilerEngineTest.java`
- Delete: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpSituationRecompiler.java` (use `ide_refactor_safe_delete`)
- Delete: `runtime/src/test/java/io/casehub/desiredstate/runtime/NoOpSituationRecompilerTest.java`
- Modify: `engine-adapter/src/main/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatch.java`
- Modify: `engine-adapter/src/test/java/io/casehub/desiredstate/engine/DesiredStateReplanDispatchTest.java`

**Interfaces:**
- Consumes: `SituationRecompiler` (evolved, from Task 2), `CompilationResult`, `DesiredStateGraph`, `ActualState`, `ActiveSituation`, `DesiredStateGraphFactory`
- Produces: `SituationRecompilerEngine` — used by Task 5 (CbrSituationRecompiler registers into the engine's recompiler list)

- [ ] **Step 1: Write SituationRecompilerEngineTest**

```java
// runtime/src/test/java/io/casehub/desiredstate/runtime/SituationRecompilerEngineTest.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class SituationRecompilerEngineTest {

    private final DesiredStateGraph graph = ImmutableDesiredStateGraph.empty();
    private final ActualState actual = new ActualState(Map.of());
    private final ActiveSituation situation = new ActiveSituation(
        "sit-1", "zone-A", "tenant-1", 0.95,
        Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);

    @Test
    void emptyRecompilerList_shouldReturnEmpty() {
        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of());
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);
        assertThat(result).isEmpty();
    }

    @Test
    void singleRecompiler_returnsNonEmpty_shouldReturnResult() {
        DesiredStateGraph newGraph = graph.withNode(
            new DesiredNode(NodeId.of("n1"), new TestSpec("v1"), false));
        SituationRecompiler recompiler = (c, a, s, f) -> Optional.of(CompilationResult.single(newGraph));

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(recompiler));
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);

        assertThat(result).isPresent();
    }

    @Test
    void multipleRecompilers_firstReturnsEmpty_secondReturnsResult() {
        SituationRecompiler empty = (c, a, s, f) -> Optional.empty();
        SituationRecompiler hasResult = (c, a, s, f) -> Optional.of(CompilationResult.single(graph));

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(empty, hasResult));
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);

        assertThat(result).isPresent();
    }

    @Test
    void allRecompilersReturnEmpty_shouldReturnEmpty() {
        SituationRecompiler e1 = (c, a, s, f) -> Optional.empty();
        SituationRecompiler e2 = (c, a, s, f) -> Optional.empty();

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(e1, e2));
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldRespectPriorityOrdering() {
        DesiredStateGraph lowGraph = graph.withNode(
            new DesiredNode(NodeId.of("low"), new TestSpec("low"), false));
        DesiredStateGraph highGraph = graph.withNode(
            new DesiredNode(NodeId.of("high"), new TestSpec("high"), false));

        SituationRecompiler lowPriority = new SituationRecompiler() {
            @Override
            public Optional<CompilationResult> recompile(DesiredStateGraph c, ActualState a,
                    ActiveSituation s, DesiredStateGraphFactory f) {
                return Optional.of(CompilationResult.single(lowGraph));
            }
            @Override
            public int priority() { return 0; }
        };

        SituationRecompiler highPriority = new SituationRecompiler() {
            @Override
            public Optional<CompilationResult> recompile(DesiredStateGraph c, ActualState a,
                    ActiveSituation s, DesiredStateGraphFactory f) {
                return Optional.of(CompilationResult.single(highGraph));
            }
            @Override
            public int priority() { return Integer.MAX_VALUE; }
        };

        // Pass in wrong order — engine should sort by priority
        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(highPriority, lowPriority));
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);

        assertThat(result).isPresent();
        CompilationResult.SingleGraph sg = (CompilationResult.SingleGraph) result.get();
        assertThat(sg.graph().nodes()).containsKey(NodeId.of("low"));
    }

    @Test
    void firstMatchWins_shouldNotCallSubsequentRecompilers() {
        List<String> callOrder = new ArrayList<>();

        SituationRecompiler first = (c, a, s, f) -> {
            callOrder.add("first");
            return Optional.of(CompilationResult.single(graph));
        };
        SituationRecompiler second = (c, a, s, f) -> {
            callOrder.add("second");
            return Optional.of(CompilationResult.single(graph));
        };

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(first, second));
        engine.recompile(graph, actual, situation, null);

        assertThat(callOrder).containsExactly("first");
    }

    private record TestSpec(String value) implements NodeSpec {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationRecompilerEngineTest`
Expected: FAIL — class not found

- [ ] **Step 3: Implement SituationRecompilerEngine**

```java
// runtime/src/main/java/io/casehub/desiredstate/runtime/SituationRecompilerEngine.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.ActiveSituation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.All;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SituationRecompilerEngine {

    private final List<SituationRecompiler> recompilers;

    SituationRecompilerEngine(@All List<SituationRecompiler> recompilers) {
        this.recompilers = recompilers.stream()
            .sorted(Comparator.comparingInt(SituationRecompiler::priority))
            .toList();
    }

    public Optional<CompilationResult> recompile(
            DesiredStateGraph current, ActualState actual,
            ActiveSituation situation, DesiredStateGraphFactory factory) {
        for (SituationRecompiler recompiler : recompilers) {
            Optional<CompilationResult> result = recompiler.recompile(
                current, actual, situation, factory);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationRecompilerEngineTest`
Expected: PASS

- [ ] **Step 5: Delete NoOpSituationRecompiler and its test**

Use `ide_refactor_safe_delete` for `NoOpSituationRecompiler`. If usages are found, review and update them. Delete `NoOpSituationRecompilerTest` similarly.

- [ ] **Step 6: Update DesiredStateReplanDispatch to inject SituationRecompilerEngine**

Replace `SituationRecompiler situationRecompiler` field with `SituationRecompilerEngine recompilerEngine`. Update constructor and `replan()` method:

```java
Optional<CompilationResult> newResult = recompilerEngine.recompile(
    current, actual, situation, graphFactory);
```

Remove the `ActualStateAdapterRouter` injection from Task 2 — `DesiredStateReplanDispatch`
now passes `actual` through the engine, but still needs to *read* it. Keep the router injection:
the dispatch reads actual state and passes it to the engine.

- [ ] **Step 7: Update DesiredStateReplanDispatchTest**

Replace `mockRecompiler` with a `SituationRecompilerEngine`:
```java
SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(mockRecompiler));
dispatch = new DesiredStateReplanDispatch(
    mockLifecycleManager, mockLoop, engine, mockFactory, callRegistry, stubRouter);
```

- [ ] **Step 8: Run full build**

Run: `mvn --batch-mode install -DskipTests=false`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(#23): SituationRecompilerEngine — chain-of-responsibility aggregation, remove NoOpSituationRecompiler"
```

---

### Task 4: GraphDiff Utility

Converts an adapted graph fragment into `List<GraphMutation>` by diffing against the current graph.

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/GraphDiff.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/GraphDiffTest.java`

**Interfaces:**
- Consumes: `DesiredStateGraph`, `GraphMutation`, `DesiredNode`, `NodeId`, `Dependency`, `NodeSpec`
- Produces: `GraphDiff.computeMutations(DesiredStateGraph current, DesiredStateGraph adapted) → List<GraphMutation>` — used by Task 5

- [ ] **Step 1: Write GraphDiffTest**

```java
// runtime/src/test/java/io/casehub/desiredstate/runtime/GraphDiffTest.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class GraphDiffTest {

    private record TestSpec(String value) implements NodeSpec {}

    private DesiredStateGraph graph(DesiredNode... nodes) {
        DesiredStateGraph g = ImmutableDesiredStateGraph.empty();
        for (DesiredNode n : nodes) {
            g = g.withNode(n);
        }
        return g;
    }

    private DesiredNode node(String id, String spec) {
        return new DesiredNode(NodeId.of(id), new TestSpec(spec), false);
    }

    @Test
    void emptyBothGraphs_shouldReturnNoMutations() {
        List<GraphMutation> mutations = GraphDiff.computeMutations(
            ImmutableDesiredStateGraph.empty(), ImmutableDesiredStateGraph.empty());
        assertThat(mutations).isEmpty();
    }

    @Test
    void newNodeInAdapted_shouldProduceAddNode() {
        DesiredStateGraph current = ImmutableDesiredStateGraph.empty();
        DesiredStateGraph adapted = graph(node("n1", "v1"));

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        assertThat(((GraphMutation.AddNode) mutations.get(0)).node().id()).isEqualTo(NodeId.of("n1"));
    }

    @Test
    void changedSpecInAdapted_shouldProduceUpdateNode() {
        DesiredStateGraph current = graph(node("n1", "v1"));
        DesiredStateGraph adapted = graph(node("n1", "v2"));

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.UpdateNode.class);
        GraphMutation.UpdateNode update = (GraphMutation.UpdateNode) mutations.get(0);
        assertThat(update.id()).isEqualTo(NodeId.of("n1"));
        assertThat(update.newSpec()).isEqualTo(new TestSpec("v2"));
    }

    @Test
    void unchangedNode_shouldProduceNoMutations() {
        DesiredStateGraph current = graph(node("n1", "v1"));
        DesiredStateGraph adapted = graph(node("n1", "v1"));

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).isEmpty();
    }

    @Test
    void removedNodeInScope_shouldProduceRemoveNode() {
        DesiredStateGraph current = graph(node("n1", "v1"), node("n2", "v2"));
        DesiredStateGraph adapted = graph(node("n1", "v1"));
        // n2 is NOT in the adapted graph's scope — but wait, n2 IS in current.
        // Scope is defined by the adapted graph's node set.
        // n2 is not in adapted → but n2 is in current AND not in adapted node set → no RemoveNode.
        // Only nodes IN the adapted graph's scope that are ALSO in current are candidates for removal.
        // Since n2 is not in adapted's node set, it's out of scope.

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        // n1 is unchanged → no mutation. n2 is out of scope → no mutation.
        assertThat(mutations).isEmpty();
    }

    @Test
    void nodeInCurrentAndAdaptedScope_removedFromAdapted_shouldProduceRemoveNode() {
        // To test removal, we need a node that WAS in the adapted graph's scope in a prior version
        // but is now absent. The scope is the adapted graph's node set.
        // The spec says: "For each node in current graph within the adapted graph's node set (scope):
        //   Not in adapted → RemoveNode"
        // This means: iterate current nodes, check if they're in adapted's node set.
        // A node in current but NOT in adapted's node set is out-of-scope → not removed.
        // So removal only happens if a node ID appears in both current's node set and
        // ... wait, this means removal never happens unless we define scope differently.

        // Re-reading the spec: "scope is defined by the adapted graph's node set"
        // This means removal is for nodes that are in current AND in adapted's scope but not in adapted.
        // Since the scope IS the adapted node set, the only way a node is "in scope but not in adapted"
        // is impossible — if it's in the adapted node set, it IS in adapted.

        // The intent is: the adapted graph represents a *replacement* for a subset of the current graph.
        // If the adapted graph has nodes {A, B} and current has {A, B, C}, then C is untouched.
        // If the adapted graph has nodes {A} and current has {A, B}, should B be removed?
        // Only if B was "within the scope that the adapted graph is replacing."

        // The simplest correct interpretation: the adapted graph's node TYPE set defines scope.
        // Nodes of the same types as adapted nodes are in scope for removal.
        // This is consistent with filterByTypes() — scope by NodeType, not NodeId.

        DesiredNode n1 = node("n1", "v1");
        DesiredNode n2 = new DesiredNode(NodeId.of("n2"), new TestSpec("v2"), false);
        DesiredNode n3 = new DesiredNode(NodeId.of("n3"), new TestSpec("v3"), false);

        DesiredStateGraph current = graph(n1, n2, n3);
        // Adapted only has n1 — n2 is same type as n1 ("test"), n3 is different type ("other")
        DesiredStateGraph adapted = graph(n1);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        // n2 is same type as adapted nodes → in scope → removed
        // n3 is different type → out of scope → untouched
        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.RemoveNode.class);
        assertThat(((GraphMutation.RemoveNode) mutations.get(0)).id()).isEqualTo(NodeId.of("n2"));
    }

    @Test
    void addDependency_targetExistsInCurrent() {
        DesiredNode n1 = node("n1", "v1");
        DesiredNode n2 = new DesiredNode(NodeId.of("n2"), new TestSpec("v2"), false);

        DesiredStateGraph current = graph(n2); // n2 exists in current
        DesiredStateGraph adapted = graph(n1)
            .withDependency(new Dependency(NodeId.of("n1"), NodeId.of("n2")));

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.AddNode).hasSize(1);
        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.AddDependency).hasSize(1);
    }

    @Test
    void removeDependency_betweenInScopeNodes() {
        DesiredNode n1 = node("n1", "v1");
        DesiredNode n2 = node("n2", "v2");
        Dependency dep = new Dependency(NodeId.of("n1"), NodeId.of("n2"));

        DesiredStateGraph current = graph(n1, n2).withDependency(dep);
        DesiredStateGraph adapted = graph(n1, n2); // same nodes, no dependency

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.RemoveDependency.class);
    }

    @Test
    void crossBoundaryDependency_shouldNotBeRemoved() {
        DesiredNode n1 = node("n1", "v1");
        DesiredNode n2 = new DesiredNode(NodeId.of("n2"), new TestSpec("v2"), false);
        Dependency dep = new Dependency(NodeId.of("n1"), NodeId.of("n2"));

        DesiredStateGraph current = graph(n1, n2).withDependency(dep);
        DesiredStateGraph adapted = graph(n1); // n2 is out of scope

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        // n1 is unchanged, n2 is out of scope, cross-boundary dep is not removed
        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.RemoveDependency).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=GraphDiffTest`
Expected: FAIL — class not found

- [ ] **Step 3: Implement GraphDiff**

```java
// runtime/src/main/java/io/casehub/desiredstate/runtime/GraphDiff.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;

import java.util.*;

final class GraphDiff {

    private GraphDiff() {}

    static List<GraphMutation> computeMutations(DesiredStateGraph current, DesiredStateGraph adapted) {
        List<GraphMutation> mutations = new ArrayList<>();

        Set<NodeType> adaptedTypes = new HashSet<>();
        for (DesiredNode node : adapted.nodes().values()) {
            adaptedTypes.add(node.type());
        }

        // AddNode / UpdateNode
        for (Map.Entry<NodeId, DesiredNode> entry : adapted.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode adaptedNode = entry.getValue();
            DesiredNode currentNode = current.nodes().get(id);

            if (currentNode == null) {
                mutations.add(new GraphMutation.AddNode(adaptedNode));
            } else if (!Objects.equals(currentNode.spec(), adaptedNode.spec())) {
                mutations.add(new GraphMutation.UpdateNode(id, adaptedNode.spec()));
            }
        }

        // RemoveNode — nodes in current that are in-scope (same type as adapted nodes)
        // but not present in adapted
        for (Map.Entry<NodeId, DesiredNode> entry : current.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode currentNode = entry.getValue();
            if (adaptedTypes.contains(currentNode.type()) && !adapted.nodes().containsKey(id)) {
                mutations.add(new GraphMutation.RemoveNode(id));
            }
        }

        // AddDependency
        Set<NodeId> allKnownNodes = new HashSet<>();
        allKnownNodes.addAll(current.nodes().keySet());
        allKnownNodes.addAll(adapted.nodes().keySet());

        for (Dependency dep : adapted.dependencies()) {
            if (!current.dependencies().contains(dep)) {
                if (allKnownNodes.contains(dep.from()) && allKnownNodes.contains(dep.to())) {
                    mutations.add(new GraphMutation.AddDependency(dep));
                }
            }
        }

        // RemoveDependency — only between in-scope nodes (both endpoints in adapted type set)
        Set<NodeId> inScopeNodeIds = new HashSet<>();
        for (Map.Entry<NodeId, DesiredNode> entry : current.nodes().entrySet()) {
            if (adaptedTypes.contains(entry.getValue().type())) {
                inScopeNodeIds.add(entry.getKey());
            }
        }
        inScopeNodeIds.addAll(adapted.nodes().keySet());

        for (Dependency dep : current.dependencies()) {
            if (inScopeNodeIds.contains(dep.from()) && inScopeNodeIds.contains(dep.to())) {
                if (!adapted.dependencies().contains(dep)) {
                    mutations.add(new GraphMutation.RemoveDependency(dep));
                }
            }
        }

        return mutations;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl runtime -Dtest=GraphDiffTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/java/io/casehub/desiredstate/runtime/GraphDiff.java runtime/src/test/java/io/casehub/desiredstate/runtime/GraphDiffTest.java
git commit -m "feat(#23): GraphDiff utility — diff adapted graph fragments against current graph to produce mutations"
```

---

### Task 5: CbrFaultPolicy and CbrSituationRecompiler

The two runtime implementations that execute the retrieve → adapt → apply chain.

**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpConfigurationRetriever.java`
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpConfigurationAdapter.java`
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/CbrFaultPolicy.java`
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/CbrSituationRecompiler.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/DesiredStatePreferenceKeys.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/CbrFaultPolicyTest.java`
- Create: `runtime/src/test/java/io/casehub/desiredstate/runtime/CbrSituationRecompilerTest.java`

**Interfaces:**
- Consumes: `ConfigurationRetriever`, `ConfigurationAdapter`, `CbrConfiguration`, `RetrievalContext`, `RetrievedConfiguration`, `AdaptedConfiguration` (Task 1), `GraphDiff` (Task 4), `SituationRecompiler` (Task 2), `PreferenceProvider`, `DesiredStatePreferenceKeys`
- Produces: `CbrFaultPolicy` (registered as `FaultPolicy` CDI bean), `CbrSituationRecompiler` (registered as `SituationRecompiler` CDI bean with `Integer.MAX_VALUE` priority)

- [ ] **Step 1: Add CBR preference keys to DesiredStatePreferenceKeys**

```java
public static final PreferenceKey<DoublePreference> CBR_MIN_RETRIEVAL_CONFIDENCE =
    new PreferenceKey<>("desiredstate", "cbr.min-retrieval-confidence",
        new DoublePreference(0.5), DoublePreference::parse);

public static final PreferenceKey<DoublePreference> CBR_MIN_ADAPTATION_CONFIDENCE =
    new PreferenceKey<>("desiredstate", "cbr.min-adaptation-confidence",
        new DoublePreference(0.6), DoublePreference::parse);

public static final PreferenceKey<IntPreference> CBR_MAX_CANDIDATES =
    new PreferenceKey<>("desiredstate", "cbr.max-candidates",
        new IntPreference(3), IntPreference::parse);
```

Add imports for `DoublePreference`, `IntPreference`.

- [ ] **Step 2: Implement NoOpConfigurationRetriever and NoOpConfigurationAdapter**

```java
// runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpConfigurationRetriever.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpConfigurationRetriever implements ConfigurationRetriever {
    @Override
    public List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults) {
        return List.of();
    }
}
```

```java
// runtime/src/main/java/io/casehub/desiredstate/runtime/NoOpConfigurationAdapter.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class NoOpConfigurationAdapter implements ConfigurationAdapter {
    @Override
    public Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context) {
        return Optional.empty();
    }
}
```

- [ ] **Step 3: Write CbrFaultPolicyTest**

```java
// runtime/src/test/java/io/casehub/desiredstate/runtime/CbrFaultPolicyTest.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.api.preferences.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CbrFaultPolicyTest {

    private record TestSpec(String value) implements NodeSpec {}

    private StubRetriever retriever;
    private StubAdapter adapter;
    private PreferenceProvider prefProvider;
    private CbrFaultPolicy policy;

    @BeforeEach
    void setUp() {
        retriever = new StubRetriever();
        adapter = new StubAdapter();
        prefProvider = scope -> new MapPreferences(Map.of());
        policy = new CbrFaultPolicy(retriever, adapter, prefProvider);
    }

    @Test
    void noCandidates_shouldReturnEmptyMutations() {
        retriever.setResults(List.of());

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void candidateBelowRetrievalThreshold_shouldBeFiltered() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new TestSpec("new"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.3, "case-1", Map.of())));
        adapter.setResult(new AdaptedConfiguration(adapted, 0.9, "case-1"));

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty(); // 0.3 < default 0.5 threshold
    }

    @Test
    void candidateBelowAdaptationThreshold_shouldBeFiltered() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new TestSpec("new"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.8, "case-1", Map.of())));
        adapter.setResult(new AdaptedConfiguration(adapted, 0.4, "case-1"));

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty(); // 0.4 < default 0.6 threshold
    }

    @Test
    void successfulAdaptation_shouldProduceMutations() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new TestSpec("fixed"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        adapter.setResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
    }

    @Test
    void adapterReturnsEmpty_shouldReturnEmptyMutations() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty();
        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        adapter.setResult(null); // adapter returns Optional.empty()

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void shouldSelectHighestConfidenceAdaptation() {
        DesiredStateGraph low = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("low"), new TestSpec("low"), false));
        DesiredStateGraph high = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("high"), new TestSpec("high"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(low, 0.9, "case-low", Map.of()),
            new RetrievedConfiguration(high, 0.8, "case-high", Map.of())));
        adapter.setMultipleResults(Map.of(
            "case-low", new AdaptedConfiguration(low, 0.65, "case-low"),
            "case-high", new AdaptedConfiguration(high, 0.95, "case-high")));

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).hasSize(1);
        GraphMutation.AddNode add = (GraphMutation.AddNode) mutations.get(0);
        assertThat(add.node().id()).isEqualTo(NodeId.of("high"));
    }

    @Test
    void perCallPreferenceResolution_changedThreshold() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new TestSpec("v"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.3, "case-1", Map.of())));
        adapter.setResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        // Default threshold 0.5 → 0.3 is below → filtered
        List<GraphMutation> mutations1 = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x"),
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));
        assertThat(mutations1).isEmpty();

        // Lower threshold to 0.2 via preferences
        prefProvider = scope -> new MapPreferences(Map.of(
            DesiredStatePreferenceKeys.CBR_MIN_RETRIEVAL_CONFIDENCE.qualifiedName(), "0.2"));
        policy = new CbrFaultPolicy(retriever, adapter, prefProvider);

        List<GraphMutation> mutations2 = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x"),
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));
        assertThat(mutations2).hasSize(1);
    }

    // --- Test doubles ---

    private static class StubRetriever implements ConfigurationRetriever {
        private List<RetrievedConfiguration> results = List.of();
        void setResults(List<RetrievedConfiguration> results) { this.results = results; }
        @Override
        public List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults) {
            return results;
        }
    }

    private static class StubAdapter implements ConfigurationAdapter {
        private AdaptedConfiguration singleResult;
        private Map<String, AdaptedConfiguration> multiResults = Map.of();

        void setResult(AdaptedConfiguration result) { this.singleResult = result; }
        void setMultipleResults(Map<String, AdaptedConfiguration> results) { this.multiResults = results; }

        @Override
        public Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context) {
            if (!multiResults.isEmpty()) {
                return Optional.ofNullable(multiResults.get(retrieved.sourceId()));
            }
            return Optional.ofNullable(singleResult);
        }
    }

    private record MapPreferences(Map<String, String> values) implements Preferences {
        @Override
        public <T extends SingleValuePreference> T get(PreferenceKey<T> key) {
            String raw = values.get(key.qualifiedName());
            return raw != null ? key.parse(raw) : null;
        }
        @Override
        public <T extends MultiValuePreference> T get(PreferenceKey<T> key, String subKey) {
            return null;
        }
        @Override
        public Map<String, Object> asMap() { return Map.copyOf(values); }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrFaultPolicyTest`
Expected: FAIL — class not found

- [ ] **Step 5: Implement CbrFaultPolicy**

```java
// runtime/src/main/java/io/casehub/desiredstate/runtime/CbrFaultPolicy.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class CbrFaultPolicy implements FaultPolicy {

    private static final Logger LOG = Logger.getLogger(CbrFaultPolicy.class.getName());

    private final ConfigurationRetriever retriever;
    private final ConfigurationAdapter adapter;
    private final PreferenceProvider preferenceProvider;

    public CbrFaultPolicy(ConfigurationRetriever retriever,
                           ConfigurationAdapter adapter,
                           PreferenceProvider preferenceProvider) {
        this.retriever = retriever;
        this.adapter = adapter;
        this.preferenceProvider = preferenceProvider;
    }

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
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

    private CbrConfiguration resolveConfiguration() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.root());
        double minRetrieval = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MIN_RETRIEVAL_CONFIDENCE).value();
        double minAdaptation = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MIN_ADAPTATION_CONFIDENCE).value();
        int maxCandidates = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MAX_CANDIDATES).value();
        return new CbrConfiguration(minRetrieval, minAdaptation, maxCandidates);
    }
}
```

- [ ] **Step 6: Run CbrFaultPolicyTest**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrFaultPolicyTest`
Expected: PASS

- [ ] **Step 7: Write CbrSituationRecompilerTest**

```java
// runtime/src/test/java/io/casehub/desiredstate/runtime/CbrSituationRecompilerTest.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.api.preferences.*;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CbrSituationRecompilerTest {

    private record TestSpec(String value) implements NodeSpec {}

    private CbrFaultPolicyTest.StubRetriever retriever;
    private CbrFaultPolicyTest.StubAdapter adapter;
    private PreferenceProvider prefProvider;
    private CbrSituationRecompiler recompiler;

    private final ActiveSituation situation = new ActiveSituation(
        "sit-1", "zone-A", "tenant-1", 0.95,
        Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);

    @BeforeEach
    void setUp() {
        // Use same test doubles pattern — but CbrSituationRecompilerTest needs its own stubs
        // since they're package-private in CbrFaultPolicyTest
        retriever = new CbrFaultPolicyTest.StubRetriever();
        adapter = new CbrFaultPolicyTest.StubAdapter();
        prefProvider = scope -> new CbrFaultPolicyTest.MapPreferences(Map.of());
        recompiler = new CbrSituationRecompiler(retriever, adapter, prefProvider);
    }

    @Test
    void noCandidates_shouldReturnEmpty() {
        retriever.setResults(List.of());

        Optional<CompilationResult> result = recompiler.recompile(
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void successfulAdaptation_shouldReturnCompilationResult() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), new TestSpec("fixed"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        adapter.setResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        Optional<CompilationResult> result = recompiler.recompile(
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(CompilationResult.SingleGraph.class);
        CompilationResult.SingleGraph sg = (CompilationResult.SingleGraph) result.get();
        assertThat(sg.graph().nodes()).containsKey(NodeId.of("n1"));
    }

    @Test
    void shouldHaveMaxIntPriority() {
        assertThat(recompiler.priority()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void actualStateIsPassedToRetrievalContext() {
        ActualState actual = new ActualState(Map.of(NodeId.of("x"), NodeStatus.PRESENT));

        // Capture the context passed to retriever
        List<RetrievalContext> captured = new ArrayList<>();
        ConfigurationRetriever capturingRetriever = (ctx, max) -> {
            captured.add(ctx);
            return List.of();
        };
        CbrSituationRecompiler r = new CbrSituationRecompiler(capturingRetriever, adapter, prefProvider);

        r.recompile(ImmutableDesiredStateGraph.empty(), actual, situation, null);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).actualState()).isSameAs(actual);
        assertThat(captured.get(0).situation()).isEqualTo(situation);
        assertThat(captured.get(0).faultEvent()).isNull();
    }
}
```

Note: the test doubles from `CbrFaultPolicyTest` need to be made package-private (not private inner classes) so they can be reused. Alternatively, extract them to a shared test fixture. The simplest approach: make the `StubRetriever`, `StubAdapter`, and `MapPreferences` inner classes in `CbrFaultPolicyTest` non-private (package-access).

- [ ] **Step 8: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrSituationRecompilerTest`
Expected: FAIL — class not found

- [ ] **Step 9: Implement CbrSituationRecompiler**

```java
// runtime/src/main/java/io/casehub/desiredstate/runtime/CbrSituationRecompiler.java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.ras.api.ActiveSituation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class CbrSituationRecompiler implements SituationRecompiler {

    private static final Logger LOG = Logger.getLogger(CbrSituationRecompiler.class.getName());

    private final ConfigurationRetriever retriever;
    private final ConfigurationAdapter adapter;
    private final PreferenceProvider preferenceProvider;

    public CbrSituationRecompiler(ConfigurationRetriever retriever,
                                   ConfigurationAdapter adapter,
                                   PreferenceProvider preferenceProvider) {
        this.retriever = retriever;
        this.adapter = adapter;
        this.preferenceProvider = preferenceProvider;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Optional<CompilationResult> recompile(
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

    private CbrConfiguration resolveConfiguration() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.root());
        double minRetrieval = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MIN_RETRIEVAL_CONFIDENCE).value();
        double minAdaptation = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MIN_ADAPTATION_CONFIDENCE).value();
        int maxCandidates = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MAX_CANDIDATES).value();
        return new CbrConfiguration(minRetrieval, minAdaptation, maxCandidates);
    }
}
```

- [ ] **Step 10: Run CbrSituationRecompilerTest**

Run: `mvn --batch-mode test -pl runtime -Dtest=CbrSituationRecompilerTest`
Expected: PASS

- [ ] **Step 11: Run full build**

Run: `mvn --batch-mode install -DskipTests=false`
Expected: BUILD SUCCESS

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat(#23): CbrFaultPolicy + CbrSituationRecompiler — CBR chain with per-call preference resolution, NoOp defaults, preference keys"
```

---

### Task 6: Testing Module Mocks + Deferred Issue Filing

Add programmable mock implementations to `testing/` for domain test use.
File the CBR Revise step issue and the DoublePreference/IntPreference promotion issue.

**Files:**
- Create: `testing/src/main/java/io/casehub/desiredstate/testing/MockConfigurationRetriever.java`
- Create: `testing/src/main/java/io/casehub/desiredstate/testing/MockConfigurationAdapter.java`

**Interfaces:**
- Consumes: `ConfigurationRetriever`, `ConfigurationAdapter`, `RetrievalContext`, `RetrievedConfiguration`, `AdaptedConfiguration` (Task 1)
- Produces: Reusable test doubles for domain modules implementing CBR

- [ ] **Step 1: Implement MockConfigurationRetriever**

```java
// testing/src/main/java/io/casehub/desiredstate/testing/MockConfigurationRetriever.java
package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;

import java.util.ArrayList;
import java.util.List;

public class MockConfigurationRetriever implements ConfigurationRetriever {

    private List<RetrievedConfiguration> results = List.of();
    private final List<RetrievalContext> receivedContexts = new ArrayList<>();

    @Override
    public List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults) {
        receivedContexts.add(context);
        return results.size() > maxResults ? results.subList(0, maxResults) : results;
    }

    public void setResults(List<RetrievedConfiguration> results) {
        this.results = List.copyOf(results);
    }

    public List<RetrievalContext> receivedContexts() {
        return List.copyOf(receivedContexts);
    }

    public void clear() {
        results = List.of();
        receivedContexts.clear();
    }
}
```

- [ ] **Step 2: Implement MockConfigurationAdapter**

```java
// testing/src/main/java/io/casehub/desiredstate/testing/MockConfigurationAdapter.java
package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;

import java.util.*;

public class MockConfigurationAdapter implements ConfigurationAdapter {

    private AdaptedConfiguration defaultResult;
    private final Map<String, AdaptedConfiguration> resultsBySourceId = new HashMap<>();
    private final List<RetrievedConfiguration> receivedConfigurations = new ArrayList<>();

    @Override
    public Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context) {
        receivedConfigurations.add(retrieved);
        AdaptedConfiguration specific = resultsBySourceId.get(retrieved.sourceId());
        if (specific != null) return Optional.of(specific);
        return Optional.ofNullable(defaultResult);
    }

    public void setDefaultResult(AdaptedConfiguration result) {
        this.defaultResult = result;
    }

    public void setResultForSource(String sourceId, AdaptedConfiguration result) {
        resultsBySourceId.put(sourceId, result);
    }

    public List<RetrievedConfiguration> receivedConfigurations() {
        return List.copyOf(receivedConfigurations);
    }

    public void clear() {
        defaultResult = null;
        resultsBySourceId.clear();
        receivedConfigurations.clear();
    }
}
```

- [ ] **Step 3: Implement NodeSpecValueSemanticsTest**

```java
// testing/src/test/java/io/casehub/desiredstate/testing/NodeSpecValueSemanticsTest.java
package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.NodeSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NodeSpecValueSemanticsTest {

    private record SpecA(String x, int y) implements NodeSpec {}
    private record SpecB(double value) implements NodeSpec {}

    @Test
    void recordNodeSpecs_shouldHaveValueEquality() {
        assertThat(new SpecA("hello", 42)).isEqualTo(new SpecA("hello", 42));
        assertThat(new SpecA("hello", 42).hashCode()).isEqualTo(new SpecA("hello", 42).hashCode());
    }

    @Test
    void differentFieldValues_shouldNotBeEqual() {
        assertThat(new SpecA("hello", 42)).isNotEqualTo(new SpecA("hello", 99));
    }

    @Test
    void differentSpecTypes_shouldNotBeEqual() {
        // Even if structurally similar, different types are not equal
        assertThat((NodeSpec) new SpecA("x", 1)).isNotEqualTo(new SpecB(1.0));
    }
}
```

- [ ] **Step 4: Run testing module build**

Run: `mvn --batch-mode install -pl testing`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add testing/src/main/java/io/casehub/desiredstate/testing/MockConfigurationRetriever.java testing/src/main/java/io/casehub/desiredstate/testing/MockConfigurationAdapter.java testing/src/test/java/io/casehub/desiredstate/testing/NodeSpecValueSemanticsTest.java
git commit -m "feat(#23): testing module — MockConfigurationRetriever, MockConfigurationAdapter, NodeSpecValueSemanticsTest"
```

- [ ] **Step 6: File deferred issues**

Create GitHub issue for CBR Revise step:
```bash
gh issue create --repo casehubio/casehub-desiredstate --title "feat: CBR Revise step — outcome feedback loop to case store" --body "When CBR proposes a configuration (via CbrFaultPolicy or CbrSituationRecompiler), the reconciliation outcome should be emitted as an event so downstream consumers (casehub-ledger, CaseMemoryStore) can update the case store.

Without this, CBR recommendations are static — quality depends entirely on the initial case corpus.

See §Deferred: CBR Revise Step in docs/specs/2026-07-10-cbr-integration-design.md.

Linked to #23." --label "scale: M,complexity: High"
```

Create GitHub issue for DoublePreference/IntPreference promotion:
```bash
gh issue create --repo casehubio/casehub-desiredstate --title "chore: promote DoublePreference/IntPreference to casehub-platform-api" --body "DoublePreference and IntPreference currently live in io.casehub.api.spi.routing (casehub-engine-api) and are duplicated locally in desiredstate api/.

They should be promoted to io.casehub.platform.api.preferences (casehub-platform-api) alongside DurationPreference, as all SingleValuePreference implementations belong together.

Requires coordinated change: platform-api, engine-api, desiredstate.

See §Preference Integration in docs/specs/2026-07-10-cbr-integration-design.md." --label "scale: S,complexity: Low"
```

- [ ] **Step 7: Update CLAUDE.md with new types**

Add `CbrFaultPolicy`, `CbrSituationRecompiler`, `SituationRecompilerEngine`, `GraphDiff`, `ConfigurationRetriever`, `ConfigurationAdapter`, and related types to the Core SPIs and Core Runtime Types tables.

- [ ] **Step 8: Final full build**

Run: `mvn --batch-mode install -DskipTests=false`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(#23): update CLAUDE.md with CBR types and SPIs"
```
