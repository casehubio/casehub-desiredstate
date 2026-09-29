---
layout: post
title: "The Layers Were Already There"
date: 2026-09-29
entry_type: note
subtype: diary
projects: [casehubio/casehub-desiredstate]
tags: [orchestration, parallelism, topological-sort, plugin-migration, lifecycle]
series: issue-150-orchestration-primitives
---

# The Layers Were Already There

I'd been thinking about parallel provisioning as a new capability — something to design from scratch. The issue framed it that way: use yaml-core's `OrcLatch` for dependency gating, `OrcSemaphore` for rate limiting, `ComputeBlock` for parallel execution. Three new primitives to wire into the executor.

Then I looked at `TransitionPlanner.topologicalSort()`. Kahn's algorithm processes nodes in BFS levels — collect everything with in-degree zero, process them, decrement their dependents, collect the next batch. Those BFS levels ARE the concurrent layers. The planner already knew which nodes could run in parallel. It was computing the answer and throwing it away, flattening everything into `List<NodeId>`.

The fix was changing the return type from `List<NodeId>` to `List<List<NodeId>>`. Instead of adding each node to a flat result, collect the whole BFS level as one inner list before moving to the next. The planner gained layer-structured output without changing its algorithm — same code, different container.

This rippled further than expected. `TransitionPlan` went from `List<OrderedStep>` per phase to `List<List<OrderedStep>>`. That touched 56 call sites across the codebase — every test, every executor, the reconciliation loop, the engine adapter. Each call to `plan.removals()` became `plan.flatRemovals()`. Mechanical, but the blast radius showed how deeply the flat assumption was embedded.

The second surprise was the plugin module. The issue described integrating orchestration primitives into plugin provisioners — retry directives, state machines, parallel steps. But `yaml-step-runtime` already had all of this. `StructuralStepEvaluator` handles parallel steps via virtual threads, barrier synchronization via `OrcLatch`, quorum voting, first-wins select, retry and loop decorators, try-catch-finally — the full toolkit. The plugin module was stuck on `yaml-step-core`, an archived module with a sequential-only `StepPipelineExecutor`. The work isn't building orchestration integration — it's migrating to the evaluator that already has it.

The adversarial design review caught something every dimension spotted independently: `NodeStatus.DEGRADED` doesn't exist. I'd been designing lifecycle state machines around a DEGRADED state that isn't in the enum — the actual value is DRIFTED. The semantic difference matters: DRIFTED means "diverged from desired spec," which is the right concept for provisioning failure. DEGRADED implies operational health monitoring, which is a separate concern.

The review also forced the `NodeStepExecutor` extraction. `SimpleTransitionExecutor` has ~260 lines of per-node execution logic — human gating, approval lifecycle, OTel tracing, lifecycle hooks — repeated four times across provision/deprovision/suspend/resume. `ParallelTransitionExecutor` needs the same logic. Extracting it into a shared class means both executors (and any future executor) get the same cross-cutting concerns without duplication.

Three of ten tasks are done. The foundation is in place: API types, layered transition plans, and the extracted `NodeStepExecutor`. Next session picks up with `ParallelTransitionExecutor` itself — virtual threads, `CountDownLatch` per layer, JDK `Semaphore` for rate limiting. The concurrency is straightforward now that the layer structure exists. After that, the plugin migration and lifecycle state machines are independent tracks.

The pattern that keeps repeating in this project: the capability already exists somewhere in the platform. The work is finding it and connecting it, not building it from scratch.
