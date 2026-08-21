# Spec Review — Standard (4 dimensions)

## 1. Structure

**Major — @FaultPolicy on void method is ambiguous.** The example places `@FaultPolicy` on
`default void goldTierFaults() {}` — a non-`@Node` method used purely as an annotation anchor.
The spec says `@FaultPolicy` targets `METHOD` and `TYPE` but never defines what a non-`@Node`
method with `@FaultPolicy` means. Recommendation: either (a) restrict method-level `@FaultPolicy`
to `@Node`-annotated methods only and use `TYPE` for cross-type policies, or (b) explicitly
document that any method — even a void non-`@Node` one — can be a policy anchor when `nodeTypes`
is specified. Option (a) is cleaner.

**Major — addReviewNode review ID derivation after D1-D2.** Current `addReviewNode` derives the
review node ID as `reviewType.value() + "-" + event.node().value()`. After removing the
`reviewType` parameter, the ID must come from `specFactory.create(...).nodeType().value()`.
But this means the factory is called *twice* per fault — once for the ID check, once for the
node creation. The spec should clarify: call the factory once, cache the result, derive ID from it.

**Minor — GoalCompiler<Void> CDI typing.** `GoalCompiler<G>` is generic. CDI erases `<Void>` at
runtime — `@Inject GoalCompiler<Void>` won't resolve without a qualifier or `TypeLiteral`.
The spec mentions CDI qualifier by `@DesiredState(namespace, name)` but doesn't define the
qualifier annotation. Engine-annotations solves this by registering `CaseDefinition` (non-generic).
Recommendation: the generated bean could register as the raw `GoalCompiler` type with a
`@DesiredStateQualifier` annotation, or use a non-generic wrapper.

## 2. Coherence

**No issues.** Module structure mirrors engine-annotations. Annotation naming is consistent
with platform conventions. The NodeSpec.nodeType() cascade through DesiredNode/FaultPolicy/
GraphMutation is complete — `addReviewNode` creates `DesiredNode(id, type, spec, gating)` which
becomes `DesiredNode(id, spec, gating)` cleanly since `type` was only used for the review node
ID derivation. Epic design spec lists desiredstate-annotations as a child of blocks#115 — no conflict.

## 3. Robustness

**Minor — Empty @DesiredState.** Spec doesn't say what happens with zero `@Node` methods.
Recommendation: emit a build-time warning (not error) — an empty graph is legal but likely
a mistake.

**Minor — @FaultPolicy precedence.** If both interface-level and method-level `@FaultPolicy`
match the same node type with different tiers, which wins? Recommendation: method-level wins
(more specific), interface-level is fallback. Document this.

**OK — Circular deps.** Detected at build time per §2.5 validation table. Correct.

**OK — @Customize with DesiredStateGraph.** `ImmutableDesiredStateGraph` returns new instances
from mutations, so `@Customize` receiving a graph must use `withNode()` etc. and the recorder
must use the returned graph. This is implicit but follows from the existing immutable API.

## 4. Crosscutting

**Minor — Pipeline-annotated example doesn't show @Customize.** §Part 3 lists the demonstrated
features but omits @Customize. Recommendation: add a @Customize example to the pipeline-annotated
module (e.g., setting a custom FaultCountStore on the policy builder).

**OK — Part 1/Part 2 ordering.** Clear dependency: Part 1 must land first since annotations
depend on NodeSpec.nodeType(). Testing strategy covers all annotation types.

## Summary

| Severity | Count | Blockers |
|----------|-------|----------|
| Major    | 2     | @FaultPolicy void-method ambiguity, addReviewNode double-call |
| Minor    | 3     | CDI typing, empty graph, precedence, example coverage |

Recommendation: Address the 2 majors in the spec before planning. Both are spec clarifications,
not redesigns.
