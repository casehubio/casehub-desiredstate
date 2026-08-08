# Dependency-Aware Graph Mutation Helpers + Multi-Tier Escalation

**Issues:** #87 (graph mutation helpers), #86 (multi-tier escalation in ThresholdFaultPolicy)
**Date:** 2026-08-08
**Status:** Approved

## Problem

Two gaps in the desired-state API surfaced during design review of casehub-ops#45:

1. **GraphMutation operations are independent of graph structure.** Fault policies that
   need to add connected subgraphs (e.g., a review node that depends on the faulted node)
   must manually compose `[AddNode, AddDependency]`. The existing `addReviewNode` helper
   creates review nodes without dependency edges — review nodes are structurally disconnected
   from the nodes they review.

2. **ThresholdFaultPolicy supports single-tier escalation only.** The platform's three-tier
   model (auto-retry → AI review → human review) requires composable escalation chains.
   `ProvisionEscalationFaultPolicy` in the pipeline example hand-rolls this in ~50 lines
   of stateful logic. Domain modules shouldn't need to reimplement multi-tier logic.

These issues are coupled: #87's dependency edges enable #86's graph-presence-based
escalation detection.

## Design

### GraphMutations Utility (#87)

New utility class in `api/`: `GraphMutations`

```java
public final class GraphMutations {
    private GraphMutations() {}

    public static List<GraphMutation> addNodeDependingOn(DesiredNode node, NodeId dependsOn) {
        return List.of(
            new GraphMutation.AddNode(node),
            new GraphMutation.AddDependency(new Dependency(node.id(), dependsOn))
        );
    }
}
```

- `addNodeDependingOn` — the common pattern: add a node and a dependency edge from it to
  an existing node. Returns `[AddNode, AddDependency]`.

`removeNodeWithEdges` was considered but dropped — `DesiredStateGraph.withoutNode()` already
removes the node and all its dependency edges. Explicit per-edge `RemoveDependency` mutations
are redundant, get reordered by `FaultPolicyEngine`, and create unnecessary graph copies.

### Updated addReviewNode (#87)

Two changes to `FaultPolicy.addReviewNode(NodeType, ReviewSpecFactory)`:

1. **ID prefix from NodeType:** `reviewType.value() + "-" + event.node().value()` instead
   of hard-coded `"review-"`. So `NodeType.of("ai-review")` produces ID `"ai-review-n1"`.
   Backward-compatible for types named `"review"`. Matches the convention already used
   manually in ProvisionEscalationFaultPolicy.

2. **Dependency edge included:** Returns `GraphMutations.addNodeDependingOn(reviewNode, faultedNode)`
   instead of bare `List.of(new AddNode(...))`. Review nodes now depend on the faulted node —
   correct for removal ordering (review removed before faulted node) and enables #86's
   graph-presence detection via `dependentsOf()`.

**CAS safety:** `ReconciliationLoop.casRetryMutations` applies fault mutations in a CAS
retry loop. If the faulted node is removed between evaluation and CAS application, the
`AddDependency` would reference a missing target. `ImmutableDesiredStateGraph.withDependency()`
must tolerate this: if either endpoint of the dependency is absent from the graph, treat
the mutation as a no-op (return the graph unchanged). This preserves the CAS invariant
that mutations are safely re-applicable to any graph version.

### Multi-Tier ThresholdFaultPolicy (#86)

**Tier record** (nested in ThresholdFaultPolicy):

```java
public record Tier(int threshold, FaultPolicy action, NodeType nodeType) {
    public Tier {
        if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(nodeType, "nodeType is required");
    }
}
```

- `threshold` — fault count gate (fires when count ≥ threshold)
- `action` — what to do (typically `addReviewNode(...)`)
- `nodeType` — the type of node this tier creates (used by the next tier's graph-presence guard)

**Invariant:** the tier's `nodeType` MUST match the NodeType of the node created by the
tier's `action`. The `FaultPolicy` interface is opaque (a lambda), so the builder cannot
enforce this at build time. A mismatch causes the next tier's graph-presence guard to
look for the wrong type — escalation silently breaks.

**Builder changes:**

- Drop `.threshold(int)` and `.action(FaultPolicy)` — replaced by `.tier()`
- Add `.tier(int threshold, FaultPolicy action, NodeType nodeType)` — called multiple times
  for multi-tier chains
- Build validates: at least one tier required, thresholds must be strictly ascending
- **Auto-ignore:** tier nodeTypes are automatically merged into `ignoreTypes` — prevents
  infinite loops where a review node faults and re-triggers the policy

**Evaluation logic:**

1. Pre-checks unchanged: null node → remove count + return empty; ignored type → return empty;
   wrong fault type → return empty; wrong node type → return empty
2. Increment fault count (single counter, shared namespace — same as today)
3. Iterate tiers **highest-first**:
   - `count < tier.threshold` → skip
   - Tier 0 (first tier): no guard → fire action **and return immediately**
   - Tier N (N > 0): check `current.dependentsOf(faultedNodeId)` for a node of tier N-1's
     `nodeType`. Present → previous tier attempted and unresolved → fire action
     **and return immediately**. Absent → skip (fall through to lower tiers)
4. No tier matched → return empty

**First-match-wins:** only one tier fires per fault event. If a tier's action returns
empty (e.g., `addReviewNode` duplicate guard), that empty list is the policy result.
Lower tiers are not re-evaluated. This prevents creating a lower-tier review node
alongside an active higher-tier review.

**Graph-presence check implementation:** `dependentsOf()` returns `Set<NodeId>`. To
check types, iterate the returned IDs, look up each in `current.nodes()`, and check
`.type()` against the previous tier's `nodeType`.

**Graph-presence via `dependentsOf()`:** Because #87 ensures review nodes have dependency
edges to faulted nodes, `dependentsOf(faultedNode)` returns all review nodes attached to it.
Checking for a specific `NodeType` among those dependents tells the mechanism whether the
previous tier was attempted. No ID-convention coupling — uses existing graph API.

**Escalation trace (pipeline example):**

```java
ThresholdFaultPolicy.builder()
    .faultTypes(Set.of(FaultType.PROVISION_FAILED))
    .tier(4, addReviewNode(AI_REVIEW, aiSpecFactory), AI_REVIEW)
    .tier(7, addReviewNode(HUMAN_REVIEW, humanSpecFactory), HUMAN_REVIEW)
    .build()
```

Note: threshold=4 matches the existing `ProvisionEscalationFaultPolicy` which uses
`count <= 3` (fires on the 4th fault). Threshold=7 gives 3 additional faults for AI
review to resolve before human escalation.

| Fault # | Count | Result |
|---------|-------|--------|
| 1–3 | 1–3 | Below tier 1 → empty (retry) |
| 4 | 4 | Tier 1 fires → AddNode(ai-review) + AddDependency |
| 5–6 | 5–6 | Tier 1: addReviewNode duplicate guard → empty |
| 7 | 7 | Tier 2: count ≥ 7, ai-review present → AddNode(human-review) + AddDependency |
| 8+ | 8+ | Tier 2: addReviewNode duplicate guard → empty |

**Resolution and re-escalation:** When a review resolves, the domain MUST remove the
review node from the desired graph and call `resetCount()`. Both steps are required:

- **Node removal** — graph-presence is the escalation signal. A lingering resolved review
  node would cause the next tier to fire incorrectly on subsequent faults (it would see
  the previous tier as "attempted and unresolved" when it's actually resolved). This closes
  the ARC42STORIES §12 debt item about lingering review nodes.
- **Count reset** — resets the escalation chain so the next fault starts from count 1.

Without both: tier 2 skips (tier 1 node absent after removal), tier 1 re-fires
(re-adds review node) — correct self-healing behavior.

**Escalation model difference:** the existing `ProvisionEscalationFaultPolicy` escalates
based on domain-specific state (`PipelineWorld.ReviewState`) — it can escalate immediately
when an AI review reports ESCALATED, regardless of fault count. The multi-tier
ThresholdFaultPolicy uses a simpler model: fault count + graph-presence. This is not a
1:1 behavioral replacement — it is a deliberately simpler, domain-agnostic mechanism.
The pipeline example will adapt to the count-based model.

## Impact on Existing Code

| File | Change |
|------|--------|
| `ThresholdFaultPolicy` | Replace `(threshold, action)` with `List<Tier>`. Builder API migration. Auto-ignore. New evaluation loop with first-match-wins. |
| `ThresholdFaultPolicyTest` | Migrate builder calls to `.tier()`. Update mutation count assertions: `addReviewNode` now returns 2 mutations (AddNode + AddDependency) instead of 1. |
| `FaultPolicy.addReviewNode()` | NodeType-based ID prefix, return `GraphMutations.addNodeDependingOn()`. |
| `ImmutableDesiredStateGraph.withDependency()` | Tolerate missing endpoint nodes — return graph unchanged (no-op) instead of throwing. Required for CAS retry safety. |
| `ProvisionEscalationFaultPolicy` | **Delete.** Replaced by ThresholdFaultPolicy configuration with count-based escalation (different model — see escalation model difference above). |
| `ProvisionEscalationFaultPolicyTest` | Rewrite to test ThresholdFaultPolicy-based config. Behavioral change: count-based escalation replaces domain-state-based escalation. |

**No changes needed:** FaultPolicyEngine (already handles dependency mutations),
GraphDiff, CbrFaultPolicy, HeroRaidFaultPolicy, ExpansionFaultPolicy.

**Follow-up (out of scope):** `SchemaDriftFaultPolicy` and `QuarantineFaultPolicy` hand-roll
`AddNode` without `addReviewNode` — they create review nodes without dependency edges,
which is now structurally inconsistent with the platform convention. File a follow-up issue
to migrate them to use `addReviewNode`.

**New files:** `GraphMutations.java` (api/), `GraphMutationsTest.java` (runtime/test/).

## Test Plan

### GraphMutations
- `addNodeDependingOn` returns [AddNode, AddDependency] with correct node and edge

### Updated addReviewNode
- Returns AddNode + AddDependency (2 mutations, not 1)
- ID derived from NodeType value (`"ai-review"` → `"ai-review-n1"`)
- Duplicate guard returns empty when node exists

### ImmutableDesiredStateGraph.withDependency()
- Missing `from` node → no-op (graph unchanged)
- Missing `to` node → no-op (graph unchanged)
- Both nodes present → dependency added (existing behavior)

### Multi-tier ThresholdFaultPolicy
- Single tier — same behavior as old single-threshold (regression guard)
- Two tiers — below all thresholds → empty
- Two tiers — at tier 1 threshold → tier 1 fires
- Two tiers — at tier 2 threshold, tier 1 node present → tier 2 fires
- Two tiers — at tier 2 threshold, tier 1 node absent → tier 1 fires (not tier 2)
- First-match-wins — tier action returns empty → empty is the result, lower tiers not checked
- Three tiers — full escalation chain
- Auto-ignore — faults on tier nodeTypes return empty
- Tenant isolation across tiers
- `resetCount` resets the escalation chain
- Builder rejects: no tiers, non-ascending thresholds

### Pipeline example
- Verify count-based escalation with ThresholdFaultPolicy replacing ProvisionEscalationFaultPolicy
- Threshold=4 fires on 4th fault (matches original PEFP behavior for tier 1 entry)
