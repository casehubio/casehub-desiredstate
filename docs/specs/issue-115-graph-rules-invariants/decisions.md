## D1: @GraphInvariant engine architecture

**Choice:** Separate GraphInvariantEngine with shared PatternMatchingSupport utility
**Alternatives:**
- Mode flag on GraphRuleEngine — avoids duplication but interleaves two distinct control flows (mutate-and-loop vs validate-and-collect) in a 361-line class
- Full duplication with no shared code — simplest but ~166 lines duplicated, sync maintenance burden on the most bug-prone code (expandChain, BFS)
**Rationale:** Rules produce mutations in a fixed-point loop with existential quantification ("any match → fire"). Invariants produce violations in a single pass with universal quantification ("every anchor must succeed"). The evaluation algorithms genuinely differ — separate engines are necessary, not just convenient. Stateless pattern matching primitives (~79 lines: resolveReference, findDirectNeighbors, findReachable, existsGlobal, existsRelational, getParameterNames, crossProduct) are extracted to a shared utility. Evaluation-specific code (~87 lines) stays in each engine.
**Trade-offs:** One additional utility class. Future changes to matching primitives apply once. Evaluation logic changes are engine-specific — no coupling.
**Sources:** GraphRuleEngine.java (annotations/runtime), issue #107 body
**Exploration:** quick
**Status:** captured

## D2: @GraphInvariant pattern vocabulary

**Choice:** Full vocabulary — @Match, @DirectDep, @Reaches, @NotExists all supported
**Alternatives:**
- @Match + @NotExists only — simpler but forces imperative fallback for structural invariants like "every sink reachable from a source must have a validator"
- Full + new @Exists annotation — adds vocabulary but @NotExists already handles the inverse semantics for invariants (empty binding = violation)
**Rationale:** Restricting the vocabulary means some invariants require imperative fallback for no good reason. @Reaches is needed for transitive structural assertions.
**Trade-offs:** More pattern matching code to duplicate in the invariant engine.
**Sources:** Issue #107 body, GraphRuleEngine pattern matching implementation
**Exploration:** quick
**Status:** captured

## D3: @GraphInvariant parameterized signature

**Choice:** Void return, binding is assertion — if the pattern can't bind (no matching tuples), that's the violation. Method body can add custom checks via throw GraphViolationException.
**Alternatives:**
- Boolean return — pattern binding produces tuples, method returns true/false per tuple. Empty binding set passes by default, which inverts the natural "must exist" semantics.
- List<GraphViolation> return — most flexible but heaviest signature
**Rationale:** Clean separation: pattern = structural assertion (must bind), body = value assertion (custom checks). Matches the issue's design ("empty binding IS the violation — no if-statement needed").
**Trade-offs:** No way to express "this pattern should NOT bind" with parameterized invariants — use imperative for negative assertions. But @NotExists already covers that case in patterns.
**Sources:** Issue #107 body
**Exploration:** quick
**Status:** captured

## D4: Complete @GraphRule parameter validation

**Choice:** Include missing parameter validation from #106 spec in this branch
**Alternatives:**
- Separate issue — keep scope to what #115, #113, #107 describe. But this leaves interface rules under-validated while adding standalone validation.
**Rationale:** Adding standalone class validation (#115) without also validating parameter annotations leaves the existing interface rules under-validated. The checks are specified in the #106 spec Part 6 and belong to the @GraphRule feature.
**Trade-offs:** Slightly larger branch scope.
**Sources:** #106 spec Part 6 validation table, AnnotationValidationStep.java (2 of 6 applicable interface error checks implemented; 3 standalone-specific checks are new with this branch)
**Exploration:** quick
**Status:** captured

## D5: Standalone @GraphRule graph matching — wildcard and include/exclude

**Choice:** `graph` attribute is `String[]` with `!` prefix for exclusion, ordered evaluation, last match wins. Supports `"*:*"` (global), `"pipeline:*"` (namespace-scoped), exact `"pipeline:medallion"`, and `!`-prefixed exclusions. At least one non-`!` entry required.
**Alternatives:**
- Namespace wildcards only, no `"*:*"` — too restrictive when the graph set is large and exclusions are few
- Separate `graph` and `exclude` attributes — only works in one direction (include then exclude). The `!` pattern handles both directions: start big and exclude, or start specific and add.
**Rationale:** The `!` prefix pattern (.gitignore, ACL rules) is well-understood and handles both directions: `{"*:*", "!debug:*"}` (all except debug) and `{"pipeline:*", "!pipeline:debug", "pipeline:debug-monitor"}` (namespace minus one, plus re-include). Ordered evaluation with last-match-wins gives full control.
**Trade-offs:** Slightly more complex matching logic in the processor. `"*:*"` without exclusions is valid (not a build error) — trust the developer.
**Sources:** Issue #115 body, #106 spec Part 6
**Exploration:** quick
**Status:** captured

## D6: @GraphRule `graph` attribute changes from String to String[]

**Choice:** Change `@GraphRule.graph()` from `String` to `String[]` to support include/exclude patterns
**Alternatives:**
- Keep `String` and add separate `exclude` String[] — two attributes for one concept, only supports include-first
**Rationale:** D5 requires array semantics for ordered evaluation. Single `String` can't express `{"*:*", "!debug:*"}`. The existing default `""` (interface-scoped) becomes `{}` (empty array, same semantics — graph attribute ignored on interfaces).
**Trade-offs:** Breaking change to the annotation API. No existing consumers use standalone `@GraphRule` classes yet (feature is new), so migration cost is zero.
**Depends on:** D5 (wildcard semantics)
**Sources:** @GraphRule annotation definition
**Exploration:** quick
**Status:** captured

## D7: @GraphInvariant standalone class matching — same mechanism as @GraphRule

**Choice:** `@GraphInvariant` at class level uses the same `String[] graph()` with `!` prefix semantics as `@GraphRule`. `@GraphInvariant` on methods has no `graph` attribute (inherited from class or interface).
**Alternatives:**
- Different matching mechanism — no justification for divergence when the use case is identical
**Rationale:** Consistency. One matching mechanism to learn, one implementation to maintain. The issue states "where invariants live: same as @GraphRule."
**Trade-offs:** Inherits D5's include/exclude matching complexity. Consistency is the right choice — diverging would be worse — but the mechanism itself carries D5's trade-offs.
**Sources:** Issue #107 body
**Exploration:** quick
**Status:** captured

## D8: GraphViolationException module placement

**Choice:** `annotations/runtime/` — co-located with GraphInvariantEngine and annotations
**Alternatives:**
- `api/` — available to programmatic GoalCompiler users, but pollutes the core API with annotation-specific exception types
**Rationale:** Users writing invariant methods already depend on `casehub-desiredstate-annotations` for @GraphInvariant. The exception is thrown in invariant method bodies — same module boundary. No new dependency required.
**Trade-offs:** Programmatic GoalCompiler users who want to validate graphs structurally would need to depend on the annotations module. This is unlikely — programmatic users have full graph access for validation.
**Sources:** Decision review R1-09
**Exploration:** quick
**Status:** captured
