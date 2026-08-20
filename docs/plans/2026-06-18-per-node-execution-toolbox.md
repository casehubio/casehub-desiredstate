# Per-Node Execution Toolbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `PipelineProvisioner` to delegate processing stage execution to pluggable `ExecutionBackend` implementations, while keeping metadata and review handling direct.

**Architecture:** Strategy pattern on the provisioner. An `ExecutionBackend` SPI handles processing stage provisioning/deprovisioning. The provisioner dispatches DATA_SOURCE, SCHEMA, AI_REVIEW, HUMAN_REVIEW directly, and delegates INGESTION through SINK to backends. `DefaultExecutionBackend` extracts the existing processing stage logic. Fail-fast `AmbiguousBackendException` when multiple backends match.

**Tech Stack:** Java 21, plain JUnit 5, AssertJ, casehub-desiredstate-api

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `examples/pipeline/src/main/java/.../ExecutionBackend.java` | SPI interface — `handles`, `provision`, `deprovision` |
| Create | `examples/pipeline/src/main/java/.../AmbiguousBackendException.java` | Thrown when multiple backends match a node |
| Create | `examples/pipeline/src/main/java/.../DefaultExecutionBackend.java` | Handles all 6 processing stage types |
| Modify | `examples/pipeline/src/main/java/.../PipelineProvisioner.java` | Refactor to hybrid: direct + backend dispatch |
| Create | `examples/pipeline/src/test/java/.../ExecutionBackendTest.java` | Tests for backend dispatch, ambiguity, isolation |
| Modify | `examples/pipeline/src/test/java/.../PipelineTest.java` | Update setUp to construct backends |

All paths under `io/casehub/desiredstate/example/pipeline/`.

---

### Task 1: ExecutionBackend SPI and AmbiguousBackendException

**Files:**
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/ExecutionBackend.java`
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/AmbiguousBackendException.java`

- [ ] **Step 1: Create ExecutionBackend interface**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;

public interface ExecutionBackend {
    boolean handles(DesiredNode node);
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}
```

- [ ] **Step 2: Create AmbiguousBackendException**

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.DesiredNode;

import java.util.List;
import java.util.stream.Collectors;

public class AmbiguousBackendException extends RuntimeException {

    private final DesiredNode node;
    private final List<ExecutionBackend> matchingBackends;

    public AmbiguousBackendException(DesiredNode node, List<ExecutionBackend> matchingBackends) {
        super("Multiple execution backends match node " + node.id().value()
                + " (type: " + node.type().value() + "): "
                + matchingBackends.stream()
                    .map(b -> b.getClass().getSimpleName())
                    .collect(Collectors.joining(", ")));
        this.node = node;
        this.matchingBackends = List.copyOf(matchingBackends);
    }

    public DesiredNode node() {
        return node;
    }

    public List<ExecutionBackend> matchingBackends() {
        return matchingBackends;
    }
}
```

- [ ] **Step 3: Compile**

Run: `/opt/homebrew/bin/mvn --batch-mode compile -pl examples/pipeline -am`

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```
git add examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/ExecutionBackend.java examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/AmbiguousBackendException.java
git commit -m "feat(#28): ExecutionBackend SPI and AmbiguousBackendException"
```

---

### Task 2: DefaultExecutionBackend — extract processing stage logic

**Files:**
- Create: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/DefaultExecutionBackend.java`
- Create: `examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/ExecutionBackendTest.java`

- [ ] **Step 1: Write failing test — backend handles processing types, rejects others**

Create `ExecutionBackendTest.java`:

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionBackendTest {

    private PipelineWorld world;
    private DefaultExecutionBackend backend;

    @BeforeEach
    void setUp() {
        world = new PipelineWorld();
        backend = new DefaultExecutionBackend(world);
    }

    @Test
    void handles_processingTypesOnly() {
        assertThat(backend.handles(node("src", PipelineNodeTypes.DATA_SOURCE,
                new DataSourceSpec("src", "json", "kafka://x")))).isFalse();
        assertThat(backend.handles(node("sch", PipelineNodeTypes.SCHEMA,
                new SchemaSpec("sch", List.of("a"), 1)))).isFalse();
        assertThat(backend.handles(node("ai", PipelineNodeTypes.AI_REVIEW,
                new AiReviewSpec(NodeId.of("x"), "err")))).isFalse();
        assertThat(backend.handles(node("hr", PipelineNodeTypes.HUMAN_REVIEW,
                new HumanReviewSpec(NodeId.of("x"), "err")))).isFalse();

        assertThat(backend.handles(node("ing", PipelineNodeTypes.INGESTION,
                new IngestionSpec("src", 1000, "json")))).isTrue();
        assertThat(backend.handles(node("cl", PipelineNodeTypes.CLEANSER,
                new CleanserSpec(List.of("dedupe"), true, "DROP")))).isTrue();
        assertThat(backend.handles(node("en", PipelineNodeTypes.ENRICHER,
                new EnricherSpec("geo", List.of("id"), List.of("country"))))).isTrue();
        assertThat(backend.handles(node("val", PipelineNodeTypes.VALIDATOR,
                new ValidatorSpec("sch", 0.95, false)))).isTrue();
        assertThat(backend.handles(node("tx", PipelineNodeTypes.TRANSFORMER,
                new TransformerSpec(List.of("agg"), List.of("reshape"), "parquet")))).isTrue();
        assertThat(backend.handles(node("sk", PipelineNodeTypes.SINK,
                new SinkSpec("s3://out", "parquet", List.of("date"))))).isTrue();
    }

    private DesiredNode node(String id, NodeType type, NodeSpec spec) {
        return new DesiredNode(NodeId.of(id), spec, false);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest#handles_processingTypesOnly`

Expected: FAIL — `DefaultExecutionBackend` does not exist

- [ ] **Step 3: Implement DefaultExecutionBackend**

Create `DefaultExecutionBackend.java`. This is a mechanical extraction of the 6 processing stage branches from `PipelineProvisioner.provision()` (lines 44–109) and the processing stage deprovision logic (the final else-branch at line 164).

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.Set;

public class DefaultExecutionBackend implements ExecutionBackend {

    private static final Set<NodeType> PROCESSING_TYPES = Set.of(
            PipelineNodeTypes.INGESTION, PipelineNodeTypes.CLEANSER,
            PipelineNodeTypes.ENRICHER, PipelineNodeTypes.VALIDATOR,
            PipelineNodeTypes.TRANSFORMER, PipelineNodeTypes.SINK
    );

    private final PipelineWorld world;

    public DefaultExecutionBackend(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public boolean handles(DesiredNode node) {
        return PROCESSING_TYPES.contains(node.type());
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

        if (type.equals(PipelineNodeTypes.INGESTION)) {
            IngestionSpec spec = (IngestionSpec) node.spec();
            if (!world.hasSource(NodeId.of(spec.sourceRef()))) {
                return new ProvisionResult.Failed(
                        "Data source not found: " + spec.sourceRef());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.CLEANSER)) {
            Set<NodeId> deps = context.graph().dependenciesOf(node.id());
            boolean hasUpstream = deps.stream().anyMatch(dep -> {
                DesiredNode depNode = context.graph().nodes().get(dep);
                return depNode != null && PipelineNodeTypes.INGESTION.equals(depNode.type());
            });
            if (!hasUpstream) {
                return new ProvisionResult.Failed("No upstream ingestion stage found");
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.ENRICHER)) {
            EnricherSpec spec = (EnricherSpec) node.spec();
            if (!world.hasLookupSource(spec.lookupSource())) {
                return new ProvisionResult.Failed(
                        "Lookup source not found: " + spec.lookupSource());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.VALIDATOR)) {
            ValidatorSpec spec = (ValidatorSpec) node.spec();
            if (!world.hasSchema(spec.schemaRef())) {
                return new ProvisionResult.Failed(
                        "Schema not found: " + spec.schemaRef());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.TRANSFORMER)) {
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.SINK)) {
            world.setStage(node.id(), runningStage(type));
            return new ProvisionResult.Success();
        }

        return new ProvisionResult.Failed("Unhandled processing type: " + type.value());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        world.removeStage(node.id());
        return new DeprovisionResult.Success();
    }

    private void registerDownstream(NodeId nodeId, DesiredStateGraph graph) {
        Set<NodeId> dependents = graph.dependentsOf(nodeId);
        for (NodeId dependent : dependents) {
            world.registerDownstream(nodeId, dependent);
        }
    }

    private PipelineWorld.StageEntry runningStage(NodeType nodeType) {
        return new PipelineWorld.StageEntry(
                nodeType, PipelineWorld.StageState.RUNNING, null, null, 0, 0, 0, null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest#handles_processingTypesOnly`

Expected: PASS

- [ ] **Step 5: Write test — ingestion provision validates source exists**

Add to `ExecutionBackendTest.java`:

```java
@Test
void ingestion_failsWhenSourceMissing() {
    DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
            new IngestionSpec("missing-source", 1000, "json"));
    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(ingestion), List.of());

    ProvisionResult result = backend.provision(ingestion, new ProvisionContext("test", graph));

    assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    assertThat(((ProvisionResult.Failed) result).reason()).contains("missing-source");
}

@Test
void ingestion_succeedsWhenSourcePresent() {
    world.registerSource(NodeId.of("src"),
            new PipelineWorld.DataSourceEntry("src", "json", "kafka://x"));
    DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
            new IngestionSpec("src", 1000, "json"));
    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(ingestion), List.of());

    ProvisionResult result = backend.provision(ingestion, new ProvisionContext("test", graph));

    assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    assertThat(world.stageState(NodeId.of("ingest")))
            .isEqualTo(PipelineWorld.StageState.RUNNING);
}
```

Add import: `import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;`

- [ ] **Step 6: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest`

Expected: PASS — 3 tests

- [ ] **Step 7: Write test — deprovision removes stage**

Add to `ExecutionBackendTest.java`:

```java
@Test
void deprovision_removesStage() {
    world.setStage(NodeId.of("tx"),
            new PipelineWorld.StageEntry(PipelineNodeTypes.TRANSFORMER,
                    PipelineWorld.StageState.RUNNING, null, null, 0, 0, 0, null));
    DesiredNode transformer = node("tx", PipelineNodeTypes.TRANSFORMER,
            new TransformerSpec(List.of("agg"), List.of("reshape"), "parquet"));
    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(transformer), List.of());

    DeprovisionResult result = backend.deprovision(transformer,
            new DeprovisionContext("test", graph));

    assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    assertThat(world.stageState(NodeId.of("tx"))).isNull();
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest`

Expected: PASS — 4 tests

- [ ] **Step 9: Commit**

```
git add examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/DefaultExecutionBackend.java examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/ExecutionBackendTest.java
git commit -m "feat(#28): DefaultExecutionBackend — extract processing stage logic"
```

---

### Task 3: Refactor PipelineProvisioner to hybrid dispatch

**Files:**
- Modify: `examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineProvisioner.java`
- Modify: `examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/PipelineTest.java`

- [ ] **Step 1: Write failing test — provisioner delegates processing stages to backend**

Add to `ExecutionBackendTest.java`:

```java
@Test
void provisioner_delegatesProcessingStagesToBackend() {
    world.registerSource(NodeId.of("src"),
            new PipelineWorld.DataSourceEntry("src", "json", "kafka://x"));
    DesiredNode source = node("src", PipelineNodeTypes.DATA_SOURCE,
            new DataSourceSpec("src", "json", "kafka://x"));
    DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
            new IngestionSpec("src", 1000, "json"));
    DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();
    DesiredStateGraph graph = graphFactory.of(
            List.of(source, ingestion),
            List.of(new Dependency(NodeId.of("ingest"), NodeId.of("src"))));

    PipelineProvisioner provisioner = new PipelineProvisioner(
            world, new NoOpAgentProvider(), List.of(backend));

    ProvisionResult result = provisioner.provision(ingestion, new ProvisionContext("test", graph));
    assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    assertThat(world.stageState(NodeId.of("ingest")))
            .isEqualTo(PipelineWorld.StageState.RUNNING);
}
```

Add import: `import io.casehub.platform.agent.NoOpAgentProvider;`

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest#provisioner_delegatesProcessingStagesToBackend`

Expected: FAIL — `PipelineProvisioner` constructor doesn't accept `List<ExecutionBackend>`

- [ ] **Step 3: Refactor PipelineProvisioner**

Replace the entire `PipelineProvisioner.java` with the hybrid dispatch version. The processing stage branches (INGESTION through SINK) are removed — they now live in `DefaultExecutionBackend`. The metadata (DATA_SOURCE, SCHEMA) and review (AI_REVIEW, HUMAN_REVIEW) handling is extracted to private methods. The `registerDownstream` and `runningStage` helper methods are removed (they moved to `DefaultExecutionBackend`).

```java
package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class PipelineProvisioner implements NodeProvisioner {

    private final PipelineWorld world;
    private final AgentProvider agentProvider;
    private final List<ExecutionBackend> backends;

    public PipelineProvisioner(PipelineWorld world, AgentProvider agentProvider,
                               List<ExecutionBackend> backends) {
        this.world = world;
        this.agentProvider = agentProvider;
        this.backends = List.copyOf(backends);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

        if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
            return provisionDataSource(node);
        }
        if (type.equals(PipelineNodeTypes.SCHEMA)) {
            return provisionSchema(node);
        }
        if (type.equals(PipelineNodeTypes.AI_REVIEW)) {
            return provisionAiReview(node, context);
        }
        if (type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
            return provisionHumanReview(node);
        }

        return dispatchToBackend(node, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        NodeType type = node.type();

        if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
            world.removeSource(node.id());
            return new DeprovisionResult.Success();
        }
        if (type.equals(PipelineNodeTypes.SCHEMA)) {
            SchemaSpec spec = (SchemaSpec) node.spec();
            world.removeSchema(spec.name());
            return new DeprovisionResult.Success();
        }
        if (type.equals(PipelineNodeTypes.AI_REVIEW) || type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
            world.removeReview(node.id());
            return new DeprovisionResult.Success();
        }

        return dispatchToBackendDeprovision(node, context);
    }

    private ProvisionResult dispatchToBackend(DesiredNode node, ProvisionContext context) {
        List<ExecutionBackend> matching = backends.stream()
                .filter(b -> b.handles(node))
                .toList();
        if (matching.size() > 1) {
            throw new AmbiguousBackendException(node, matching);
        }
        if (matching.isEmpty()) {
            return new ProvisionResult.Failed(
                    "No execution backend for: " + node.id().value()
                            + " (type: " + node.type().value() + ")");
        }
        return matching.get(0).provision(node, context);
    }

    private DeprovisionResult dispatchToBackendDeprovision(DesiredNode node,
                                                           DeprovisionContext context) {
        List<ExecutionBackend> matching = backends.stream()
                .filter(b -> b.handles(node))
                .toList();
        if (matching.size() > 1) {
            throw new AmbiguousBackendException(node, matching);
        }
        if (matching.isEmpty()) {
            return new DeprovisionResult.Failed(
                    "No execution backend for: " + node.id().value()
                            + " (type: " + node.type().value() + ")");
        }
        return matching.get(0).deprovision(node, context);
    }

    private ProvisionResult provisionDataSource(DesiredNode node) {
        DataSourceSpec spec = (DataSourceSpec) node.spec();
        world.registerSource(node.id(),
                new PipelineWorld.DataSourceEntry(spec.name(), spec.format(), spec.uri()));
        return new ProvisionResult.Success();
    }

    private ProvisionResult provisionSchema(DesiredNode node) {
        SchemaSpec spec = (SchemaSpec) node.spec();
        world.registerSchema(spec.name(),
                new PipelineWorld.SchemaDefinition(spec.name(), spec.fields(), spec.version()));
        return new ProvisionResult.Success();
    }

    private ProvisionResult provisionAiReview(DesiredNode node, ProvisionContext context) {
        AiReviewSpec spec = (AiReviewSpec) node.spec();
        NodeId target = spec.targetNodeId();

        PipelineWorld.ReviewEntry existing = world.review(node.id());
        if (existing != null) {
            if (existing.state() == PipelineWorld.ReviewState.RESOLVED) {
                world.clearStageError(target);
            }
            return new ProvisionResult.Success();
        }

        String diagnosis = agentProvider.invoke(AgentSessionConfig.of(
                "You are a data pipeline fault diagnostic agent. Analyze the error and determine if you can resolve it. Respond with RESOLVED if the issue can be fixed automatically, or UNRESOLVED if human intervention is needed.",
                "Node " + target.value() + " failed with: " + spec.errorDetail(),
                Duration.ofSeconds(30)
        )).filter(AgentEvent.TextDelta.class::isInstance)
                .map(AgentEvent.TextDelta.class::cast)
                .map(AgentEvent.TextDelta::text)
                .collect().asList()
                .onItem().transform(texts -> String.join("", texts))
                .await().atMost(Duration.ofSeconds(30));

        if (diagnosis.isEmpty()) {
            world.addReview(node.id(), target);
            return new ProvisionResult.Success();
        }

        String upper = diagnosis.toUpperCase(Locale.ROOT);
        boolean resolved = upper.contains("RESOLVED") && !upper.contains("UNRESOLVED");
        world.addReview(node.id(), target);
        world.setAiReviewOutcome(target, resolved);
        return new ProvisionResult.Success();
    }

    private ProvisionResult provisionHumanReview(DesiredNode node) {
        HumanReviewSpec spec = (HumanReviewSpec) node.spec();
        world.addReview(node.id(), spec.targetNodeId());
        return new ProvisionResult.Success();
    }
}
```

- [ ] **Step 4: Run the new test to verify it passes**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest#provisioner_delegatesProcessingStagesToBackend`

Expected: PASS

- [ ] **Step 5: Update PipelineTest setUp**

In `PipelineTest.java`, change the `setUp()` method to construct the backend and pass it to the provisioner. Change the field type from `PipelineProvisioner` to `NodeProvisioner` (the SPI type — tests should program to the interface):

Replace lines 31 and 40 in the current file:

Old:
```java
    private PipelineProvisioner provisioner;
```
New:
```java
    private NodeProvisioner provisioner;
```

Old:
```java
        provisioner = new PipelineProvisioner(world, new NoOpAgentProvider());
```
New:
```java
        var defaultBackend = new DefaultExecutionBackend(world);
        provisioner = new PipelineProvisioner(world, new NoOpAgentProvider(), List.of(defaultBackend));
```

Also update the three `aiReview_invokes*` tests that create their own `PipelineProvisioner` instances. Each currently reads:
```java
        PipelineProvisioner agentProvisioner = new PipelineProvisioner(world, resolvingAgent);
```
Change to:
```java
        var agentDefaultBackend = new DefaultExecutionBackend(world);
        PipelineProvisioner agentProvisioner = new PipelineProvisioner(world, resolvingAgent, List.of(agentDefaultBackend));
```

And the `aiReview_noOpAgent_registersPending` test:
```java
        PipelineProvisioner noOpProvisioner = new PipelineProvisioner(world, new NoOpAgentProvider());
```
Change to:
```java
        var noOpDefaultBackend = new DefaultExecutionBackend(world);
        PipelineProvisioner noOpProvisioner = new PipelineProvisioner(world, new NoOpAgentProvider(), List.of(noOpDefaultBackend));
```

- [ ] **Step 6: Run the full existing test suite**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=PipelineTest`

Expected: PASS — all 23 tests pass with identical behaviour. This validates the refactoring is behaviour-preserving.

- [ ] **Step 7: Commit**

```
git add examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineProvisioner.java examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/PipelineTest.java examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/ExecutionBackendTest.java
git commit -m "feat(#28): refactor PipelineProvisioner to hybrid dispatch via ExecutionBackend"
```

---

### Task 4: Dispatch edge-case tests — missing backend, ambiguous backend, direct handling

**Files:**
- Modify: `examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/ExecutionBackendTest.java`

- [ ] **Step 1: Write test — missing backend returns Failed**

Add to `ExecutionBackendTest.java`:

```java
@Test
void provisioner_noBackend_returnsFailed() {
    PipelineProvisioner provisioner = new PipelineProvisioner(
            world, new NoOpAgentProvider(), List.of());

    DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
            new IngestionSpec("src", 1000, "json"));
    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(ingestion), List.of());

    ProvisionResult result = provisioner.provision(ingestion, new ProvisionContext("test", graph));

    assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    assertThat(((ProvisionResult.Failed) result).reason()).contains("No execution backend");
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest#provisioner_noBackend_returnsFailed`

Expected: PASS (already implemented in dispatchToBackend)

- [ ] **Step 3: Write test — ambiguous backend throws exception**

Add to `ExecutionBackendTest.java`:

```java
@Test
void provisioner_ambiguousBackends_throws() {
    ExecutionBackend backend1 = new DefaultExecutionBackend(world);
    ExecutionBackend backend2 = new DefaultExecutionBackend(world);
    PipelineProvisioner provisioner = new PipelineProvisioner(
            world, new NoOpAgentProvider(), List.of(backend1, backend2));

    DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
            new IngestionSpec("src", 1000, "json"));
    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(ingestion), List.of());

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            provisioner.provision(ingestion, new ProvisionContext("test", graph)))
        .isInstanceOf(AmbiguousBackendException.class)
        .hasMessageContaining("Multiple execution backends")
        .hasMessageContaining("ingest");
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest#provisioner_ambiguousBackends_throws`

Expected: PASS

- [ ] **Step 5: Write test — DATA_SOURCE handled directly (not via backend)**

Add to `ExecutionBackendTest.java`:

```java
@Test
void provisioner_dataSource_handledDirectly() {
    PipelineProvisioner provisioner = new PipelineProvisioner(
            world, new NoOpAgentProvider(), List.of());

    DesiredNode source = node("src", PipelineNodeTypes.DATA_SOURCE,
            new DataSourceSpec("src", "json", "kafka://x"));
    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(source), List.of());

    ProvisionResult result = provisioner.provision(source, new ProvisionContext("test", graph));

    assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    assertThat(world.hasSource(NodeId.of("src"))).isTrue();
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest#provisioner_dataSource_handledDirectly`

Expected: PASS — DATA_SOURCE is handled directly even with no backends registered

- [ ] **Step 7: Write test — deprovision ambiguity guard**

Add to `ExecutionBackendTest.java`:

```java
@Test
void provisioner_deprovisionAmbiguous_throws() {
    ExecutionBackend backend1 = new DefaultExecutionBackend(world);
    ExecutionBackend backend2 = new DefaultExecutionBackend(world);
    PipelineProvisioner provisioner = new PipelineProvisioner(
            world, new NoOpAgentProvider(), List.of(backend1, backend2));

    DesiredNode transformer = node("tx", PipelineNodeTypes.TRANSFORMER,
            new TransformerSpec(List.of("agg"), List.of("reshape"), "parquet"));
    DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(transformer), List.of());

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            provisioner.deprovision(transformer, new DeprovisionContext("test", graph)))
        .isInstanceOf(AmbiguousBackendException.class);
}
```

- [ ] **Step 8: Run full ExecutionBackendTest**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline -Dtest=ExecutionBackendTest`

Expected: PASS — 8 tests

- [ ] **Step 9: Commit**

```
git add examples/pipeline/src/test/java/io/casehub/desiredstate/example/pipeline/ExecutionBackendTest.java
git commit -m "test(#28): dispatch edge cases — missing backend, ambiguous, direct handling"
```

---

### Task 5: Full build verification

**Files:** None — verification only

- [ ] **Step 1: Run the full project build**

Run: `/opt/homebrew/bin/mvn --batch-mode install`

Expected: BUILD SUCCESS — all modules compile, all tests pass (existing 23 in PipelineTest + 8 new in ExecutionBackendTest + all other module tests)

- [ ] **Step 2: Verify test counts**

Run: `/opt/homebrew/bin/mvn --batch-mode test -pl examples/pipeline`

Expected: `Tests run: 31` (23 PipelineTest + 8 ExecutionBackendTest)

- [ ] **Step 3: Commit if any formatting/cleanup was needed**

If the build required any fixes, commit them:
```
git commit -m "fix(#28): build verification fixes"
```

Otherwise, skip this step.

---
