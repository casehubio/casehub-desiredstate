---
title: "Invariants, Standalone Rules, and the Quantification Trap"
date: 2026-08-26
author: mdp
entry_type: note
subtype: diary
tags: [annotations, graph-rules, invariants, design-review]
projects: [casehub-desiredstate]
issues: [115, 113, 107]
---

Three issues closed in one branch, all building on the `@GraphRule` infrastructure from the
previous session. The work was straightforward — extend the annotation model with standalone
rule containers, `@Tier(nodeType)` validation, and `@GraphInvariant` assertions — but the
design review caught something that would have been a subtle, hard-to-diagnose bug in production.

## Standalone @GraphRule — include/exclude matching

The original `@GraphRule` only worked on `@DesiredState` interfaces. Standalone rule classes
let you write rules that apply across multiple graphs — a monitoring rule that fires on every
pipeline graph, for instance. The graph matching uses a `.gitignore`-style `!` prefix pattern:

```java
@GraphRule(graph = {"*:*", "!debug:*", "!test:*"})
public class MonitoringRules {
    @GraphRule
    public List<GraphMutation> ensureMonitoring(
            @Match(type = "sink") DesiredNode sink,
            @NotExists(type = "monitor", of = "sink",
                       direction = Direction.DEPENDENTS) Void guard) {
        return GraphMutations.addNodeDependingOn(...);
    }
}
```

Ordered evaluation, last match wins. `graph()` changed from `String` to `String[]` — Java's
single-element annotation shorthand means existing `@GraphRule(graph = "exact:match")` code
still compiles without changes.

## The quantification trap

Here's what the design review caught. I'd specified that `@GraphInvariant` uses "binding is
assertion" — if the pattern can't bind, that's the violation. The algorithm I wrote checked
whether *any* binding tuple survived. If at least one did, the invariant passed.

That's existential quantification. The invariant "every sink must have an upstream" needs
universal quantification — *each* anchor independently must successfully expand.

Consider a graph with three sinks. Sink1 and sink2 have upstream data sources. Sink3 doesn't.
Under existential semantics, two tuples survive, the check says "at least one survived," and
the invariant passes. Sink3's missing upstream goes undetected.

The fix: the invariant engine evaluates per anchor tuple. For each `@Match` binding, it
independently expands the remaining parameters. If *any* anchor fails to expand, that
specific anchor produces a violation. Empty `@Match` cross-product is vacuously true — an
invariant about sinks passes when there are no sinks.

This distinction between rules and invariants is why we ended up with a separate
`GraphInvariantEngine` rather than bolting a mode flag onto `GraphRuleEngine`. The evaluation
algorithms genuinely differ. We extracted the shared pattern matching primitives
(`resolveReference`, `findDirectNeighbors`, `findReachable`, BFS, cross-product — about 80
lines) into `PatternMatchingSupport` so both engines use the same matching logic with
different evaluation semantics.

## @Tier(nodeType)

The smallest of the three changes. `@Tier` now accepts an optional `nodeType` attribute:

```java
@Tier(threshold = 3, review = "createAiReview", nodeType = "ai-review")
```

When present, the recorder skips the runtime `ReviewSpecFactory` probe — no synthetic
`FaultEvent`, no null graph, no reflective invocation just to discover a type that was
known at annotation time. When absent, the existing probe continues to work.

## What this opens up

`@GraphInvariant` is the validation layer the annotation model was missing. Rules rewrite
the graph; invariants assert structural properties of the result. The natural next step is
`#107`'s companion — `@GraphInvariant` on standalone classes, which already works through
the same `GraphPatternMatcher` infrastructure. Beyond that, the pattern matching engine is
now positioned for the invariant-as-documentation path: invariants that describe the graph's
structural contracts in a way that's both executable and readable.
