# Decisions — #150 Orchestration Primitives

## D1: Plugin step migration architecture

**Choice:** Direct integration — PluginParser produces `ResolvedStep` trees, YamlPluginProvisioner uses `StructuralStepEvaluator` from yaml-step-runtime
**Alternatives:**
- Adapter layer — keep `StepDef` output, convert at execution time. Smaller parser change but adds maintenance-only conversion layer
**Rationale:** `StructuralStepEvaluator` is the long-term step engine. Plugin module should speak its language natively. Adapter adds indirection without capability.
**Trade-offs:** Larger migration surface — PluginParser output changes from `List<StepDef>` to `List<ResolvedStep>`, PluginDescriptor field types change
**Sources:** `plugin/runtime/src/main/java/io/casehub/desiredstate/plugin/runtime/YamlPluginProvisioner.java`, `platform/yaml-step-runtime/src/main/java/io/casehub/yaml/step/eval/StructuralStepEvaluator.java`
**Exploration:** quick
**Status:** captured

## D2: ParallelTransitionExecutor parallelism model

**Choice:** Layer-based execution — TransitionPlanner modified to expose parallelizable layers (topological depth groups), executor runs layers sequentially with nodes within each layer in parallel via virtual threads
**Alternatives:**
- Dependency-latch — all nodes submitted at once, each awaits its dependencies' OrcLatches. Maximum parallelism but harder to reason about and debug
**Rationale:** Predictable, debuggable. Layer-based model provides clear phase boundaries for debugging and OTel span grouping. Introduces parallelism to the reconciliation loop for the first time (no existing parallel execution). Requires modifying `TransitionPlanner.topologicalSort()` to return depth groups and evolving `TransitionPlan` API (see D9).
**Trade-offs:** Slightly coarser parallelism — a node finishing early in layer N waits for the whole layer before layer N+1 starts. TransitionPlanner API change required. TransitionPlan API change required (see D9).
**Sources:** `runtime-core/src/main/java/io/casehub/desiredstate/runtime/TransitionPlanner.java:120` (topological sort), `runtime-core/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java`
**Exploration:** quick
**Status:** revised — acknowledged TransitionPlanner/TransitionPlan changes as part of migration surface; clarified this introduces parallelism for the first time (R1-05, R1-07)

## D3: Node lifecycle state machine integration

**Choice:** NodeStatus expansion — add PROVISIONING, DEPROVISIONING, SUSPENDING, RESUMING to `NodeStatus` enum. Executor sets transitional states during execution. `NodeProvisioner.supportsStatefulLifecycle()` (already exists) remains the opt-in gate. No separate `NodeLifecycleState` enum, no decorator wrapper.
**Alternatives:**
- Decorator wrapper — `StatefulNodeProvisioner` wraps any `NodeProvisioner`, owns `OrcStateMachine<NodeLifecycleState>` per node instance. Violates `no-workarounds-fix-the-design` protocol (PP-20260522-3b1ccd) — a wrapper is a wrapper.
- SPI default methods — `lifecycleState(NodeId)` on `NodeProvisioner`. Misplaces responsibility — lifecycle state is an execution concern, not a provisioner concern.
**Rationale:** `NodeStatus` already contains `SUSPENDED` — a lifecycle state. The precedent is set: lifecycle states belong in `NodeStatus`. The planner's `decideAction()` already handles `SUSPENDED`. Adding transitional states follows the same pattern. `NodeProvisioner.supportsStatefulLifecycle()` already exists as a default method (line 52), proving the SPI was already evolving toward lifecycle awareness. Breaking API change forces consumers to handle new states explicitly.
**Trade-offs:** Breaking API change on `NodeStatus` (L1 Core API). All `switch` expressions over `NodeStatus` must add new cases. This is the point — consumers must be explicit about transitional states. Transitional states are observable via OTel span attributes, CloudEvent payloads (through `ReconciliationEventEmitter`), and status query endpoints. `ActualStateAdapter` implementations never return transitional values — they produce only PRESENT, ABSENT, DRIFTED, UNKNOWN, or SUSPENDED. This dual usage of `NodeStatus` (adapter-observed stable states vs executor-set transitional states) follows the precedent established by SUSPENDED.
**Sources:** `api/src/main/java/io/casehub/desiredstate/api/NodeStatus.java`, `api/src/main/java/io/casehub/desiredstate/api/NodeProvisioner.java`, PP-20260522-3b1ccd (`no-workarounds-fix-the-design`), PP-20260601-81b9e5 (`spi-evolution-default-methods`)
**Exploration:** quick
**Status:** revised — dropped decorator approach, adopted NodeStatus expansion per platform protocols and existing SPI evolution direction (R1-10, R1-11, R1-12); clarified observability surface (R2-03)

## D4: State machine persistence

**Choice:** Reconstruct from ActualState on startup — derive lifecycle state from current ActualState. Transient states (PROVISIONING, DEPROVISIONING, SUSPENDING, RESUMING) exist only during execution. No new persistence infrastructure.
**Alternatives:**
- NodeLifecycleStore SPI — persist lifecycle state per node with JPA entity and Flyway migration. Preserves transient states across restarts but adds significant infrastructure.
**Rationale:** The reconciliation loop's re-planning already handles crash recovery. If provisioning was in-flight when process died, reconciliation re-evaluates and re-plans. Persisting transient states adds complexity without safety improvement.
**Trade-offs:** Transient states are lost on restart — harmless for idempotent provisioners because reconciliation re-derives the needed actions from ActualState. For non-idempotent provisioners, crash during provisioning may result in double-provisioning of external resources. The SPI does not require idempotency, but provisioners SHOULD be idempotent as a matter of good design.
**Sources:** `runtime-core/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java`, reconciliation model design
**Exploration:** quick
**Depends on:** D3 (NodeStatus expansion approach)
**Status:** revised — qualified "harmless" claim with idempotency assumption (R1-14)

## D5: Scope — all three applications

**Choice:** All three applications in one branch — plugin provisioner orchestration (#1), parallel transition executor (#2), declarative node lifecycle state machines (#3)
**Alternatives:**
- App #1 only — self-contained M-scale deliverable
- App #1 + #2 — related but independent, #3 deferred
**Rationale:** User preference for comprehensive delivery. Applications have separate integration points: #1 touches PluginParser/YamlPluginProvisioner, #2 touches TransitionPlanner/TransitionExecutor, #3 touches NodeStatus/executor lifecycle tracking. They share infrastructure modules but different classes within those modules.
**Trade-offs:** L-scale branch. Risk of cross-contamination across shared modules. Mitigated by per-application tests and incremental commits.
**Sources:** GitHub issue #150
**Exploration:** quick
**Status:** captured

## D6: Parallelism implementation — virtual threads + JDK Semaphore

**Choice:** Virtual threads per node within each layer, `java.util.concurrent.Semaphore` for optional per-NodeType concurrency limits. Parallel execution is opt-in per provisioner via `supportsParallelExecution()` default method. Error handling: continue all — collect all outcomes, let FaultPolicyEngine handle failures on next cycle.
**Alternatives:**
- Virtual threads + OrcSemaphore from yaml-core — creates framework coupling in runtime-core (violates framework-neutral invariant)
- Virtual threads only — no rate limiting
- Configurable ExecutorService SPI — most flexible but heaviest
**Rationale:** Virtual threads provide lightweight per-node threads. JDK Semaphore provides simple concurrency limiting without yaml-core dependency. Opt-in parallel execution respects provisioner thread safety (domain provisioners with mutable `@ApplicationScoped` world objects are NOT thread-safe). Continue-all error handling is consistent with the existing sequential executor — `SimpleTransitionExecutor` collects all outcomes including failures, never aborts on single failure.
**Trade-offs:** Provisioners must explicitly opt in to parallel execution. Semaphore configuration surface needed. Default is sequential per-provisioner within a layer (same as before for non-opting provisioners).
**Sources:** `runtime-core/src/main/java/io/casehub/desiredstate/runtime/SimpleTransitionExecutor.java` (error handling pattern), JDK `java.util.concurrent.Semaphore`
**Exploration:** quick
**Status:** revised — replaced OrcSemaphore with JDK Semaphore (R1-19), added opt-in parallel execution (R1-20), specified error handling model (R1-21)

## D7: ParallelTransitionExecutor module placement

**Choice:** Framework-neutral implementation in `runtime-core` with CDI bridge in `runtime/`
**Alternatives:**
- CDI-only in `runtime/` — simpler but breaks framework-neutral pattern
**Rationale:** Consistent with SimpleTransitionExecutor's placement. Spring module gets the same pattern.
**Trade-offs:** Two classes instead of one (core + CDI bridge). Standard pattern, no real cost.
**Sources:** `runtime-core/` and `runtime/` module structure
**Exploration:** quick
**Status:** captured

## D8: Plugin YAML orchestration surface — inline decorators

**Choice:** Inline decorators on steps (`retry:`, `loop:`, `parallel:`, etc.) with top-level lifecycle state machine declaration
**Alternatives:**
- Top-level `orchestration:` section — all config in one block, steps reference by name
- Both — top-level defaults + inline overrides
**Rationale:** Matches how `StructuralStepEvaluator` + `DecoratorChain` work in `yaml-step-runtime`. Each `ResolvedStep` carries `Map<String, Object> decorators`. `DecoratorChain.apply()` wraps step execution with retry, loop, when, timeout, semaphore, transform, forEach, and state machine transition decorators. The existing `PluginParser.STEP_DIRECTIVES` (`result`, `when`, `on-error`, `max-retries`, `backoff`) are already inline directives — extending with orchestration decorators follows the established pattern.
**Trade-offs:** Lifecycle declaration is at top level while step orchestration is inline — two locations. But this reflects the semantic difference (type-wide vs step-specific).
**Sources:** `yaml-step-runtime/.../StructuralStepEvaluator.java` (`evaluateInternal()`, line 48), `yaml-step-runtime/.../DecoratorChain.java` (`apply()`, line 42), `yaml-step-runtime/.../ResolvedStep.java` (`decorators()` field)
**Exploration:** quick
**Status:** captured

## D9: TransitionPlan API evolution

**Choice:** Evolve `TransitionPlan` to carry layer structure for parallelizable phases. Per-phase treatment:
- **Additions:** `List<List<OrderedStep>> additionLayers` — topological depth groups, roots before leaves
- **Resumptions:** `List<List<OrderedStep>> resumptionLayers` — same layering as additions (roots before leaves)
- **Suspensions:** `List<List<OrderedStep>> suspensionLayers` — reverse depth groups, leaves before roots (planner already uses `topologicalSortReverse()`)
- **Removals:** stays flat `List<OrderedStep>` — orphaned nodes are no longer in the desired graph, so dependency context for layer computation is unavailable

Breaking API change.
**Alternatives:**
- Separate `LayeredTransitionPlan` — new type, old `TransitionPlan` preserved. Adds a parallel type hierarchy with no architectural benefit.
- Layers in executor only — planner returns flat list, executor reconstructs layers. Duplicates topological computation and loses layer semantics at the API boundary.
**Rationale:** `TransitionPlan` is in api/ (L1 Core API). Changes there break all consumers — but per `no-workarounds-fix-the-design` (PP-20260522-3b1ccd), this is acceptable. The API change forces consumers to handle layers explicitly. The sequential executor continues to work by iterating all layers sequentially. Removals remain flat because `TransitionPlanner.plan()` builds removals from `actual.statuses()` entries absent from `desired.nodes()` — the desired graph has no dependency edges for these orphaned nodes.
**Trade-offs:** Breaking API change. All `TransitionPlan` consumers must update. `SimpleTransitionExecutor` iterates layers sequentially (same effective behaviour as flat list). Removals execute sequentially within a single flat list — acceptable because orphan removal order is arbitrary when dependency context is absent.
**Sources:** `api/src/main/java/io/casehub/desiredstate/api/TransitionPlan.java`, `runtime-core/src/main/java/io/casehub/desiredstate/runtime/TransitionPlanner.java` (lines 86-101)
**Exploration:** quick (surfaced by review)
**Status:** revised — explicitly addressed all four phases: additions and resumptions get depth layers, suspensions get reverse layers, removals stay flat (R2-02)

## D10: runtime-core dependency boundary — no yaml-core

**Choice:** runtime-core maintains no dependency on `casehub-platform-yaml-core` or `casehub-platform-yaml-step-*`. Framework-neutral invariant preserved.
**Alternatives:**
- Accept yaml-core dependency for OrcSemaphore/OrcStateMachine — breaks framework-neutral pattern, couples runtime-core to yaml infrastructure
**Rationale:** D3 revised to expand `NodeStatus` (no `OrcStateMachine` in runtime-core). D6 revised to use JDK `Semaphore` (no `OrcSemaphore` in runtime-core). Both revisions eliminate the need for yaml-core dependency. runtime-core's current dependencies: `desiredstate-api`, `platform-api`, `ras-api`, `opentelemetry-api`, `cloudevents-api`, `jackson-databind`, `mutiny`.
**Trade-offs:** None — the framework-neutral invariant is already the design intent. This decision makes it explicit.
**Sources:** `runtime-core/pom.xml`
**Exploration:** quick (surfaced by review)
**Status:** captured

## D11: NodeProvisioner thread safety contract — opt-in parallel execution

**Choice:** New default method `supportsParallelExecution()` returning `false` on `NodeProvisioner`. Executor serializes same-provisioner nodes within a layer unless the provisioner opts in.
**Alternatives:**
- Universal thread safety requirement — all provisioners must be thread-safe. Breaking contract change, forces synchronization in domain provisioners.
- No contract, unsafe parallelism — run all nodes concurrently regardless. Correctness risk for provisioners with mutable state.
**Rationale:** Follows `spi-evolution-default-methods` protocol (PP-20260601-81b9e5). Existing provisioners remain safely sequential. New provisioners opt in when thread-safe. Domain provisioners with `@ApplicationScoped` mutable world objects (DungeonWorld, PipelineWorld, ExpansionWorld) default to sequential execution within their provisioner scope — correct without code changes.
**Trade-offs:** Provisioners that don't opt in get no intra-provisioner parallelism. Cross-provisioner parallelism (different NodeTypes, different provisioners) works unconditionally — no serialization concern across provisioner boundaries.
**Sources:** `api/src/main/java/io/casehub/desiredstate/api/NodeProvisioner.java`, PP-20260601-81b9e5
**Exploration:** quick (surfaced by review)
**Status:** captured

## D12: CaseTransitionExecutor parallel execution participation

**Choice:** `CaseTransitionExecutor` consumes layer-structured `TransitionPlan` (from D9) and generates parallel workflow structures (fork branches per layer) instead of sequential `TaskItem` lists. Suspend/resume support is a separate concern (currently throws `UnsupportedOperationException`).
**Alternatives:**
- CaseTransitionExecutor remains sequential — only `SimpleTransitionExecutor` replacement (`ParallelTransitionExecutor`) gets parallelism. Production engine-backed deployments would not benefit.
- Full CaseTransitionExecutor overhaul including suspend/resume — too broad for this scope; suspend/resume depends on engine workflow capabilities
**Rationale:** The ARC42STORIES.MD L3 aspiration states: "independent nodes at the same topological level execute as parallel fork branches in the generated workflow." D9's layer-structured `TransitionPlan` makes this natural — each layer becomes a parallel `fork` in the generated Serverless Workflow, layers execute sequentially.
**Trade-offs:** `TransitionWorkflowGenerator` must evolve to emit parallel/fork constructs. Engine must support parallel workflow branches (assumed from Serverless Workflow DSL support).
**Sources:** `engine-adapter/src/main/java/io/casehub/desiredstate/engine/TransitionWorkflowGenerator.java`, `engine-adapter/src/main/java/io/casehub/desiredstate/engine/CaseTransitionExecutor.java`, ARC42STORIES.MD §L3
**Exploration:** quick (surfaced by review)
**Status:** captured
