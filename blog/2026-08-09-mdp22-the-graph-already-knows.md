---
layout: post
title: "The graph already knows"
date: 2026-08-09
entry_type: note
subtype: diary
projects: [CaseHub Desired State]
tags: [fault-policy, escalation, graph-structure, desired-state]
---

The desired-state runtime has had `ThresholdFaultPolicy` since early on — count faults per node, fire an action at a threshold. One tier. The pipeline example needed three tiers (retry → AI review → human review), so `ProvisionEscalationFaultPolicy` hand-rolled the whole thing: 85 lines of stateful logic checking `PipelineWorld` for review resolution state, with hardcoded ID prefixes and domain-coupled escalation decisions.

The multi-tier extension was straightforward enough — add a `Tier` record, let the builder accept multiple tiers with ascending thresholds, evaluate highest-first with first-match-wins. But the interesting question was the escalation guard: how does tier N+1 know that tier N was attempted?

The naive answer is external state. That's what `ProvisionEscalationFaultPolicy` did — it asked `PipelineWorld` whether the AI review had resolved. Domain-specific, not reusable, and it meant every domain that wanted escalation had to build its own state tracker.

The answer that fell out of the design: the graph already knows. If review nodes carry a dependency edge to the faulted node (which they should — it's correct for removal ordering), then `dependentsOf(faultedNode)` returns every review node attached to it. Check the `NodeType` of those dependents and you know exactly which tiers have fired. No external state, no ID conventions, no domain coupling. The graph's own dependency structure IS the escalation state.

This required one prerequisite: `addReviewNode` had to actually create the dependency edge. It never did — every fault policy in the codebase added review nodes as structurally disconnected islands. Fixing that was straightforward: `GraphMutations.addNodeDependingOn()` returns `[AddNode, AddDependency]`, and `addReviewNode` now calls it instead of bare `List.of(new AddNode(...))`.

The design review caught something I'd missed. `ImmutableDesiredStateGraph.withDependency()` threw `DanglingDependencyException` when either endpoint node was absent. That was fine when fault policies only returned `AddNode` mutations — adding a node to any graph version is idempotent. But `AddDependency` isn't. The reconciliation loop's CAS retry mechanism assumes mutations are safely re-applicable to any graph version. If the faulted node gets removed between evaluation and CAS application, the `AddDependency` targets a ghost. The fix was clean: `withDependency` returns `this` (no-op) on missing endpoints, while the factory retains strict validation at construction time. Two call sites, two different safety contracts, split at the right boundary.

The review also caught lingering review nodes — resolved reviews that stay in the graph as documented debt. If an AI review resolves but its node lingers, the next round of faults sees it via `dependentsOf`, thinks tier 1 was attempted and unresolved, and escalates to human review incorrectly. The spec now requires node removal as part of resolution. The right answer — the debt item was always wrong, this just made the cost visible.

The pipeline example's 85 lines collapsed to a builder configuration:

```java
ThresholdFaultPolicy.builder()
    .faultTypes(Set.of(FaultType.PROVISION_FAILED))
    .tier(4, addReviewNode(AI_REVIEW, aiSpec), AI_REVIEW)
    .tier(7, addReviewNode(HUMAN_REVIEW, humanSpec), HUMAN_REVIEW)
    .build()
```

Different escalation model — count-based instead of domain-state-based. Simpler, generic, and the domain doesn't need to know about escalation mechanics at all.

The broader pattern is worth naming: when your runtime already maintains a dependency graph, query it before building auxiliary state. The graph structure encodes relationships that external trackers merely duplicate. `dependentsOf` was always there. It just took the right question to make it useful for something beyond ordering.
