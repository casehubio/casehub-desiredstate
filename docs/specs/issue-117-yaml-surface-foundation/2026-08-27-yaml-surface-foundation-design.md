# YAML Surface Foundation — Design Spec

**Issue:** casehubio/casehub-desiredstate#117
**Parent:** casehubio/casehub-desiredstate#116 (operator-first declaration language)
**Date:** 2026-08-27

## 1. Overview

Build the YAML → GraphDescriptor pipeline so operators can declare desired-state graphs
in YAML and have them compile and reconcile identically to the annotation path.

**In scope:**
- YAML top-level schema (graph envelope)
- YAML → GraphDescriptor deserializer (Jackson-based)
- `@NodeType` annotation and Jandex-discovered NodeSpec type registry
- Quarkus build extension (classpath YAML discovery → GoalCompiler beans)
- Variable substitution (Map → Preferences → Config fallthrough)
- YAML-driven pipeline example (side-by-side with `examples/pipeline-annotated/`)

**Out of scope (follow-up issues under #116):**
- YAML fault policies (requires ReviewAction aliasing — D8)
- YAML rules and invariants (requires Drools backend)
- Conditional/iterated subgraph inclusion (`when:`, `forEach:`)
- Module composition (imports, overrides)
- REST endpoint for runtime YAML submission (D9) — explicitly descoped from #117; the build-time pipeline must be proven before adding a runtime HTTP surface. Follow-up issue to be filed under #116
- Lifecycle hooks on nodes

## 2. Module Structure

Two new modules following the `annotations/` Quarkus extension pattern:

```
yaml/
├── runtime/        casehub-desiredstate-yaml
│   └── src/main/java/io/casehub/desiredstate/yaml/
│       ├── model/         YAML deserialization model (YamlGraph, YamlNode)
│       ├── registry/      NodeSpecRegistry
│       ├── resolver/      VariableResolver (Map → Preferences → Config)
│       └── YamlGraphRecorder.java   @Recorder — YAML-specific GoalCompiler creation
│
├── deployment/     casehub-desiredstate-yaml-deployment
│   └── src/main/java/io/casehub/desiredstate/yaml/deployment/
│       └── YamlDesiredStateProcessor.java   @BuildStep — classpath scan, validation
│
└── pom.xml         parent POM for yaml/runtime + yaml/deployment

examples/
└── pipeline-yaml/  casehub-desiredstate-example-pipeline-yaml
    ├── src/main/resources/
    │   └── META-INF/desiredstate/
    │       └── medallion-pipeline.yaml
    └── src/test/java/...
```

**Dependencies:**
- `yaml/runtime/` depends on `annotations/runtime/` (GraphDescriptor, NodeDescriptor, descriptor records) and `api/` (NodeSpec, GoalCompiler, etc.)
- `yaml/deployment/` depends on `yaml/runtime/`, `annotations/runtime/`, and `annotations/deployment/` (for shared `DesiredStateGraphBuildItem`)
- `examples/pipeline-yaml/` depends on `yaml/runtime/`, `examples/pipeline/` (reuses NodeSpec implementations), and `runtime/` (reconciliation loop)

## 3. YAML Schema

```yaml
desiredState:
  namespace: pipeline
  name: medallion

variables:
  batch_size: 1000
  source_uri: s3://data/customers.csv

nodes:
  # --- Bronze layer ---
  csv-source:
    type: data-source
    spec:
      name: customers
      format: CSV
      uri: ${source_uri}

  customer-schema:
    type: schema
    spec:
      name: customer-schema
      fields: [id, name, email]
      version: 1

  csv-ingest:
    type: ingestion
    dependsOn: [csv-source]
    spec:
      sourceRef: csv-source
      batchSize: ${batch_size}
      format: CSV

  # --- Silver layer ---
  dedup-cleanser:
    type: cleanser
    dependsOn: [csv-ingest, customer-schema]
    spec:
      rules: [dedup, nullcheck]
      deduplication: true
      nullHandling: DROP

  geo-enricher:
    type: enricher
    dependsOn: [dedup-cleanser]
    spec:
      lookupSource: geo-lookup
      joinKeys: [address]
      enrichFields: [lat, lon]

  quality-validator:
    type: validator
    dependsOn: [geo-enricher, customer-schema]
    spec:
      schemaRef: customer-schema
      qualityThreshold: 0.95
      anomalyDetection: true

  # --- Gold layer ---
  aggregate-tx:
    type: transformer
    dependsOn: [quality-validator]
    humanGating: PROVISION_ONLY
    spec:
      aggregations: [sum, avg]
      reshapeRules: []
      outputFormat: parquet
      approvalRequired: true

  warehouse-sink:
    type: sink
    dependsOn: [aggregate-tx]
    humanGating: PROVISION_ONLY
    spec:
      destination: s3://warehouse/gold/
      format: parquet
      partitionKeys: [date]
      approvalRequired: true
```

### 3.1 Schema Elements

| Key | Required | Type | Description |
|-----|----------|------|-------------|
| `desiredState.namespace` | yes | string | Graph namespace — becomes GoalCompiler qualifier |
| `desiredState.name` | yes | string | Graph name — becomes GoalCompiler qualifier |
| `variables` | no | map | Inline variable bindings (Map layer in resolution chain) |
| `nodes` | yes | map | Node ID → node definition |
| `nodes.<id>.type` | yes | string | NodeType value — resolved via type registry to NodeSpec class |
| `nodes.<id>.spec` | no | map | NodeSpec field values — deserialized into the resolved NodeSpec class. Defaults to `{}` for zero-field NodeSpec records |
| `nodes.<id>.dependsOn` | no | list | Node IDs this node depends on |
| `nodes.<id>.humanGating` | no | enum | `NONE` (default), `PROVISION_ONLY`, `DEPROVISION_ONLY`, `ALL`. Merged with `NodeSpec.humanGating()` via OR semantics — see §3.3 |

**Naming convention:** `spec:` field names use **camelCase** because they map directly
to Java record component names via `ObjectMapper.convertValue()`. Variable names in the
`variables:` section are operator-chosen strings with no Java mapping, so they follow
whatever convention the operator prefers (typically snake_case). If operator feedback
favours a uniform snake_case surface, a future issue can configure the ObjectMapper with
`PropertyNamingStrategies.SNAKE_CASE` — this is a non-breaking addition.

### 3.2 Future Extension Points

The schema accommodates future sections without breaking changes:

| Future key | Epic | Description |
|------------|------|-------------|
| `faultPolicies:` | Fault policy aliasing | Threshold tiers with action aliases |
| `rules:` | Drools backend | Pattern-matching graph rewriting rules |
| `invariants:` | Drools backend | Structural assertion validation |
| `nodes.<id>.when:` | Conditionals | Conditional node inclusion |
| `nodes.<id>.forEach:` | Iteration | Cardinality-driven stamping |
| `nodes.<id>.hooks:` | Lifecycle hooks | Pre/post provision/deprovision steps |
| `modules:` | Composition | Import + override of reusable subgraphs |

### 3.3 HumanGating Merge Semantics

YAML-level `humanGating` is merged with `NodeSpec.humanGating()` via OR semantics in
`DesiredNode.requiresHuman()`. This means:

- The YAML author can **strengthen** gating (e.g., add `PROVISION_ONLY` to a NodeSpec
  that defaults to `NONE`)
- The YAML author **cannot weaken** gating declared by the NodeSpec implementation

This is intentional: `NodeSpec.humanGating()` is a safety floor. A NodeSpec that declares
`humanGating() = ALL` is asserting "this node type always requires human oversight,
regardless of how it's declared." This is a domain-level safety constraint that operators
should not be able to bypass through YAML configuration.

If a YAML author sets `humanGating: NONE` but the NodeSpec returns `ALL`, the effective
gating is `ALL`. The recorder emits a warning at RUNTIME_INIT (in `YamlGraphRecorder`,
§9.1) when the effective gating (after merge with `NodeSpec.humanGating()`) differs from
the YAML-declared gating. This check runs after `ObjectMapper.convertValue()` creates the
NodeSpec instance — it cannot run at build time because `humanGating()` may depend on
deserialized spec field values (e.g., `TransformerSpec.humanGating()` returns
`DEPROVISION_ONLY` only when `approvalRequired == true`). The check is simply
`yamlGating != yamlGating.merge(specGating)`, covering all surprising interactions —
including non-comparable values like `PROVISION_ONLY` merged with `DEPROVISION_ONLY`
producing `ALL`.

## 4. YAML Deserialization Model

Jackson YAML deserializes into an intermediate model, not directly into GraphDescriptor.

### 4.1 Intermediate Model

```java
// yaml/runtime — deserialization target
public record YamlGraph(
    YamlDesiredState desiredState,
    Map<String, String> variables,        // inline variable bindings
    Map<String, YamlNode> nodes) {}       // node ID → node definition

public record YamlDesiredState(
    String namespace,
    String name) {}

public record YamlNode(
    String type,                          // NodeType string
    Map<String, Object> spec,             // raw spec values (may contain ${...})
    List<String> dependsOn,               // dependency node IDs
    HumanGating humanGating) {}           // defaults to NONE
```

### 4.2 Transformation Pipeline

```
YAML file
  → Jackson ObjectMapper (YAML factory)
  → YamlGraph (intermediate model)
  → Build extension resolves type → specClassName via registry
  → GraphDescriptor with InlineNode descriptors
  → Recorder creates GoalCompiler (at RUNTIME_INIT):
      1. Resolve variables (Map → Preferences → Config)
      2. ObjectMapper.convertValue(resolvedSpec, specClass) per node
      3. Build DesiredNode + Dependency lists
      4. Return CompilationResult.single(factory.of(nodes, deps))
```

## 5. NodeDescriptor Extension

Add a third variant to the sealed `NodeDescriptor` interface:

```java
public sealed interface NodeDescriptor
        permits NodeDescriptor.InterfaceNode, NodeDescriptor.ClassNode,
                NodeDescriptor.InlineNode {

    String id();

    record InterfaceNode(String id, String methodName, String returnTypeName,
                         HumanGating humanGating) implements NodeDescriptor {}

    record ClassNode(String id, String className) implements NodeDescriptor {}

    record InlineNode(String id, String specClassName,
                      Map<String, Object> specValues,
                      HumanGating humanGating) implements NodeDescriptor {}
}
```

- `specClassName` — fully qualified NodeSpec class name, resolved at build time
- `specValues` — raw field values (may contain unresolved `${...}` expressions)
- `Map<String, Object>` is bytecode-recording friendly (primitives, strings, nested maps/lists)

**Note:** `InlineNode` is not YAML-specific — it represents any node whose spec is
provided as inline key-value data. Future surfaces (TypeScript DSL, visual graph editor)
will also produce `InlineNode` descriptors.

Adding `InlineNode` to the sealed hierarchy requires a compile fix in
`DesiredStateGraphRecorder.buildNodes()`, which has an exhaustive switch over
`InterfaceNode` and `ClassNode`. The fix is trivial — add a case that throws
`IllegalStateException("InlineNode cannot appear in annotation-path graphs")`.
The `buildClassOnlyNodes()` method uses `instanceof` and is unaffected.

## 6. @NodeType Annotation and Type Registry

### 6.1 Annotation

```java
package io.casehub.desiredstate.api;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface NodeTypeId {
    String value();   // e.g., "data-source"
}
```

Placed in `api/` alongside `NodeSpec` — not in `annotations/` — because it is metadata
on a NodeSpec class, not an annotation-surface concept. Any NodeSpec can be made
addressable by data-driven surfaces (YAML, TypeScript DSL, visual editor) without
depending on any surface module. Named `@NodeTypeId` to avoid collision with the
`NodeType` record in the same package.

Applied to NodeSpec implementations:

```java
@NodeTypeId("data-source")
public record DataSourceSpec(String name, String format, String uri) implements NodeSpec {
    @Override
    public NodeType nodeType() {
        return PipelineNodeTypes.DATA_SOURCE;
    }
}
```

### 6.2 Registry

```java
package io.casehub.desiredstate.yaml.registry;

public class NodeSpecRegistry {
    private final Map<String, Class<? extends NodeSpec>> typeMap;

    public Class<? extends NodeSpec> resolve(String typeName) {
        Class<? extends NodeSpec> cls = typeMap.get(typeName);
        if (cls == null) {
            throw new IllegalArgumentException("Unknown node type: " + typeName
                + ". Available types: " + typeMap.keySet());
        }
        return cls;
    }

    public Set<String> availableTypes() {
        return Collections.unmodifiableSet(typeMap.keySet());
    }
}
```

### 6.3 Build-Time Discovery

The YAML build extension:

1. Scans Jandex combined index for all classes implementing `NodeSpec`
2. For each, reads the `@NodeTypeId` annotation value
3. Validates: no duplicate type strings, every YAML-referenced type has a match
4. Produces a `Map<String, String>` (typeName → className) passed to the recorder
5. NodeSpec classes without `@NodeTypeId` are not YAML-addressable (they remain
   available to the annotation and imperative paths)

**`@NodeTypeId` / `nodeType()` divergence:** The annotation value and
`NodeSpec.nodeType().value()` must be identical strings. Build-time validation cannot
check this (records with required constructor args cannot be instantiated at build time
without values). Instead, `YamlGraphRecorder` validates at RUNTIME_INIT after
`ObjectMapper.convertValue()` creates the NodeSpec instance — see §9.1.

## 7. Variable Substitution

### 7.1 Resolution Chain

Three-layer fallthrough, most-specific-wins:

1. **Map** — `variables:` section from the YAML file
2. **Preferences** — Platform Preferences API (`PreferenceProvider`)
3. **Config** — MicroProfile Config (`ConfigProvider.getConfig()`)

### 7.2 Resolver

```java
package io.casehub.desiredstate.yaml.resolver;

public class VariableResolver {
    private final Map<String, String> inlineVariables;
    private final PreferenceProvider preferences;    // may be null
    private final Config config;

    public Object resolve(Object value) {
        if (value instanceof String s && s.contains("${")) {
            return resolveString(s);
        }
        if (value instanceof Map<?, ?> map) {
            return resolveMap(map);
        }
        if (value instanceof List<?> list) {
            return resolveList(list);
        }
        return value;
    }
}
```

**CDI access for PreferenceProvider:** The `GoalCompiler.compile()` lambda executes at
RUNTIME_INIT when CDI (Arc) is available. `PreferenceProvider` is obtained via Arc's
programmatic lookup:

```java
InjectableInstance<PreferenceProvider> instance =
    Arc.container().select(PreferenceProvider.class);
PreferenceProvider preferences = instance.isResolvable() ? instance.get() : null;
```

This handles the case where no `PreferenceProvider` is on classpath (e.g., lightweight
test deployments) — `preferences` is null and the resolution chain skips the Preferences
layer.

Variable syntax: `${key}` — simple key lookup through the chain. No expression
language, no nesting, no defaults syntax in #117. Future epics can extend the
syntax (e.g., `${key:-default}`, `${env.KEY}`).

**Unresolved variable error:** If a `${key}` reference cannot be resolved in any layer,
`VariableResolver.resolveString()` throws `UnresolvedVariableException` with:
- The variable name
- The node ID where it was used
- The layers searched and available keys in the inline variables map

Example: `"Unresolved variable 'bacth_size' in node 'csv-ingest'. Not found in: inline
variables [batch_size, source_uri], Preferences, MicroProfile Config."`

This prevents the error cascade where an unresolved `${key}` string passes through to
`ObjectMapper.convertValue()` and produces a misleading Jackson deserialization error
(e.g., `Cannot deserialize value of type int from String "${bacth_size}"`).

### 7.3 Timing

Variables are resolved inside the `GoalCompiler.compile()` lambda at RUNTIME_INIT.
Values are frozen for the graph's lifetime. Picking up changed Preferences or Config
values requires explicit recompilation via `LifecycleManager.updateDesired()` or
`SituationRecompiler.recompile()`. This matches the existing annotation path behavior.

## 8. Build Extension

### 8.1 YAML File Discovery

YAML files are discovered on the classpath at `META-INF/desiredstate/*.yaml`.
The path convention mirrors how Quarkus extensions discover configuration
(e.g., `META-INF/services/`, `META-INF/resources/`).

### 8.2 Build Steps

```java
@BuildStep
void discoverYamlGraphs(CombinedIndexBuildItem index,
                        YamlGraphRecorder recorder,
                        BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
    // 1. Build NodeSpec type registry from Jandex
    Map<String, String> typeRegistry = scanNodeTypes(index.getIndex());

    // 2. Discover YAML files on classpath
    List<YamlGraph> yamlGraphs = discoverAndParseYaml();

    // 3. Validate each YAML file:
    //    - All type: references resolve in the registry
    //    - All dependsOn references resolve to declared node IDs
    //    - No duplicate node IDs within a file
    //    - namespace + name are present

    // 4. Transform each YamlGraph → GraphDescriptor (with InlineNode)
    for (YamlGraph yamlGraph : yamlGraphs) {
        GraphDescriptor descriptor = toGraphDescriptor(yamlGraph, typeRegistry);

        // 5. Create GoalCompiler bean via recorder
        RuntimeValue<GoalCompiler> compiler = recorder.createYamlGoalCompiler(
                descriptor, typeRegistry, yamlGraph.variables());

        // 6. Register as CDI bean with @DesiredStateQualifier
        registerGoalCompilerBean(compiler, syntheticBeans,
                yamlGraph.desiredState().namespace(),
                yamlGraph.desiredState().name());
    }
}
```

### 8.3 Validation

Build-time validation catches errors before deployment:

| Check | Error |
|-------|-------|
| Unknown `type:` in node | "Unknown node type 'foo'. Available: [data-source, ingestion, ...]" |
| Dangling `dependsOn` reference | "Node 'csv-ingest' depends on 'csv-source' which is not declared" |
| Duplicate node IDs | "Duplicate node ID 'csv-source' in medallion-pipeline.yaml" |
| Missing namespace/name | "desiredState.namespace and desiredState.name are required" |
| Duplicate type registry entries | "NodeType 'data-source' claimed by both DataSourceSpec and DataSourceSpec2" |
| Cyclic dependencies | "Cyclic dependency detected involving nodes: [a, b, c] in medallion-pipeline.yaml" |
| Cross-surface namespace:name collision | "Graph 'pipeline:medallion' declared by both YAML (medallion-pipeline.yaml) and annotations (MedallionPipeline)" |

**Cycle detection:** After building the dependency graph from `dependsOn` references,
run topological sort (Kahn's algorithm). If the sort does not consume all nodes, the
remaining nodes form a cycle. Report the involved node IDs. This is O(V+E) and adds
negligible build time. Catching cycles at build time is better UX than a
`CyclicDependencyException` at RUNTIME_INIT.

**Cross-surface collision detection:** Both `DesiredStateAnnotationsProcessor` and
`YamlDesiredStateProcessor` produce `DesiredStateGraphBuildItem` instances (a new
`MultiBuildItem`). A shared validation `@BuildStep` consumes all instances and fails
if any namespace:name pair is declared by more than one surface. The `BuildItem` is
defined in `annotations/deployment/` — both deployment modules can see it.

## 9. Recorder Integration

### 9.1 YamlGraphRecorder

A new recorder in `yaml/runtime/` that handles `InlineNode` resolution:

```java
@Recorder
public class YamlGraphRecorder {

    public RuntimeValue<GoalCompiler> createYamlGoalCompiler(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistry,
            Map<String, String> inlineVariables) {

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Class<? extends NodeSpec>> resolvedTypes = resolveTypes(typeRegistry);

        return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
            InjectableInstance<PreferenceProvider> prefInstance =
                    Arc.container().select(PreferenceProvider.class);
            VariableResolver resolver = new VariableResolver(
                    inlineVariables,
                    prefInstance.isResolvable() ? prefInstance.get() : null,
                    ConfigProvider.getConfig());

            List<DesiredNode> nodes = new ArrayList<>();
            for (NodeDescriptor nd : descriptor.nodes()) {
                if (nd instanceof NodeDescriptor.InlineNode in) {
                    String typeName = typeNameForClass.get(in.specClassName());
                    Class<? extends NodeSpec> specClass = resolvedTypes.get(typeName);
                    Map<String, Object> resolved = resolver.resolveMap(in.specValues());
                    NodeSpec spec = mapper.convertValue(resolved, specClass);

                    // Validate @NodeTypeId / nodeType() consistency
                    if (!spec.nodeType().value().equals(typeName)) {
                        throw new IllegalStateException(
                            "@NodeTypeId(\"" + typeName + "\") diverges from nodeType()=\""
                            + spec.nodeType().value() + "\" on " + specClass.getName());
                    }

                    nodes.add(new DesiredNode(NodeId.of(in.id()), spec, in.humanGating()));
                }
            }

            List<Dependency> deps = descriptor.dependencies().stream()
                    .map(dd -> new Dependency(NodeId.of(dd.from()), NodeId.of(dd.to())))
                    .toList();

            return CompilationResult.single(factory.of(nodes, deps));
        });
    }
}
```

### 9.2 Relationship to DesiredStateGraphRecorder

The existing `DesiredStateGraphRecorder` handles `InterfaceNode` and `ClassNode`.
`YamlGraphRecorder` handles `InlineNode`. They are separate recorders because:
- Different dependencies (YAML recorder needs ObjectMapper, VariableResolver)
- Different initialization patterns (YAML needs type registry and variable maps)
- Clean separation — YAML module doesn't modify the annotation module

Both produce `RuntimeValue<GoalCompiler>` registered as CDI beans with
`@DesiredStateQualifier(namespace, name)`.

Both processors also produce `DesiredStateGraphBuildItem` instances. A shared
`@BuildStep` (in `annotations/deployment/`) consumes all instances and validates
no duplicate namespace:name pairs exist across surfaces. This catches the collision
at build time with a clear error message rather than an opaque CDI ambiguous-bean
resolution failure at runtime.

## 10. YAML Pipeline Example

`examples/pipeline-yaml/` declares the same medallion pipeline as
`examples/pipeline-annotated/` but in YAML. Reuses the same NodeSpec
implementations from `examples/pipeline/`.

### 10.1 Structure

```
examples/pipeline-yaml/
├── pom.xml
├── src/main/resources/META-INF/desiredstate/
│   └── medallion-pipeline.yaml
└── src/test/java/.../PipelineYamlTest.java
```

### 10.2 Test Strategy

`PipelineYamlTest` verifies structural equivalence between the YAML-declared graph
and the expected pipeline topology:

1. Load the YAML GoalCompiler bean (qualified by namespace + name)
2. Call `compile(null, factory)` — YAML GoalCompiler is `GoalCompiler<Void>`, goals parameter is always null (the graph is fully declared in YAML, not parameterized by a goals object)
3. Construct the expected graph by instantiating the same NodeSpec records and wiring
   the same dependency edges (the annotation-path GoalCompiler cannot be on the same
   classpath — the cross-surface collision check §8.3 would reject duplicate
   `pipeline:medallion` declarations). Compare:
   - Same node IDs
   - Same node types (`spec.nodeType()` matches)
   - Same dependency edges
   - Same spec field values (record equality)
   - Same humanGating per node
4. Variable substitution test: override a variable via MicroProfile Config,
   verify the spec value changes

### 10.3 NodeSpec Reuse

The pipeline-yaml example adds `@NodeTypeId` annotations to the existing NodeSpec
records in `examples/pipeline/`:

```java
@NodeTypeId("data-source")
public record DataSourceSpec(...) implements NodeSpec { ... }

@NodeTypeId("ingestion")
public record IngestionSpec(...) implements NodeSpec { ... }
```

This demonstrates the design principle: Java NodeSpec records are shared across
surfaces. The YAML surface adds `@NodeTypeId` for discoverability; the annotation
surface continues using `@Node` methods. Since `@NodeTypeId` is in `api/`, the
`examples/pipeline/` module needs no new dependencies.

## 11. Deferred Items — Follow-Up Issues Under #116

| Item | Reason deferred | Follow-up scope |
|------|----------------|-----------------|
| YAML fault policies | Requires ReviewAction aliasing mechanism | New `@ReviewAction` annotation + registry, YAML `faultPolicies:` section |
| YAML rules/invariants | Requires Drools as backend engine | YAML `rules:`/`invariants:` → Drools rule definitions |
| Conditional inclusion | Design challenge (§6 of research doc) | `when:` directive on nodes, resolved at compile time |
| Iterated subgraph | Design challenge (§6 of research doc) | `forEach:` directive, expansion before rule evaluation |
| Module composition | Largest unresolved design area (§7) | Import, namespace, parameter override, packaging |
| REST endpoint | Prove language first, then HTTP surface | Thin HTTP layer over LifecycleManager.updateDesired() |
| Lifecycle hooks | Ansible-concern, coupled to TransitionExecutor | `hooks:` on nodes with pre/post provision steps |

## References

- [casehubio/casehub-desiredstate#116](https://github.com/casehubio/casehub-desiredstate/issues/116) — parent epic
- [casehubio/casehub-desiredstate#117](https://github.com/casehubio/casehub-desiredstate/issues/117) — this issue
- `/Users/mdproctor/claude/casehub/desiredstate/docs/research/2026-08-27-operator-declaration-language-research.md` — research doc §5.1, §5.5, §8, §11
- `annotations/runtime/src/main/java/.../GraphDescriptor.java` — IR record
- `annotations/runtime/src/main/java/.../NodeDescriptor.java` — sealed interface (extended with InlineNode)
- `annotations/runtime/src/main/java/.../DesiredStateGraphRecorder.java` — recorder pattern
- `annotations/deployment/src/main/java/.../DesiredStateAnnotationsProcessor.java` — build extension pattern
- `api/src/main/java/.../NodeSpec.java` — runtime contract
- `api/src/main/java/.../GoalCompiler.java` — compilation interface
- `examples/pipeline-annotated/src/main/java/.../MedallionPipeline.java` — annotation-path reference
- `examples/pipeline/src/main/java/.../DataSourceSpec.java` — NodeSpec implementation pattern
- `runtime/src/main/java/.../DesiredStatePreferenceKeys.java` — Preferences API usage
