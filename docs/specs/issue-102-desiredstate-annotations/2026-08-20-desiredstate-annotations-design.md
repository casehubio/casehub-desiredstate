# casehub-desiredstate-annotations — Design Spec

**Date:** 2026-08-20
**Issue:** casehubio/casehub-desiredstate#102
**Epic:** casehubio/blocks#115 (annotation-driven agent programming model)
**Status:** Draft

## Motivation

The desiredstate runtime requires implementing `GoalCompiler`, constructing `DesiredNode` instances,
manually pairing `NodeSpec` implementations with `NodeType` values, and wiring `Dependency` edges —
boilerplate that obscures the graph's structure. The annotation model makes static graph declarations
visual and validates them at build time.

This module follows the pattern established by `casehub-engine-annotations`, `casehub-eidos-annotations`,
`casehub-work-annotations`, and `casehub-ledger-annotations`. Each repo's `*-annotations` module
provides declarative alternatives to programmatic builders while producing the same runtime types.

## Scope

**In scope:**
- `@DesiredState`, `@Node` (with `humanGating` attribute), `@DependsOn`, `@FaultPolicy`, `@Customize` annotations
- Quarkus build extension: Jandex scan, validation, `SyntheticBeanBuildItem` generation
- `NodeSpec.nodeType()` addition and `DesiredNode` simplification (prerequisite refactoring)
- `FaultPolicy.addReviewNode()` simplification (cascading from nodeType change)
- `pipeline-annotated` example

**Out of scope:**
- `CompilationResult.Lifecycle` (multi-phase) — stays as GoalCompiler concern
- Custom `FaultPolicy` implementations — annotations handle `ThresholdFaultPolicy` only
- `langchain4j-agentic` dependency — no LC4j annotations compose onto graph declarations
- YAML graph declarations

---

## Part 1: Prerequisite Refactoring (desiredstate-api)

### 1.1 NodeSpec gains nodeType()

```java
public interface NodeSpec {
    NodeType nodeType();
    default HumanGating humanGating() { return HumanGating.NONE; }
}
```

Every `NodeSpec` implementation declares its node type. The mapping between spec and type is intrinsic
to the type system, not external assembly by the GoalCompiler.

**Migration:** ~50 implementations across desiredstate (examples) and ops repos. Each adds:
```java
@Override public NodeType nodeType() { return PipelineNodeTypes.DATA_SOURCE; }
```

### 1.2 DesiredNode simplified

```java
public record DesiredNode(NodeId id, NodeSpec spec, HumanGating humanGating) {

    public DesiredNode {
        Objects.requireNonNull(id, "DesiredNode id must not be null");
        Objects.requireNonNull(spec, "DesiredNode spec must not be null");
        Objects.requireNonNull(humanGating, "DesiredNode humanGating must not be null");
    }

    public NodeType type() {
        return spec.nodeType();
    }

    public boolean requiresHuman(StepAction action) {
        return humanGating.requiresHuman(action) || spec.humanGating().requiresHuman(action);
    }

    public boolean requiresHuman() {
        return humanGating.any() || spec.humanGating().any();
    }
}
```

`type` is derived from `spec.nodeType()`. All callers access `.type()` via method call — transparent
whether it's a record component or derived. Construction simplifies from 4 args to 3.

**Before:** `new DesiredNode(NodeId.of("src"), PipelineNodeTypes.DATA_SOURCE, spec, HumanGating.NONE)`
**After:** `new DesiredNode(NodeId.of("src"), spec, HumanGating.NONE)`

### 1.3 FaultPolicy.addReviewNode() simplified

```java
static FaultPolicy addReviewNode(ReviewSpecFactory specFactory) {
    return (tenancyId, event, current, actual) -> {
        NodeSpec reviewSpec = specFactory.create(event, current);
        NodeType reviewType = reviewSpec.nodeType();
        NodeId reviewId = NodeId.of(reviewType.value() + "-" + event.node().value());
        // ... single factory call, ID derived from cached result
    };
}
```

The separate `NodeType reviewNodeType` parameter is removed — the review spec's `nodeType()` provides it.
The factory is called once per fault; the result is cached to derive the review node ID and create
the `DesiredNode`. The existing two-arg overload is deprecated and delegates to the one-arg version.

### 1.4 Impact summary

| Repo | Modules affected | Change type |
|------|-----------------|-------------|
| desiredstate | api, runtime, engine-adapter, work-adapter, ras-adapter, testing, all examples | NodeSpec.nodeType(), DesiredNode 4→3 args, addReviewNode |
| ops | api, app, deployment, compliance, infra, iot | NodeSpec.nodeType(), DesiredNode 4→3 args |

---

## Part 2: Annotations Module

### 2.1 Module structure

```
annotations/
  pom.xml                          # parent
  runtime/
    pom.xml                        # depends on desiredstate-api
    src/main/java/io/casehub/desiredstate/annotations/
      DesiredState.java            # @DesiredState
      Node.java                    # @Node (includes humanGating attribute)
      DependsOn.java              # @DependsOn
      FaultPolicyDef.java         # @FaultPolicy
      FaultPolicies.java          # @FaultPolicies (repeatable container)
      Tier.java                    # @Tier (nested in @FaultPolicy)
      Customize.java              # @Customize
    src/main/java/io/casehub/desiredstate/annotations/runtime/
      GraphDescriptor.java         # carries build-time metadata to recorder
      NodeDescriptor.java
      DependencyDescriptor.java
      FaultPolicyDescriptor.java
      TierDescriptor.java
      DesiredStateGraphRecorder.java  # Quarkus recorder — builds GoalCompiler at runtime init
  deployment/
    pom.xml                        # depends on runtime + Quarkus deployment
    src/main/java/io/casehub/desiredstate/annotations/deployment/
      DesiredStateAnnotationsProcessor.java   # Quarkus build extension
      AnnotationValidationStep.java           # build-time validation
    src/test/java/...
      DesiredStateAnnotationsProcessorTest.java
      ValidationErrorTest.java
      FaultPolicyWiringTest.java
```

**Artifact coordinates:**
- `io.casehub:casehub-desiredstate-annotations` (runtime)
- `io.casehub:casehub-desiredstate-annotations-deployment` (deployment)

### 2.2 Annotations

#### @DesiredState

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DesiredState {
    String namespace() default "";
    String name() default "";
}
```

Marks an interface as a desired-state graph declaration. The build extension generates a
`GoalCompiler<Void>` CDI bean from the annotated interface. `namespace` and `name` are optional
metadata for bean qualification when multiple graphs coexist.

#### @Node

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Node {
    String value();  // node ID
    io.casehub.desiredstate.api.HumanGating humanGating()
        default io.casehub.desiredstate.api.HumanGating.NONE;
}
```

Declares a method as a node declaration. The method must be a `default` method returning a `NodeSpec`
implementation. The return type's `nodeType()` provides the `NodeType`. The `value` is the `NodeId`.

`humanGating` is an attribute of `@Node` rather than a separate annotation to avoid name collision
with the existing `io.casehub.desiredstate.api.HumanGating` enum. The attribute value IS the api enum,
so user code imports only the enum — no collision.

#### @DependsOn

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DependsOn {
    String[] value();  // node IDs this node depends on
}
```

Declares dependency edges. Each entry references a `@Node` id on the same interface. The build
extension validates all references resolve.

#### @FaultPolicy

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(FaultPolicies.class)
public @interface FaultPolicy {
    String[] faultTypes();                // FaultType enum names
    String[] nodeTypes() default {};      // filter — empty = infer from @Node context
    String[] ignoreTypes() default {};    // ThresholdFaultPolicy.ignoreTypes
    String namespace() default "";        // empty = auto-derived from faultTypes
    Tier[] tiers();
}
```

On `ElementType.TYPE`: applies to specified `nodeTypes` (required). On `ElementType.METHOD`: must be
on a `@Node`-annotated method — `nodeTypes` is inferred from the return type's `nodeType()`.
The build extension emits an error if `@FaultPolicy` appears on a non-`@Node` method.
`@Repeatable` allows multiple policies on the same interface (different fault type sets).

**Precedence:** When both interface-level and method-level `@FaultPolicy` match the same node type,
method-level wins (more specific). Interface-level is the fallback for unmatched types.

#### @Tier

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Tier {
    int threshold();
    String review();  // method name on the interface — ReviewSpecFactory
}
```

Each tier names a method that serves as the `ReviewSpecFactory`. The method must:
- Exist on the `@DesiredState` interface
- Return a type implementing `NodeSpec`
- Accept `(FaultEvent, DesiredStateGraph)` parameters

The build extension validates all three constraints at build time.

#### @Customize

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Customize {
    String value() default "";  // for @FaultPolicy: names the @FaultPolicy to target
}
```

Escape hatch for advanced configuration. Static method on the interface:
- `ThresholdFaultPolicy.Builder` parameter: customize the generated fault policy
- `DesiredStateGraph` parameter: post-process the generated graph

### 2.3 Programming model

```java
@DesiredState(namespace = "pipeline", name = "medallion")
@FaultPolicy(
    faultTypes = {"PROVISION_FAILED"},
    nodeTypes = {"transformer", "sink"},
    tiers = {
        @Tier(threshold = 3, review = "createAiReview"),
        @Tier(threshold = 5, review = "createHumanReview")
    }
)
public interface MedallionPipeline {

    // --- Bronze layer ---

    @Node("csv-source")
    default DataSourceSpec csvSource() {
        return new DataSourceSpec("CSV", "s3://data/customers.csv");
    }

    @Node("customer-schema")
    default SchemaSpec customerSchema() {
        return new SchemaSpec(List.of("id", "name", "email"), 1);
    }

    @Node("csv-ingest")
    @DependsOn("csv-source")
    default IngestionSpec csvIngestion() {
        return new IngestionSpec("csv-source", 1000, "CSV");
    }

    // --- Silver layer ---

    @Node("dedup-cleanser")
    @DependsOn({"csv-ingest", "customer-schema"})
    default CleanserSpec dedupCleanser() {
        return new CleanserSpec(List.of("dedup", "nullcheck"), true, "DROP");
    }

    @Node("geo-enricher")
    @DependsOn("dedup-cleanser")
    default EnricherSpec geoEnricher() {
        return new EnricherSpec("geo-lookup", List.of("address"), List.of("lat", "lon"));
    }

    @Node("quality-validator")
    @DependsOn({"geo-enricher", "customer-schema"})
    default ValidatorSpec qualityValidator() {
        return new ValidatorSpec("customer-schema", 0.95, true);
    }

    // --- Gold layer ---

    @Node(value = "aggregate-tx", humanGating = HumanGating.PROVISION_ONLY)
    @DependsOn("quality-validator")
    default TransformerSpec aggregateTransformer() {
        return new TransformerSpec(List.of("sum", "avg"), List.of(), "parquet", true);
    }

    @Node(value = "warehouse-sink", humanGating = HumanGating.PROVISION_ONLY)
    @DependsOn("aggregate-tx")
    default SinkSpec warehouseSink() {
        return new SinkSpec("s3://warehouse/gold/", "parquet", List.of("date"), true);
    }

    // --- Fault policy review spec factories ---

    default AiReviewSpec createAiReview(FaultEvent event, DesiredStateGraph graph) {
        return new AiReviewSpec(event.node(), event.detail());
    }

    default HumanReviewSpec createHumanReview(FaultEvent event, DesiredStateGraph graph) {
        return new HumanReviewSpec(event.node(), event.detail(), "Requires manual review");
    }
}
```

### 2.4 Build extension architecture

```
@DesiredState interface
        │
        ▼ (Jandex scan at build time)
DesiredStateAnnotationsProcessor
        │
        ├─ Scan @Node methods → NodeDescriptor(id, returnType, humanGating)
        ├─ Scan @DependsOn → DependencyDescriptor(from, to)
        ├─ Scan @FaultPolicy → FaultPolicyDescriptor(faultTypes, nodeTypes, tiers)
        ├─ Validate: all @DependsOn refs resolve, review methods exist, return types implement NodeSpec
        │
        ▼ (SyntheticBeanBuildItem + @Record RUNTIME_INIT)
DesiredStateGraphRecorder
        │
        ├─ Instantiate interface (Gizmo-generated impl or CDI proxy)
        ├─ Call each @Node method → NodeSpec
        ├─ Build DesiredNode(NodeId, spec, humanGating) per node
        ├─ Build Dependency edges from descriptors
        ├─ Create DesiredStateGraph via factory
        ├─ Wrap in GoalCompiler<Void> returning CompilationResult.single(graph)
        │
        ▼ (CDI bean registration)
GoalCompiler<Void> bean          ThresholdFaultPolicy bean(s)
```

The processor runs at build time, extracting structural metadata into descriptor records.
The recorder runs at runtime init, calling default methods for spec values and assembling
the graph. This separation follows the engine-annotations pattern exactly.

### 2.5 Build-time validation

| Check | Error message |
|-------|---------------|
| @Node method not default | `@Node on 'csvSource' must be a default method returning NodeSpec` |
| @Node return type not NodeSpec | `@Node 'csvSource' return type DataSourceSpec does not implement NodeSpec` |
| @DependsOn unknown reference | `@DependsOn on 'csvIngest' references 'unknown-id' which is not declared as @Node` |
| Duplicate @Node IDs | `Duplicate @Node id 'csv-source' on methods 'csvSource' and 'anotherSource'` |
| Circular dependency | `Circular dependency detected: csv-ingest → dedup-cleanser → csv-ingest` |
| @Tier review method missing | `@Tier review 'createAiReview' not found on interface MedallionPipeline` |
| @Tier review return not NodeSpec | `Review method 'createAiReview' return type must implement NodeSpec` |
| @Tier review bad signature | `Review method 'createAiReview' must accept (FaultEvent, DesiredStateGraph)` |
| @FaultPolicy invalid faultType | `Unknown FaultType 'INVALID' in @FaultPolicy — valid: PROVISION_FAILED, NODE_DEGRADED, ...` |
| @FaultPolicy on non-@Node method | `@FaultPolicy on method 'goldTierFaults' which is not annotated with @Node — use @FaultPolicy on the interface for cross-type policies` |
| Empty @DesiredState (warning) | `@DesiredState 'MedallionPipeline' has no @Node methods — graph will be empty` |

### 2.6 GoalCompiler<Void> integration

The generated `GoalCompiler<Void>` ignores its goals argument (always `null`) and returns the
static graph. `LifecycleManager.start()` discovers it via CDI and calls `compile(null, factory)`.

The recorder wraps the graph in a no-op compiler:
```java
RuntimeValue<GoalCompiler<Void>> createGoalCompiler(GraphDescriptor descriptor) {
    // calls @Node methods, builds graph, returns:
    return new RuntimeValue<>((goals, factory) -> CompilationResult.single(graph));
}
```

When multiple `@DesiredState` interfaces exist, each produces a separate `GoalCompiler` bean.
The build extension registers it as the raw `GoalCompiler` type (CDI erases generics) with
a `@DesiredStateQualifier(namespace, name)` qualifier annotation for disambiguation. Single-graph
apps need no qualifier — CDI resolves the sole bean.

### 2.7 @Customize integration

```java
@Customize
static void customize(DesiredStateGraph graph) {
    // post-process: overlay additional nodes, connect subgraphs, etc.
}

@Customize("goldTierFaults")
static void customizeFaultPolicy(ThresholdFaultPolicy.Builder builder) {
    // set custom FaultCountStore, adjust namespace, etc.
    builder.faultCountStore(myStore);
}
```

The build extension calls `@Customize` methods after assembling the graph/policy from annotations.
Same semantics as engine-annotations: annotation-set values are already on the builder.

---

## Part 3: Example — pipeline-annotated

New module at `examples/pipeline-annotated/`. Implements the medallion architecture pipeline
(Bronze/Silver/Gold) using annotations instead of `PipelineGoalCompiler`.

Demonstrates:
- `@Node` with `@DependsOn` for the full pipeline topology
- `humanGating` attribute on Gold-tier nodes
- `@FaultPolicy` with two-tier escalation (AI review → human review)
- Review spec factory methods
- `@Customize` setting a custom `FaultCountStore` on the policy builder

The existing `examples/pipeline/` stays unchanged — showing the builder/GoalCompiler equivalent
side-by-side per the epic's three-programming-models principle.

---

## Testing Strategy

### Unit tests (runtime/)
- Annotation presence and attribute reflection
- Descriptor record construction and serialization

### Build extension tests (deployment/)
- **Processor test:** @DesiredState interface → GoalCompiler<Void> bean, verify graph nodes/deps match
- **Validation tests:** each error in §2.5 verified with a negative-test interface
- **FaultPolicy wiring:** @FaultPolicy → ThresholdFaultPolicy bean, verify tier thresholds and review method dispatch
- **@Customize:** verify customizer receives the builder after annotation processing
- **Drift-protection:** annotation attributes map 1:1 to builder/type equivalents (reflection parity test)

### Integration tests (pipeline-annotated example)
- Full Quarkus app with annotated pipeline
- Reconciliation loop runs against mock actual state
- Fault escalation through annotated tiers

---

## References

- [engine-annotations EngineAnnotationsProcessor.java](/Users/mdproctor/claude/casehub/engine/annotations/deployment/src/main/java/io/casehub/engine/annotations/deployment/EngineAnnotationsProcessor.java) — reference build extension
- [engine-annotations CAPABILITY-MATRIX.md](/Users/mdproctor/claude/casehub/engine/annotations/CAPABILITY-MATRIX.md) — capability → test mapping pattern
- [blocks#115 epic design spec](/Users/mdproctor/claude/casehub/blocks/docs/specs/annotation-driven-model/2026-08-14-annotation-driven-agent-model-design.md) — annotation-driven model architecture
- [NodeSpec.java](/Users/mdproctor/claude/casehub/desiredstate/api/src/main/java/io/casehub/desiredstate/api/NodeSpec.java) — current interface (to be extended)
- [DesiredNode.java](/Users/mdproctor/claude/casehub/desiredstate/api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java) — current record (to be simplified)
- [ThresholdFaultPolicy.java](/Users/mdproctor/claude/casehub/desiredstate/api/src/main/java/io/casehub/desiredstate/api/ThresholdFaultPolicy.java) — fault policy builder
- [PipelineGoalCompiler.java](/Users/mdproctor/claude/casehub/desiredstate/examples/pipeline/src/main/java/io/casehub/desiredstate/example/pipeline/PipelineGoalCompiler.java) — builder equivalent of annotated pipeline
- decisions.md — 9 design decisions with rationale
- decision-review.md — light review pass confirming all decisions
