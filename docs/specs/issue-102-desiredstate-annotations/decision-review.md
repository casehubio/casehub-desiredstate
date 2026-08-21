# Decision Review — Light Pass

## Summary

All 9 decisions hold. No contradictions found. Two observations worth noting during implementation.

## D1-D2 Cascade: NodeSpec.nodeType() + DesiredNode

Sound. All runtime `.type()` call sites use method invocation, so whether `type()` is a record component or derived method is transparent to callers. The real cascade site is `FaultPolicy.addReviewNode()` — it takes a separate `NodeType reviewType` parameter that becomes redundant once specs carry their own type. The D2 migration should simplify this factory: drop the `reviewType` param and derive it from `specFactory.create(...).nodeType()`. Flag for implementation.

## D4: GoalCompiler<Void>

Sound, but note: `GoalCompiler<G>` has `compile(G goals, DesiredStateGraphFactory factory)`. With `G = Void`, callers must pass `null` as the goals argument. The runtime's `LifecycleManager` resolves the GoalCompiler and calls `compile()` — verify it handles `Void`-typed compilers without NPE on the goals parameter. The generated compiler ignores its input, so this should be safe, but warrants a test.

## D7: @FaultPolicy method references

Workable. Jandex can resolve method names on the annotated interface and validate return types via `MethodInfo.returnType()` — checking it implements `NodeSpec` (and therefore has `nodeType()`) is a standard Jandex type-hierarchy check. The build extension should validate at build time: (1) named method exists on the interface, (2) return type implements NodeSpec, (3) method signature matches `(FaultEvent, DesiredStateGraph)`.

## Gap: no decision on @FaultPolicy `ignoreTypes` and `namespace`

ThresholdFaultPolicy.Builder has `ignoreTypes` and `namespace` — neither appears in D7. Recommend: `ignoreTypes` as optional annotation attribute, `namespace` auto-derived from faultTypes (matching ThresholdFaultPolicy's existing `deriveNamespace` logic). Not blocking — can be added during spec writing.

## Verdict

No revisions needed. Proceed to spec writing.
