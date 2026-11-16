---
layout: post
title: "Rollback — Why Undo Is Harder Than It Looks in a Reconciliation Loop"
date: 2026-10-26
entry_type: note
subtype: diary
projects: [casehubio/casehub-desiredstate]
tags: [rollback, reconciliation, history, approval-gate, spi]
series: issue-134-rollback-desired-state-graph
---

# Rollback — Why Undo Is Harder Than It Looks in a Reconciliation Loop

The reconciliation loop applies desired-state changes continuously. When a bad change lands, it reconciles toward that bad state immediately. The PlanApprovalGate can catch it before execution — that was the point of the plan-preview work — but once a change gets through, there's no "undo." The ReconciliationStateStore holds exactly one snapshot per tenant, overwritten every cycle. History is gone the moment it's written.

The feature request seemed straightforward: store N previous graphs, add a `rollback()` method, done. The interesting parts turned out to be everything around the edges.

## What "previous version" actually means

`DesiredStateGraph.version()` is a local monotonic counter that increments on every mutation — including internal ones from fault policy CAS retries and lifecycle phase transitions. A single user change can trigger multiple version bumps. If history tracked `graph.version()`, most entries would be invisible system mutations, and "roll back to the previous version" would revert the last fault retry, not the last user intent.

So the history store uses its own sequence number per tenant, and only records at `updateDesired()` — the entry point for deliberate changes. Lifecycle phase transitions go through `compareAndSetDesired()`, which is deliberately excluded. A five-phase lifecycle would otherwise consume half the default history depth of ten, pushing out the initial graph that rollback exists to recover.

## The lifecycle cancellation problem

If a tenant is mid-lifecycle (phase 2 of 3) and rollback fires, what happens to the lifecycle? The phases are stored in `LifecycleManager.lifecycles` — a ConcurrentHashMap keyed by tenant. The historical graph has no lifecycle metadata; it's a raw `DesiredStateGraph`, not a `CompilationResult.Lifecycle`.

I decided rollback cancels the lifecycle outright. It's an operational recovery verb — the operator is choosing to abandon whatever is in progress and revert to a known-good graph. If they want the lifecycle back, they recompile. This aligns with the ADR that says lifecycle is forward-only.

The implementation needed a `LifecycleCanceller` SPI to break the circular dependency. ReconciliationLoop creates LifecycleManager, which holds a reference back to ReconciliationLoop. Adding `LifecycleManager` as a constructor parameter to ReconciliationLoop would create a cycle. The interface in `api/` lets ReconciliationLoop depend on the contract without knowing the implementation.

## Rollback through the approval gate

A rollback generates a normal `TransitionPlan` — the planner diffs the historical desired graph against actual state and produces additions and removals. This plan flows through `PlanApprovalGate` like any other. The policy can auto-approve rollback plans for emergency recovery, or gate them for non-critical cases.

The mechanism for distinguishing rollback plans is a `PlanContext` sealed interface — `Normal` or `Rollback(fromVersion, toVersion, reason, rollbackId)`. This was a breaking change to `PlanApprovalPolicy.evaluate()`, which gained a third parameter. No backward-compatible default method — every policy implementation must handle the context explicitly. The rollbackId is a UUID that flows through to approval and rejection CloudEvents, so monitoring systems can correlate the `ROLLBACK_INITIATED` event with the subsequent plan lifecycle.

![Rollback flow — from rollback() call through approval gate to execution](images/rollback-flow.svg)

## The rejected-rollback recovery path

The design review surfaced a scenario I hadn't considered: what happens when a rollback plan is rejected by the approval policy? The desired graph has already been swapped — rollback commits intent before execution is gated. If the plan is rejected, the operator is stuck with a desired state that can't converge.

The recovery path is rollback-of-rollback. The pre-rollback graph is in history because it was recorded when originally set via `updateDesired()`. The rollback itself records a new version (the historical graph it restored). Both are in history. The operator can list versions and roll back again to the pre-rollback state.

For this to work, rollback must never prune history — if pruning ran after recording the rollback version, it could evict the pre-rollback graph. Regular `updateDesired()` calls prune normally; rollback skips it.

## The concurrency guard

`rollback()` runs on the caller's thread; `reconcile()` runs on the scheduler thread. Between the approval gate cancel and the desired graph swap, a concurrent reconciliation cycle could read the old graph, plan against it, and submit a new pending plan. The `volatile boolean rollbackInProgress` flag on TenantLoop short-circuits `reconcile()` at the top when set, blocking the window.

## What it looks like

The SPI is a single new interface — `DesiredStateHistoryStore` — with `record()`, `load()`, `listVersions()`, `prune()`, and `remove()`. The in-memory implementation backs with ConcurrentHashMap and AtomicLong per-tenant sequences. The JPA implementation uses a composite key `(tenancy_id, version)` with a Flyway V3 migration. Both Quarkus CDI and Spring auto-config wire it the same way the existing stores do — `@DefaultBean` fallback to in-memory, JPA displaces when on classpath.

The rollback entry point is `ReconciliationLoop.rollback(tenancyId, version, initiatedBy)`. It validates, cancels pending approvals and lifecycle, emits the audit event, sets the `PlanContext.Rollback` for the next cycle, and swaps desired state. The normal reconciliation loop does the rest — planning, approval, execution. No new execution path.

The one thing it doesn't do is per-node rollback. The design review argued — correctly — that per-node rollback is functionally `GraphMutation.UpdateNode` with a historical spec lookup. The runtime doesn't need new capability for it; it's a tooling convenience that can use the same history store later.
