# Data Pipeline Example — Design Spec

**Date:** 2026-06-15
**Epic:** #2 — Toy domain (validates generic runtime)
**Status:** Design
**Repo:** casehubio/casehub-desiredstate

---

## 1. Overview

A data pipeline domain implementing all casehub-desiredstate SPIs. Validates that the generic runtime's SPI contracts are truly domain-agnostic by building a structurally different domain from the Nefarious Dungeons example.

The domain models a data processing pipeline with medallion architecture (Bronze/Silver/Gold layers), typed stage semantics, schema compatibility validation, and a three-tier fault escalation cascade (auto-retry → AI review → human WorkItem).

Strategic context: positions CaseHub for AiFusion by demonstrating desired-state management applied to data processing, cleansing, enrichment, and validation — core capabilities in the AI/data domain.

---

## 2. Module Structure

```
examples/
  pipeline/    casehub-desiredstate-example-pipeline    io.casehub.desiredstate.example.pipeline
```

**Dependencies** (same footprint as dungeon):
- `casehub-desiredstate-api`
- `casehub-desiredstate` (runtime)
- `casehub-platform-api`
- `quarkus-arc`
- `quarkus-vertx`
- `quarkus-rest` (visualizer endpoint)
- Test: `quarkus-junit`, `assertj-core`, `casehub-platform`, `casehub-platform-testing`

No external data processing libraries. Pure in-memory simulation.

---

## 3. Medallion Architecture

Stages are organized into three quality tiers. The `GoalCompiler` assigns layers based on node type. The dependency graph's topological ordering naturally produces layer-correct provisioning (Bronze before Silver before Gold). This is an emergent property of the graph structure, not a planner-level enforcement — the planner knows nothing about medallion layers.

```java
public enum PipelineLayer {
    BRONZE,   // raw ingestion — preserve fidelity
    SILVER,   // cleaned, enriched, validated — enterprise view
    GOLD      // transformed, business-ready — consumption layer
}
```

---

## 4. Node Types

### 4.1 Blueprint Node Types (8)

Declared in `PipelineBlueprint`, compiled into the graph by `PipelineGoalCompiler`.

| Node Type | Layer | NodeSpec | Purpose |
|-----------|-------|---------|---------|
| `DATA_SOURCE` | Bronze | `DataSourceSpec(name, format, uri)` | Origin of raw data |
| `SCHEMA` | Bronze | `SchemaSpec(name, fields, version)` | Validation contract / schema registry entry |
| `INGESTION` | Bronze | `IngestionSpec(sourceRef, batchSize, format)` | Extract from source |
| `CLEANSER` | Silver | `CleanserSpec(rules, deduplication, nullHandling)` | Dedup, normalize, fix formats |
| `ENRICHER` | Silver | `EnricherSpec(lookupSource, joinKeys, enrichFields)` | Augment with external reference data |
| `VALIDATOR` | Silver | `ValidatorSpec(schemaRef, qualityThreshold, anomalyDetection)` | Quality gate against schema |
| `TRANSFORMER` | Gold | `TransformerSpec(aggregations, reshapeRules, outputFormat)` | Reshape and aggregate for consumption |
| `SINK` | Gold | `SinkSpec(destination, format, partitionKeys)` | Final delivery |

### 4.2 Fault-Generated Node Types (2)

Created by fault policies only — never declared in a blueprint. Node IDs are deterministic: `ai-review-{targetNodeId}` and `human-review-{targetNodeId}`. This provides inherent idempotency — `AddNode` with the same ID replaces the existing node rather than creating a duplicate.

| Node Type | requiresHuman | Purpose |
|-----------|--------------|---------|
| `AI_REVIEW` | false | LLM diagnostic — attempts automated resolution of persistent faults |
| `HUMAN_REVIEW` | true | Human escalation — WorkItem for manual resolution |

Node type constants live in `PipelineNodeTypes` (same pattern as `DungeonNodeTypes`).

---

## 5. Dependency Graph

Uses `Dependency(from, to)` convention: "from depends on to" — arrows point from dependent to dependency.

```
BRONZE:  ingestion ──→ datasource       (ingestion depends on datasource)
         datasource (root)
         schema (root)

SILVER:  cleanser ──→ ingestion         (cleanser depends on ingestion)
         cleanser ──→ schema            (cleanser depends on schema)
         enricher ──→ cleanser          (enricher depends on cleanser)
         validator ──→ enricher         (validator depends on enricher)
         validator ──→ schema           (validator depends on schema)

GOLD:    transformer ──→ validator      (transformer depends on validator)
         sink ──→ transformer           (sink depends on transformer)
```

Two independent roots (datasource, schema) — creates wider graph with parallelism opportunities for the planner. Three fan-in points:
- **Cleanser** depends on both Schema (knows what clean data looks like) and Ingestion (provides raw data)
- **Validator** depends on both Enricher output (data to validate) and Schema (contract to validate against)

The planner produces additions in topological order (roots first), which naturally respects layer boundaries: all Bronze nodes before Silver, all Silver before Gold. Note: the planner collects orphaned removals without topological ordering — orphaned nodes have lost their dependency context (they're not in the desired graph), so topological sorting is impossible.

**Design choice:** Dependencies are inferred by the `GoalCompiler` from the canonical pipeline topology — the pipeline shape IS the dependency graph. Unlike the dungeon blueprint (which has explicit `roomDeps` parameters), the pipeline blueprint does not accept explicit dependency parameters. This is deliberate: the topology is fixed by node type relationships, and having the user also specify deps would be redundant and error-prone.

---

## 6. Pipeline World (Simulation)

`PipelineWorld` — mutable in-memory simulation, `@ApplicationScoped`. Equivalent to `DungeonWorld`.

### 6.1 Stage States

```java
public enum StageState {
    IDLE,           // provisioned but not running
    RUNNING,        // actively processing
    COMPLETED,      // batch finished successfully
    FAILED,         // processing error
    QUARANTINED,    // validation failure — bad records isolated
    DEGRADED        // running but with quality/throughput issues
}
```

### 6.2 Per-Stage Tracking

Each provisioned stage stores:
- Current `StageState`
- Input/output schema references (for compatibility checking)
- Record counts: processed, failed, quarantined (simple `long` counters)
- Error detail string (populated when FAILED or QUARANTINED)

Stored in `ConcurrentHashMap<NodeId, StageEntry>` keyed by node ID.

### 6.3 Schema Registry

Internal `Map<String, SchemaDefinition>` where `SchemaDefinition` holds field names and types. Used during provisioning to validate schema compatibility between connected stages.

### 6.4 Lookup Source Registry

Internal `Map<String, LookupSourceEntry>` for enricher lookup sources (e.g., "geo-lookup"). The enricher provisioner validates that its declared lookup source is registered before setting the stage to RUNNING.

### 6.5 Review Registry

`ConcurrentHashMap<NodeId, ReviewEntry>` tracking fault-generated review nodes:

```java
record ReviewEntry(NodeId targetNode, ReviewState state) {}
enum ReviewState { PENDING, RESOLVED, UNRESOLVED }
```

- `PipelineProvisioner` stores AI_REVIEW outcomes here during provisioning
- `ProvisionEscalationFaultPolicy` reads review state to decide escalation level
- `PipelineActualStateAdapter` maps: RESOLVED → PRESENT, PENDING/UNRESOLVED → ABSENT
- Tests call `world.setAiReviewOutcome(nodeId, resolved)` for deterministic AI_REVIEW control
- Tests call `world.resolveHumanReview(nodeId)` to simulate human approval

### 6.6 Key Behaviors

- **Provisioning a stage** validates that upstream stages exist and schema is compatible. Incompatible schemas → `ProvisionResult.Failed` with a meaningful reason.
- **Deprovisioning a stage** cascades downstream stages to `IDLE` (they lose their input).
- **Schema version changes** mark dependent stages as `DEGRADED` (schema drift detection).
- **Provisioning a SINK** validates the full upstream chain is complete and schema-compatible end-to-end.

### 6.7 Resolution Methods

- **`approveSchemaChange(schemaName, newVersion)`** — updates the schema version in the registry, walks all stages that reference this schema, sets each to RUNNING (clears DEGRADED), and updates their schema references to the new version. Tests call this to simulate human approval of schema drift.
- **`clearStageError(nodeId)`** — clears the FAILED state on a stage, allowing the next provision attempt to succeed. Tests call this after an AI_REVIEW resolves to RESOLVED, simulating the LLM having fixed the root cause.
- **`setAiReviewOutcome(targetNodeId, resolved)`** — sets the AI_REVIEW outcome in the review registry (RESOLVED or UNRESOLVED). The provisioner reads this during AI_REVIEW provisioning.
- **`resolveHumanReview(targetNodeId)`** — marks a HUMAN_REVIEW as RESOLVED in the review registry.

---

## 7. SPI Implementations

### 7.1 PipelineGoalCompiler

`implements GoalCompiler<PipelineBlueprint>`

`PipelineBlueprint` — builder-pattern goal declaration:

```java
PipelineBlueprint.builder()
    .source("clickstream", "json", "kafka://clicks")
    .schema("click-schema", List.of("userId", "pageUrl", "timestamp"), 1)
    .ingestion("click-ingest", "clickstream", 1000, "json")
    .cleanser("click-clean", List.of("deduplicate", "normalize-timestamps"), true, "DROP")
    .enricher("geo-enrich", "geo-lookup", List.of("userId"), List.of("country", "city"))
    .validator("quality-gate", "click-schema", 0.95, true)
    .transformer("session-agg", List.of("sessionize", "count-pages"), "parquet")
    .sink("warehouse", "s3://analytics/sessions", "parquet", List.of("date", "country"))
    .build();
```

The compiler:
1. Creates `DesiredNode` for each entry with the correct `NodeType` and `NodeSpec`
2. Assigns medallion layers based on node type (layer is a derived property, not stored on the node)
3. Wires dependencies from the canonical pipeline topology (Section 5)
4. Returns the `DesiredStateGraph` via the factory

### 7.2 PipelineProvisioner

`implements NodeProvisioner`

Dispatches on `NodeType`:

| Node Type | Provision action | Deprovision action |
|-----------|-----------------|-------------------|
| `DATA_SOURCE` | Register source in world | Remove source |
| `SCHEMA` | Register schema definition in registry | Remove schema |
| `INGESTION` | Validate source exists → set RUNNING | Remove stage, cascade downstream to IDLE |
| `CLEANSER` | Validate input schema compatible → set RUNNING | Remove stage, cascade downstream to IDLE |
| `ENRICHER` | Validate lookup source registered → set RUNNING | Remove stage, cascade downstream to IDLE |
| `VALIDATOR` | Validate schema ref exists, check quality threshold → RUNNING or QUARANTINED | Remove stage, cascade downstream to IDLE |
| `TRANSFORMER` | Validate upstream chain complete → set RUNNING | Remove stage, cascade downstream to IDLE |
| `SINK` | Validate full chain schema-compatible → set RUNNING | Remove stage |
| `AI_REVIEW` | Read `world.aiReviewOutcome(targetNode)`: if RESOLVED → store ReviewState.RESOLVED in review registry + call `world.clearStageError(targetNode)`. If UNRESOLVED → store ReviewState.UNRESOLVED. Returns `ProvisionResult.Success()` in both cases (the diagnosis completed). | Remove review node from registry |
| `HUMAN_REVIEW` | No-op (`requiresHuman=true` → skipped by `SimpleTransitionExecutor`) | Remove review node from registry |

### 7.3 PipelineActualStateAdapter

`implements ActualStateAdapter`

Reads `PipelineWorld` stage states and translates:

| StageState | NodeStatus |
|-----------|-----------|
| `RUNNING`, `COMPLETED` | `PRESENT` |
| `IDLE`, `FAILED`, not found | `ABSENT` |
| `DEGRADED`, `QUARANTINED` | `DRIFTED` |

For `DATA_SOURCE` and `SCHEMA` nodes: present if registered in world, absent otherwise.

For review nodes (AI_REVIEW, HUMAN_REVIEW): reads from review registry — RESOLVED → `PRESENT`, PENDING/UNRESOLVED → `ABSENT`.

### 7.4 PipelineEventSource

`implements EventSource`

Simulation methods (not called by production code — used by tests and visualizer):
- `stageFailure(stageId)` → emits `StateEvent(nodeId, ABSENT, "Stage failure")`
- `schemaDrift(schemaId)` → emits `StateEvent(nodeId, DRIFTED, "Schema drift detected")`
- `throughputDrop(stageId)` → emits `StateEvent(nodeId, DRIFTED, "Throughput degradation")`
- `dataArrival(sourceId)` → emits `StateEvent(nodeId, PRESENT, "New data batch")`

Note: `BroadcastProcessor` (used by the dungeon) is deprecated in SmallRye Mutiny. Use the current API equivalent at implementation time.

---

## 8. Fault Policies — Three-Tier Escalation

Three fault policies with clean separation by FaultType and NodeType. No shared state between policies. The reconciliation loop's built-in retry provides tier 1 — it naturally retries ABSENT nodes every cycle without needing a fault policy.

### 8.1 ProvisionEscalationFaultPolicy

**Handles:** `PROVISION_FAILED` — all node types (except `AI_REVIEW` and `HUMAN_REVIEW` to prevent infinite regress).

Owns the full PROVISION_FAILED lifecycle: built-in retry → AI review → human escalation. Tracks failure count per node in an internal `Map<NodeId, Integer>`. Once a node is successfully provisioned, it stops generating PROVISION_FAILED events — the counter sits inert.

**Escalation logic:**

| Fault count | Action | Rationale |
|-------------|--------|-----------|
| 1-3 | Returns empty list | Reconciliation loop retries automatically — no policy action needed |
| 4 | Creates `AI_REVIEW` node (`ai-review-{targetNodeId}`, `requiresHuman=false`) with `AiReviewSpec(targetNodeId, errorDetail)` | Trigger AI-assisted diagnosis |
| 5+ | Checks review registry: | |
| | — AI_REVIEW PENDING → returns empty | Wait for diagnosis to complete |
| | — AI_REVIEW RESOLVED → returns empty | Fix takes effect on next provision cycle |
| | — AI_REVIEW UNRESOLVED → creates `HUMAN_REVIEW` node (`human-review-{targetNodeId}`, `requiresHuman=true`) with `HumanReviewSpec(targetNodeId, errorDetail, "AI review could not resolve")` | Escalate to human |
| | — HUMAN_REVIEW already exists for target → returns empty | Already escalated, idempotency guard |

### 8.2 QuarantineFaultPolicy

**Handles:** `NODE_DEGRADED` where the faulted node's type is `VALIDATOR`.

Injects `PipelineWorld` to disambiguate: only activates when `world.stageState(nodeId) == QUARANTINED`. Validators that are DEGRADED due to upstream schema drift (not quarantine) are handled by SchemaDriftFaultPolicy instead — this prevents duplicate HUMAN_REVIEW nodes for a single root cause.

Creates `HUMAN_REVIEW` node (`human-review-{targetNodeId}`, `requiresHuman=true`) with `HumanReviewSpec(targetNodeId, recordCounts, failureReason)`.

### 8.3 SchemaDriftFaultPolicy

**Handles:** `NODE_DEGRADED` where the faulted node's type is `SCHEMA`.

Creates `HUMAN_REVIEW` node only (`human-review-{targetNodeId}`, `requiresHuman=true`) for schema change approval. Does NOT return `RemoveNode` mutations for downstream stages — `RemoveNode` destroys both the node and all its dependency edges in `ImmutableDesiredStateGraph.withoutNode()`, making the graph topology irrecoverable without re-invoking the GoalCompiler.

Downstream stages remain in the desired graph as DRIFTED. The planner ignores them (not ABSENT). Once the human approves the schema change via `world.approveSchemaChange(schemaName, newVersion)`, dependent stages return to RUNNING, the adapter reports them as PRESENT, and reconciliation resolves naturally.

### 8.4 Composition

Three policies passed to `FaultPolicyEngine`, CDI-ordered by `@Priority`. Clean separation by FaultType:

| Policy | FaultType | NodeType filter |
|--------|-----------|----------------|
| `ProvisionEscalationFaultPolicy` | `PROVISION_FAILED` | All except AI_REVIEW, HUMAN_REVIEW |
| `QuarantineFaultPolicy` | `NODE_DEGRADED` | VALIDATOR (+ PipelineWorld check for QUARANTINED) |
| `SchemaDriftFaultPolicy` | `NODE_DEGRADED` | SCHEMA |

No shared state. No overlapping fault type/node type combinations.

---

## 9. Runtime Prerequisites

The pipeline example exposes three gaps in the generic runtime that must be fixed before the example can work correctly. These are genuine runtime improvements, not pipeline-specific workarounds — the dungeon example didn't trigger them because it uses simpler fault patterns.

### 9.1 DRIFTED → NODE_DEGRADED fault events

`ReconciliationLoop.reconcile()` only creates fault events from `StepOutcome.Failed` (always `PROVISION_FAILED`). `DRIFTED` nodes are invisible to the fault policy system — the planner ignores them (not ABSENT), and no fault event is ever created for them. `FaultType.NODE_DEGRADED` exists in the API but nothing in the runtime produces it.

**Fix:** Add drift detection to `reconcile()` BEFORE the empty-plan early return. After reading actual state, scan for nodes that are in the desired graph and have status `DRIFTED`. Create `NODE_DEGRADED` fault events for each and feed them through `FaultPolicyEngine`. The restructured reconcile cycle:

1. Read actual state
2. Detect DRIFTED nodes → create NODE_DEGRADED fault events → apply fault policy mutations to desired graph
3. Plan transition (on the potentially-mutated desired graph)
4. If plan empty, return
5. Execute transition
6. Process execution failures (PROVISION_FAILED fault events)

### 9.2 CAS race in fault mutation accumulation

When multiple nodes fail provisioning in the same cycle, only the first fault's mutations survive. The `compareAndSet` in the fault processing loop uses the original `desired` as the expected value, but the first CAS already changed `desiredRef`. Subsequent CAS operations fail silently.

**Fix:** Accumulate all mutations across all fault events on a progressively-mutated graph, then apply once:

```java
DesiredStateGraph mutated = desired;
for (Map.Entry<NodeId, StepOutcome> entry : result.outcomes().entrySet()) {
    if (entry.getValue() instanceof StepOutcome.Failed failed) {
        FaultEvent faultEvent = new FaultEvent(entry.getKey(), FaultType.PROVISION_FAILED, failed.reason());
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

### 9.3 DEPROVISION_FAILED never produced

The reconciliation loop tags all `StepOutcome.Failed` outcomes as `PROVISION_FAILED`, including deprovision failures. `FaultType.DEPROVISION_FAILED` exists in the API but is never used. The fix is straightforward: correlate the failed node ID with the plan's removals vs additions to determine the correct fault type. Low priority for the pipeline example (deprovision failures are unlikely in the simulation) but should be fixed for correctness.

---

## 10. Visualizer

`PipelineVisualizer` — JAX-RS resource.

`GET /pipeline/stream` — SSE endpoint streaming `PipelineSnapshot` every 500ms:

```java
public record PipelineSnapshot(
    Map<String, StageView> bronzeStages,
    Map<String, StageView> silverStages,
    Map<String, StageView> goldStages,
    Map<String, ReviewView> activeReviews,
    Map<String, SchemaView> schemas
) {}

public record StageView(StageState state, long processed, long failed, long quarantined) {}
public record ReviewView(String nodeType, String targetStageId, String reason, ReviewState state) {}
public record SchemaView(String name, int version, List<String> fields) {}
```

Groups stages by medallion layer. No frontend HTML — JSON SSE endpoint only.

---

## 11. Test Suite

`PipelineTest` — plain JUnit (not `@QuarkusTest`), matching dungeon pattern.

| # | Test | Validates |
|---|------|----------|
| 1 | `buildBasicPipeline` | Blueprint → GoalCompiler → graph with correct dependency structure, all 8 node types, medallion layer assignments |
| 2 | `topologicalOrderMatchesMedallionLayers` | Additions follow dependency order which produces Bronze → Silver → Gold |
| 3 | `provisionFullPipeline` | Provision all stages → PipelineWorld states are RUNNING, schema compatibility validated |
| 4 | `schemaIncompatibility_failsProvision` | Mismatched schemas → ProvisionResult.Failed with meaningful reason |
| 5 | `provisionFailure_fullEscalationChain` | Ingestion fails → 3 retries (built-in) → AI_REVIEW created (event 4) → UNRESOLVED → HUMAN_REVIEW created (event 5+) |
| 6 | `provisionFailure_aiReviewResolves` | Ingestion fails → AI_REVIEW → RESOLVED → clearStageError → next provision succeeds |
| 7 | `validationQuarantine_humanReview` | Validator quarantines records → QuarantineFaultPolicy creates HUMAN_REVIEW (requiresHuman=true) |
| 8 | `schemaDrift_humanReviewOnly` | Schema version change → SchemaDriftFaultPolicy creates HUMAN_REVIEW, downstream stays DRIFTED (no RemoveNode) |
| 9 | `schemaDrift_approvalRestoresPipeline` | Human approves via approveSchemaChange() → downstream stages return to RUNNING → PRESENT |
| 10 | `deprovisionCascade_downstreamGoesIdle` | Removing cleanser cascades enricher/validator/transformer/sink to IDLE |
| 11 | `fullReconciliationCycle` | End-to-end: build → run → fault → escalate → resolve → reconcile back to healthy |
| 12 | `faultGeneratedNodes_neverInBlueprint` | AI_REVIEW and HUMAN_REVIEW only appear from fault policies, not GoalCompiler |

---

## 12. Known Gaps (Deferred)

- **Fault-generated node cleanup:** Resolved AI_REVIEW and HUMAN_REVIEW nodes linger in the desired graph as PRESENT. Functionally harmless (planner ignores them) but cosmetically imperfect. Cleanup belongs in the runtime evolution, not the example.
- **Removal ordering:** The `TransitionPlanner` collects orphaned removals without topological ordering. Orphaned nodes (PRESENT in actual, absent from desired graph) have lost their dependency context, so topological sorting is impossible within the current planner design. The pipeline provisioner's deprovision cascade (downstream → IDLE) handles consistency independently.

---

## 13. Future Evolution

This example is designed to evolve into a real CaseHub runtime capability. Deferred items tracked as issues:

| # | Description | Issue |
|---|-------------|-------|
| 1 | Managed pipeline mode — Quarkus Flow orchestration per node with audit trail | #27 |
| 2 | Per-node execution toolbox — Camel, Drools, Stream backends | #28 |
| 3 | Real LLM integration for AI_REVIEW fault nodes via agent-api | #29 |
| 4 | Real WorkItem integration for HUMAN_REVIEW nodes via casehub-work | #30 |
| 5 | Medallion layer enforcement as a planner constraint | #31 |

The `TransitionExecutor` SPI already supports the managed/throughput split by design:
- **Pure throughput** → `SimpleTransitionExecutor` (sequential, no orchestration overhead)
- **Managed** → `CaseTransitionExecutor` (Quarkus Flow per node, audit trail via casehub-ledger)

---

## 14. Files

| File | Purpose |
|------|---------|
| `PipelineLayer.java` | Medallion layer enum (BRONZE, SILVER, GOLD) |
| `PipelineNodeTypes.java` | NodeType constants (10 types) |
| `DataSourceSpec.java` | NodeSpec for data sources |
| `SchemaSpec.java` | NodeSpec for schema definitions |
| `IngestionSpec.java` | NodeSpec for ingestion stages |
| `CleanserSpec.java` | NodeSpec for cleansing stages |
| `EnricherSpec.java` | NodeSpec for enrichment stages |
| `ValidatorSpec.java` | NodeSpec for validation stages |
| `TransformerSpec.java` | NodeSpec for transformation stages |
| `SinkSpec.java` | NodeSpec for sink destinations |
| `AiReviewSpec.java` | NodeSpec for AI review nodes (fault-generated) |
| `HumanReviewSpec.java` | NodeSpec for human review nodes (fault-generated) |
| `PipelineBlueprint.java` | Goal declaration with builder |
| `PipelineGoalCompiler.java` | GoalCompiler implementation |
| `PipelineWorld.java` | In-memory simulation (@ApplicationScoped) |
| `PipelineProvisioner.java` | NodeProvisioner implementation |
| `PipelineActualStateAdapter.java` | ActualStateAdapter implementation |
| `PipelineEventSource.java` | EventSource implementation |
| `ProvisionEscalationFaultPolicy.java` | PROVISION_FAILED: retry count → AI review → human escalation |
| `QuarantineFaultPolicy.java` | NODE_DEGRADED + VALIDATOR: quarantine → human review |
| `SchemaDriftFaultPolicy.java` | NODE_DEGRADED + SCHEMA: drift → human approval |
| `MedallionLayerConstraint.java` | Validates graph respects medallion layer ordering |
| `PipelineVisualizer.java` | SSE endpoint for pipeline state |
| `PipelineTest.java` | 20-test suite |
