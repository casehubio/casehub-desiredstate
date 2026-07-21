# 0001 — Desired state as a planning paradigm

Date: 2026-07-21
Status: Accepted

## Context and Problem Statement

The desired-state runtime was designed for infrastructure-style reconciliation — declare a
graph, provision nodes, detect drift, correct faults. Epic #24 asked whether this model
extends to classical planning domains (QuarkMind/StarCraft II as the proof point), and if so,
what architectural changes are needed. Three POCs evaluated this: lifecycle transitions (#58),
planner-backed GoalCompiler (#56), and spatial/vector state representation (#57).

## Decision Drivers

* Desired-state graphs are structurally isomorphic to classical plans — nodes as states/actions,
  edges as causal links, topological ordering as execution sequence
* The runtime adds continuous reconciliation — something classical planning lacks
* QuarkMind needs adaptive plans (replanning on failure) and multi-phase lifecycles
  (build → defend → attack)
* Spatial/continuous domains (force distribution, terrain control) must work within the
  existing graph model or justify a new representation

## Considered Options

* **Option A** — Graph model sufficient as-is; planning domains use GoalCompiler with no
  runtime changes
* **Option B** — Graph model for representation + separate reasoning layer for aggregate
  patterns; runtime extended with lifecycle support
* **Option C** — New state representation (vector/spatial) alongside graph; runtime factored
  into shared shell with pluggable representations

## Decision Outcome

Chosen option: **Option B** — the graph model handles spatial and planning state
representationally, but aggregate reasoning (correlated faults, strategic pivots) requires a
separate situational awareness layer. The runtime gains lifecycle support via
`CompilationResult.Lifecycle`.

### Positive Consequences

* GoalCompiler SPI validated as the seam for classical planning — HTN/STRIPS planners output
  `DesiredStateGraph`; reconciliation loop executes it with drift detection for free
* No runtime factoring needed — one graph model serves infrastructure, planning, and spatial
  domains
* Lifecycle transitions (`CompilationResult.Lifecycle` + `LifecycleManager`) enable multi-phase
  goals (build → defend) with CAS-safe graph swaps and zero reconciliation gaps
* Fault-triggered replan works at two orthogonal levels: `FaultPolicy` for tactical per-node
  mutations, `SituationRecompiler` for strategic whole-graph replanning
* No conflict between reconciliation loop (drift → re-provision) and planner (fault → replan) —
  three concerns compose cleanly

### Negative Consequences / Tradeoffs

* Graph model cannot reason about aggregate patterns — three losses at the same position are
  three independent faults, not a "this approach is failing" signal. Requires RAS integration
  (#61, now shipped) for correlated fault detection
* FaultPolicy SPI required modification — gained `ActualState` parameter to support composite
  node reasoning (breaking change, addressed in #57)
* Lifecycle is forward-only — no pause/resume or rollback to previous phase
* GoalCompiler recompilation is verbose for parametric changes (ratio adjustments produce full
  graph rebuild); incremental `UpdateNode` mutations work but require N+1 operations

## Pros and Cons of the Options

### Option A — No runtime changes

* ✅ Zero risk, zero cost
* ❌ No lifecycle support — "achieved → transition to next phase" impossible without external
  orchestration
* ❌ Aggregate pattern detection has no home — each fault handled independently

### Option B — Graph + reasoning layer (chosen)

* ✅ Graph model proven sufficient for representation across all tested domains
* ✅ Lifecycle support is small, compositional, and works with existing reconciliation loop
* ✅ Reasoning gap cleanly separated — RAS/Ganglia handles aggregate detection, SituationRecompiler
  handles response
* ❌ Two systems to understand (graph reconciliation + RAS situation detection)
* ❌ FaultPolicy SPI changed (ActualState parameter added)

### Option C — Alternative state representation

* ✅ Would allow native vector/spatial operations without node-per-region overhead
* ❌ Massive runtime factoring cost — ReconciliationLoop and TransitionPlanner deeply coupled to
  DAG structure (Kahn's sort, CAS mutation model, COW graphs)
* ❌ POC #57 proved unnecessary — graph handles spatial state representationally; the gap is
  reasoning, not representation

## Links

* Epic: casehubio/casehub-desiredstate#24
* POC #56 — Planner-backed GoalCompiler: casehubio/casehub-desiredstate#56 (closed)
* POC #57 — Spatial/vector state: casehubio/casehub-desiredstate#57 (closed)
* POC #58 — Lifecycle transitions: casehubio/casehub-desiredstate#58 (closed)
* RAS integration: casehubio/casehub-desiredstate#61 (closed)
* Constraint/evaluation model: casehubio/casehub-desiredstate#59 (closed)
* Runtime factoring assessment: casehubio/casehub-desiredstate#60 (closed)
* Expansion example: `examples/expansion/`
* Spatial example: `examples/spatial/`
