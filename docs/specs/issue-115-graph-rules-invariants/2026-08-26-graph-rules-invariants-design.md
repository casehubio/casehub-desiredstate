# Annotation Infrastructure Extensions — Standalone Rules, Invariants, Tier Validation

**Date:** 2026-08-26
**Issues:** casehubio/casehub-desiredstate#115, #113, #107
**Status:** Draft

## Motivation

Issue #106 delivered the core @GraphRule infrastructure: interface-scoped rules with
parameterized pattern matching and a fixed-point engine. Three follow-up capabilities
remain:

1. **Standalone rule containers (#115)** — classes annotated with `@GraphRule` whose rules
   apply across multiple graphs via include/exclude matching
2. **@Tier(nodeType) (#113)** — build-time validation that a fault policy tier's review
   method produces the declared node type, eliminating the runtime probe
3. **@GraphInvariant (#107)** — declarative graph validation rules that run after
   @GraphRule convergence, asserting structural properties of the final graph

Additionally, the @GraphRule parameter validation specified in the #106 design spec
(Part 6) is largely unimplemented — only static method and return type checks exist.
This branch completes that validation for both interface and standalone rules.

---

## Part 1: @GraphRule Standalone Class Discovery (#115)

### Annotation changes

`@GraphRule.graph()` changes from `String` to `String[]` to support include/exclude
patterns (D6):

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface GraphRule {
    String[] graph() default {};
}
```

Empty array (default) is valid on methods (graph scoped by declaring class or interface).
Empty array on a standalone class is a build-time error.

### Graph matching semantics (D5)

Entries in `graph()` use ordered evaluation with last-match-wins:

- Plain entries are includes: `"pipeline:*"`, `"pipeline:medallion"`, `"*:*"`
- `!`-prefixed entries are excludes: `"!debug:*"`, `"!pipeline:debug"`
- Wildcards: `"pipeline:*"` matches any graph with namespace `pipeline`. `"*:*"` matches
  all graphs.
- At least one non-`!` entry is required (build-time error otherwise).

```java
// All graphs except debug and test namespaces
@GraphRule(graph = {"*:*", "!debug:*", "!test:*"})
public class MonitoringRules { ... }

// Pipeline namespace, exclude one graph
@GraphRule(graph = {"pipeline:*", "!pipeline:debug"})
public class PipelineRules { ... }

// All except internal, but re-include internal:monitoring
@GraphRule(graph = {"*:*", "!internal:*", "internal:monitoring"})
public class CrossCuttingRules { ... }

// Exact match (existing behavior, now expressed as single-element array)
@GraphRule(graph = "pipeline:medallion")
public class MedallionRules { ... }
```

### Matching algorithm

```
matchesGraph(patterns[], candidateKey):
    result = false
    for each pattern in patterns:
        if pattern starts with "!":
            if matches(pattern[1:], candidateKey):
                result = false
        else:
            if matches(pattern, candidateKey):
                result = true
    return result

matches(pattern, key):
    if pattern == "*:*": return true
    if pattern ends with ":*":
        return key starts with pattern[:-1]  // namespace match
    return pattern == key  // exact match
```

### Processor changes

`DesiredStateAnnotationsProcessor.generateDesiredStateGraphs()` adds a new scan phase
after interface processing:

```java
Map<String[], List<GraphRuleDescriptor>> standaloneRules = scanStandaloneGraphRules(index);
```

For each `@DesiredState` interface's graph key, standalone rules whose `graph()` pattern
matches are merged into that graph's `GraphDescriptor.graphRules()`. A standalone rule
class may match multiple graphs — its descriptors are added to each matching graph.

```java
private Map<String[], List<GraphRuleDescriptor>> scanStandaloneGraphRules(IndexView index) {
    Map<String[], List<GraphRuleDescriptor>> byPatterns = new LinkedHashMap<>();
    for (AnnotationInstance grAnn : index.getAnnotations(GRAPH_RULE)) {
        if (grAnn.target().kind() != AnnotationTarget.Kind.CLASS) continue;
        ClassInfo classInfo = grAnn.target().asClass();
        String[] graphPatterns = grAnn.value("graph").asStringArray();

        List<GraphRuleDescriptor> rules = new ArrayList<>();
        for (MethodInfo method : classInfo.methods()) {
            if (method.hasAnnotation(GRAPH_RULE) && isPublicNonStatic(method)) {
                rules.add(buildGraphRuleDescriptor(method, index, classInfo.name().toString()));
            }
        }
        if (!rules.isEmpty()) {
            byPatterns.put(graphPatterns, rules);
        }
    }
    return byPatterns;
}
```

### Standalone class validation

Added to `AnnotationValidationStep.validate()`:

| Check | Error message |
|-------|---------------|
| Class not concrete | `@GraphRule class PipelineRules must be concrete with a no-arg constructor` |
| No no-arg constructor | Same as above |
| Empty graph attribute | `@GraphRule on class PipelineRules requires graph attribute` |
| No non-`!` entries in graph | `@GraphRule on class PipelineRules graph has no include patterns — at least one non-! entry required` |
| Non-public method with @GraphRule | `@GraphRule on 'ensureMonitor' in PipelineRules must be public` |
| Graph pattern matches no declared graph | Warning: `@GraphRule class PipelineRules graph 'foo:bar' does not match any declared graph` |

### Recorder changes

`resolveRules()` already handles standalone classes — line 133-135 checks `isInterface`,
creates instance via `newInstance()` for concrete classes. No changes needed.

---

## Part 2: @Tier(nodeType) Build-Time Validation (#113)

### Annotation change

Add optional `nodeType` attribute to `@Tier`:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Tier {
    int threshold();
    String review();
    String nodeType() default "";
}
```

### Build-time validation

In `AnnotationValidationStep.validateTierReviewMethods()`, when `nodeType` is non-empty:

1. Verify the review method's return type implements `NodeSpec`  (existing check)
2. Verify the return type's `nodeType()` method returns a value matching the declared
   `nodeType` string — instantiate the NodeSpec via Jandex metadata to check, or validate
   structurally that the return type has a `nodeType()` default method returning the
   declared value

Build-time validation confirms `nodeType` is a non-empty string when present. The
semantic match — that the review method's returned NodeSpec produces a matching
`nodeType()` — is validated at runtime init in the recorder, where the NodeSpec can be
instantiated. Build-time catches syntax; runtime catches semantics.

### Recorder changes

In `DesiredStateGraphRecorder.createFaultPolicy()`, when processing tier descriptors:

- If `nodeType` is present: use `NodeType.of(tierDescriptor.nodeType())` directly — skip
  the `ReviewSpecFactory.nodeType()` probe
- If `nodeType` is absent: fall back to existing `ReviewSpecFactory.nodeType()` default
  probe (calling the factory with a synthetic FaultEvent)

### FaultPolicyDescriptor / TierDescriptor changes

Add `nodeType` field to `TierDescriptor` (or whichever IR record carries tier data):

```java
public record TierDescriptor(
    int threshold,
    String reviewMethodName,
    String nodeType  // empty string = absent, probe at runtime
) {}
```

---

## Part 3: @GraphInvariant — Declarative Graph Validation (#107)

### Annotation

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface GraphInvariant {
    String[] graph() default {};  // same semantics as @GraphRule (D7)
}
```

On `ElementType.TYPE`: marks a standalone invariant container class. `graph` attribute
scopes all invariants to matching graphs. Same include/exclude matching as @GraphRule (D7).

On `ElementType.METHOD`: marks a method as a graph invariant.
- On `@DesiredState` interfaces: must be `static`. Graph scoped to declaring interface.
- On standalone classes: must be `public`. Graph inherited from class-level annotation.

### Programming model

**Parameterized invariants** — pattern binding is the assertion (D3). If the pattern
can't bind (no matching tuples), that's the violation. Method body can throw
`GraphViolationException` for custom value checks. Full pattern vocabulary supported:
@Match, @DirectDep, @Reaches, @NotExists (D2).

```java
@DesiredState(namespace = "pipeline", name = "medallion")
public interface MedallionPipeline {

    // Every sink must have an upstream dependency — empty binding = violation
    @GraphInvariant
    static void sinkMustHaveUpstream(
            @Match(type = "sink") DesiredNode sink,
            @DirectDep(type = "data-source", of = "sink",
                       direction = Direction.DEPENDENCIES) DesiredNode upstream) {
    }

    // Imperative: full graph inspection
    @GraphInvariant
    static void noOrphanedSinks(DesiredStateGraph graph) {
        for (var entry : graph.nodes().entrySet()) {
            if (entry.getValue().type().equals(NodeType.of("sink"))
                    && graph.dependenciesOf(entry.getKey()).isEmpty()) {
                throw new GraphViolationException(
                    "Sink " + entry.getKey() + " has no upstream dependency");
            }
        }
    }
}
```

**Standalone invariant classes:**

```java
@GraphInvariant(graph = {"pipeline:*"})
public class PipelineInvariants {

    @GraphInvariant
    public void everyTransformerReachesASink(
            @Match(type = "transformer") DesiredNode transformer,
            @Reaches(type = "sink", of = "transformer",
                     direction = Direction.DEPENDENTS) DesiredNode sink) {
        // binding success = invariant holds
    }
}
```

### Exception types

```java
public class GraphViolationException extends RuntimeException {
    private final String invariantName;
    private final List<NodeId> affectedNodes;

    // single violation constructor
    public GraphViolationException(String message) { ... }

    // with node context
    public GraphViolationException(String message, NodeId... nodes) { ... }
}

public class GraphInvariantViolationsException extends RuntimeException {
    private final List<GraphViolation> violations;
    // thrown by the engine, aggregating all violations
}

public record GraphViolation(
    String invariantName,
    String sourceClassName,
    String message,
    List<NodeId> affectedNodes
) {}
```

`GraphViolationException` is thrown by invariant method bodies (developer-facing).
`GraphInvariantViolationsException` is thrown by the engine after collecting all
violations across all invariants (aggregated, not fail-fast).

### GraphInvariantEngine (D1)

Separate engine in `annotations/runtime`. Single-pass evaluation — no fixed-point loop,
no mutation application, no conflict detection. Shares stateless pattern matching
primitives with `GraphRuleEngine` via extracted `PatternMatchingSupport` utility class
(~79 lines: resolveReference, findDirectNeighbors, findReachable, existsGlobal,
existsRelational, getParameterNames, crossProduct). The evaluation logic (~87 lines)
is engine-specific — rules and invariants have fundamentally different algorithms.

```java
public class GraphInvariantEngine {

    public void validate(DesiredStateGraph graph, List<ResolvedGraphInvariant> invariants) {
        List<GraphViolation> violations = new ArrayList<>();

        for (ResolvedGraphInvariant invariant : invariants) {
            if (invariant.imperative()) {
                validateImperative(invariant, graph, violations);
            } else {
                validateParameterized(invariant, graph, violations);
            }
        }

        if (!violations.isEmpty()) {
            throw new GraphInvariantViolationsException(violations);
        }
    }
}
```

**Parameterized evaluation — universal quantification:**

Invariants require universal quantification: "for ALL @Match anchors, the pattern must
hold." This is fundamentally different from @GraphRule's evaluation, where a surviving
tuple fires a mutation (existential — "when any match exists, fire").

Algorithm:
1. Enumerate @Match cross-product (all anchor tuples)
2. If @Match cross-product is empty → vacuously true (no violation). An invariant
   "every sink must have upstream" passes when there are no sinks.
3. For each anchor tuple independently:
   a. Expand remaining parameters (@DirectDep, @Reaches, @NotExists) with the anchor bound
   b. If zero fully-expanded tuples survive for this anchor → structural violation for
      this specific anchor (e.g., "sink 'csv-sink' has no upstream data-source dependency")
   c. For each surviving tuple, invoke the method with bound nodes
   d. If the method throws `GraphViolationException` → value violation for this tuple
   e. If the method completes normally → invariant holds for this tuple
4. Collect all violations across all anchors, report together (not fail-fast)

Example: graph with sink1 (has upstream), sink2 (has upstream), sink3 (no upstream):
- @Match produces 3 anchor tuples
- sink1: @DirectDep finds data-source → expands → method invoked → holds
- sink2: @DirectDep finds data-source → expands → method invoked → holds
- sink3: @DirectDep finds nothing → zero expanded tuples → structural violation

**@NotExists in invariants:**

@NotExists interacts with "binding is assertion" by inverting the assertion direction.
With @NotExists, a surviving binding means the negated pattern holds — "this thing does
NOT exist," which IS the assertion. This creates negated invariants:

```java
// "No transformer should have a redundant validator" (negative assertion)
@GraphInvariant
static void noRedundantValidators(
        @Match(type = "transformer") DesiredNode transformer,
        @NotExists(type = "validator", of = "transformer",
                   direction = Direction.DEPENDENTS) Void guard) {
    // Binding survives when NO validator exists → invariant holds
    // If a validator IS found → tuple filtered out → structural violation
}
```

Contrast with the positive assertion pattern using @DirectDep/@Reaches:

```java
// "Every sink MUST have an upstream" (positive assertion)
@GraphInvariant
static void sinkMustHaveUpstream(
        @Match(type = "sink") DesiredNode sink,
        @DirectDep(type = "data-source", of = "sink",
                   direction = Direction.DEPENDENCIES) DesiredNode upstream) {}
```

**Imperative evaluation:**
1. Invoke the method with the full graph
2. If it throws `GraphViolationException` → violation
3. If it completes normally → invariant holds

**Scope limitation — compile-time only:**

Invariants validate the desired graph at compilation time (Quarkus runtime init), before
reconciliation starts. Runtime fault mutations that modify the graph via
`ReconciliationLoop.compareAndSetDesired()` bypass invariant checking. This is deliberate:
invariants are graph construction constraints, not runtime structural monitors. Runtime
structural monitoring is the domain of RAS Ganglia (#64). If runtime invariant checking
is needed in the future, it would be a separate `ReconciliationListener` that re-validates
after mutations — not an extension of the compile-time engine.

**Scope limitation — per-phase only:**

For `CompilationResult.Lifecycle`, invariants validate each phase graph independently.
Cross-phase structural assertions ("phase N+1 must be a superset of phase N for
stability-typed nodes") cannot be expressed with per-phase validation. This is the right
starting point — cross-phase invariants can be added later if lifecycle examples demand it.

### IR types

```java
public record GraphInvariantDescriptor(
    String methodName,
    boolean imperative,
    List<PatternParameterDescriptor> patterns,
    String sourceClassName
) {}

record ResolvedGraphInvariant(
    String name,
    Method method,
    Object instance,
    boolean imperative,
    List<PatternParameterDescriptor> patterns
) {}
```

`PatternParameterDescriptor` and `PatternKind` are reused from the @GraphRule IR — they
live in `annotations/runtime` and are pattern-vocabulary types, not rule-specific.

### GraphDescriptor extension

```java
public record GraphDescriptor(
    String namespace,
    String name,
    String interfaceName,
    String implClassName,
    List<NodeDescriptor> nodes,
    List<DependencyDescriptor> dependencies,
    List<FaultPolicyDescriptor> faultPolicies,
    GoalMethodDescriptor goalMethod,
    List<GraphRuleDescriptor> graphRules,
    List<GraphInvariantDescriptor> graphInvariants  // NEW — 10th component
) {}
```

### Pipeline integration

Invariants slot after @GraphRule convergence:

```
Discover @Node / @DeclareNode → Assemble initial graph → Resolve dependencies
  → Apply @Customize(graph) methods
  → Apply @GoalMethod (if present)
  → Fire @GraphRule methods (fixed-point loop)
  → Validate @GraphInvariant methods (single pass)          ← NEW
  → Emit final DesiredStateGraph
```

For `CompilationResult.Lifecycle`, invariants validate each phase graph independently
(same as rules — per the parent spec Part 6).

### Recorder integration

In `DesiredStateGraphRecorder.createGoalCompiler()`, after rule application (mirrors
the existing rule-wrapping pattern at lines 96-102):

```java
if (!descriptor.graphInvariants().isEmpty()) {
    List<ResolvedGraphInvariant> resolved = resolveInvariants(descriptor.graphInvariants());
    GraphInvariantEngine invariantEngine = new GraphInvariantEngine();
    GoalCompiler inner = runtimeValue.getValue();
    runtimeValue = new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
        CompilationResult result = inner.compile(goals, factory);
        validateInvariantsOnResult(result, resolved, invariantEngine);
        return result;
    });
}
```

`validateInvariantsOnResult` mirrors `applyGraphRulesToResult` — for `SingleGraph`,
validates the graph directly; for `Lifecycle`, validates each phase graph independently.

This wrapping ensures invariants validate every `compile()` call, including the
@GoalMethod path where rules already wrap the compiler.

### Processor integration

Same as @GraphRule:
1. Scan @GraphInvariant methods on @DesiredState interfaces (static methods only)
2. Scan standalone @GraphInvariant classes via Jandex
3. Match standalone invariants to graphs via `graph` attribute (same matching as D5/D7)
4. Build `GraphInvariantDescriptor` instances and add to `GraphDescriptor`

---

## Part 4: @GraphRule Parameter Validation Completion

Complete the validation checks specified in #106 spec Part 6 but not yet implemented
in `AnnotationValidationStep`. These apply to both interface and standalone @GraphRule
methods, and identically to @GraphInvariant methods.

| Check | Error message |
|-------|---------------|
| @NotExists with `of` but no explicit direction | `@NotExists on parameter 'guard' specifies 'of' without explicit direction — DEPENDENCIES and DEPENDENTS have opposite semantics; specify direction` |
| @DirectDep/@Reaches as first param with no `of` | `@DirectDep on parameter 'ingest' uses sequential chaining but has no preceding parameter — use @Match as the first parameter or specify 'of' explicitly` |
| `of` references unknown parameter name | `@DirectDep 'of' references 'foo' — no parameter named 'foo' in ensureValidator` |
| @Match type not in any @Node/@DeclareNode | Warning: `@Match type 'unknown-type' not found in any @Node or @DeclareNode declaration` |
| Imperative method first param not DesiredStateGraph | `@GraphRule 'rebalance' imperative method first parameter must be DesiredStateGraph` |
| @GraphInvariant non-void parameterized return | `@GraphInvariant 'sinkCheck' parameterized method must return void` |

The first three checks require walking the parameter list in order, tracking parameter
names and their annotation kinds. This is a validation pass over the same data
`buildPatternForParameter` processes — but in the validation step, not the processor.

---

## Part 5: Testing Strategy

### Unit tests (`annotations/runtime/`)

**GraphInvariantEngineTest:**
- Parameterized invariant with @Match — binding succeeds, invariant holds
- Parameterized invariant with @Match — no matching nodes, structural violation
- Parameterized invariant with @DirectDep — binding succeeds
- Parameterized invariant with @Reaches — transitive check holds
- Parameterized invariant with @NotExists guard — correctly prevents invocation
- Method throws GraphViolationException — value violation collected
- Multiple invariants — all violations collected (not fail-fast)
- Imperative invariant — full graph access, no violation
- Imperative invariant — throws GraphViolationException
- Empty invariant list → no exception
- Named binding via `of` in invariant patterns

### Build extension tests (`annotations/deployment/`)

**StandaloneGraphRuleProcessorTest:**
- Standalone @GraphRule class with exact graph match → rules fire
- Standalone @GraphRule class with namespace wildcard → rules fire on matching graphs
- Standalone @GraphRule class with `!` exclusion → rules skip excluded graphs
- Standalone + interface rules merged → both fire
- `"*:*"` with `!` exclusions → correct graph set
- Re-include after exclude → last match wins

**GraphInvariantProcessorTest:**
- Interface @GraphInvariant — structural violation detected
- Interface @GraphInvariant — imperative violation detected
- Interface @GraphInvariant — invariant holds, graph compiles
- Standalone @GraphInvariant class with graph matching
- @GoalMethod returning Lifecycle → invariants validate each phase
- @GraphRule + @GraphInvariant — rules converge, then invariants validate

**ParameterValidationTest:**
- @NotExists with `of` but no direction → build error
- @DirectDep as first param with no `of` → build error
- `of` references unknown parameter → build error
- @Match type not declared → build warning
- Imperative method wrong first param type → build error
- @GraphInvariant non-void parameterized return → build error

**TierNodeTypeTest:**
- @Tier with nodeType → build validates against review method return type
- @Tier without nodeType → existing probe behavior preserved
- @Tier with wrong nodeType → build error

### Integration test (`examples/pipeline-annotated/`)

- Add standalone @GraphRule class targeting `pipeline:*` namespace
- Add @GraphInvariant on MedallionPipeline interface
- Add @Tier(nodeType) to existing fault policy
- Verify all three features work together in a compiled graph

---

## References

- [DesiredStateAnnotationsProcessor.java](/Users/mdproctor/claude/casehub/desiredstate/annotations/deployment/src/main/java/io/casehub/desiredstate/annotations/deployment/DesiredStateAnnotationsProcessor.java) — processor (extended by Parts 1, 3, 4)
- [AnnotationValidationStep.java](/Users/mdproctor/claude/casehub/desiredstate/annotations/deployment/src/main/java/io/casehub/desiredstate/annotations/deployment/AnnotationValidationStep.java) — validation (extended by Parts 1, 3, 4)
- [DesiredStateGraphRecorder.java](/Users/mdproctor/claude/casehub/desiredstate/annotations/runtime/src/main/java/io/casehub/desiredstate/annotations/runtime/DesiredStateGraphRecorder.java) — recorder (extended by Parts 1, 2, 3)
- [GraphRuleEngine.java](/Users/mdproctor/claude/casehub/desiredstate/annotations/runtime/src/main/java/io/casehub/desiredstate/annotations/runtime/GraphRuleEngine.java) — pattern matching engine (duplicated for invariants, Part 3)
- [Tier.java](/Users/mdproctor/claude/casehub/desiredstate/annotations/runtime/src/main/java/io/casehub/desiredstate/annotations/Tier.java) — @Tier annotation (extended by Part 2)
- [GraphRule.java](/Users/mdproctor/claude/casehub/desiredstate/annotations/runtime/src/main/java/io/casehub/desiredstate/annotations/GraphRule.java) — @GraphRule annotation (modified by Part 1)
- [#106 graph-rule design spec](/Users/mdproctor/claude/casehub/desiredstate/docs/specs/issue-106-graph-rule/2026-08-24-graph-rule-design.md) — parent spec, Parts 3 and 6
- [#112 TypedFaultPolicy design spec](/Users/mdproctor/claude/casehub/desiredstate/docs/specs/issue-112-reviewnodepolicy-tier/2026-08-24-typedfaultpolicy-design.md) — FaultPolicy context for Part 2
- decisions.md — 7 design decisions with rationale
