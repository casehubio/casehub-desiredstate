# Worker Foundation Extraction — Design Spec

**Issue:** #40 (casehub-desiredstate), with cross-repo impact on casehub-platform and casehub-engine
**Date:** 2026-06-18
**Status:** Design

## Summary

Extract Worker primitives from `casehub-engine-api` (orchestration tier) into a new
foundation-tier `casehub-worker` repo. Add execution governance types (`ExecutionPolicy`,
`RetryPolicy`, `BackoffStrategy`) and a `PolicyEnforcer` runtime to `casehub-platform`.
Worker becomes a peer to WorkItem — automated work alongside human work, both at
foundation tier.

## Motivation

Worker is conceptually foundational — "a named unit of automated work with a function
and execution policy." It is not domain-specific or orchestration-specific. But it
currently lives in `casehub-engine-api`, making it unavailable to foundation-tier repos
without crossing tier boundaries.

Concrete trigger: `casehub-desiredstate` (foundation) needs Workers for pipeline stage
execution (#27) and potentially for node provisioning generally. Today this requires
an `engine-adapter` bridge module. With Worker at foundation tier, desiredstate can
use Workers directly.

Separately, execution governance (retry, timeout, backoff) is reimplemented across
the platform: `ExecutionPolicy` on Workers, SLA breach timers on WorkItems, wall-clock
timeout on agent invocations, retry semantics in connector delivery. The mechanics are
identical; only the values differ. Centralising the types and enforcement runtime in
`casehub-platform` establishes the pattern for convergence.

## Architecture

```
casehub-platform-api              ← ExecutionPolicy, RetryPolicy, BackoffStrategy
casehub-platform-governance       ← PolicyEnforcer (submodule in platform repo)
    │
casehub-worker-api                ← Worker, WorkerFunction, Capability, WorkerResult, WorkerOutcome
casehub-worker                    ← WorkerExecutor (bridges Worker → PolicyEnforcer)
casehub-worker-testing            ← MockWorkerExecutor, fixtures
    │
casehub-desiredstate-api          ← depends on worker-api
casehub-desiredstate              ← depends on casehub-worker runtime
    │
casehub-engine-api                ← depends on worker-api; adds WorkerFunction.AgentExec
casehub-engine-flow               ← adds WorkerFunction.Flow (Serverless Workflow SDK)
```

### Dependency flow

All downward, no cycles:

- `casehub-platform-api`: zero casehubio deps (existing)
- `casehub-platform-governance`: depends on `casehub-platform-api`
- `casehub-worker-api`: depends on `casehub-platform-api`
- `casehub-worker`: depends on `casehub-worker-api` + `casehub-platform-governance`
- `casehub-desiredstate-api`: depends on `casehub-worker-api`
- `casehub-engine-api`: depends on `casehub-worker-api` (replaces owning the types)

### Build order position

```
casehub-parent
  casehub-platform          ← including new governance submodule
  casehub-worker            ← new repo, no casehubio deps beyond platform-api
  casehub-ledger
  casehub-work
  casehub-desiredstate      ← now depends on worker-api
  ...
  casehub-engine            ← now depends on worker-api (instead of owning the types)
```

## Module detail

### casehub-platform-api — governance types

New types added to `casehub-platform-api` in package `io.casehub.platform.api.governance`:

| Type | What it is |
|------|-----------|
| `ExecutionPolicy` | `record(Integer timeoutMs, RetryPolicy retries)` |
| `RetryPolicy` | `record(Integer maxAttempts, Integer delayMs, BackoffStrategy backoffStrategy)` |
| `BackoffStrategy` | `enum: FIXED, EXPONENTIAL, EXPONENTIAL_WITH_JITTER` |

These are data types only — no runtime behaviour. Pure Java, zero dependencies.

### casehub-platform-governance — policy enforcement

New submodule in the casehub-platform repo. Package: `io.casehub.platform.governance`.

| Type | What it does |
|------|-------------|
| `PolicyEnforcer` | Takes any action + `ExecutionPolicy`, applies retry loop with backoff, enforces timeout. Generic — not Worker-specific. |

`PolicyEnforcer` is the single enforcement mechanism for the platform. Any executable
concern (Workers, agent invocations, connector delivery, future WorkItem SLA enforcement)
can use it. Workers are the first consumer; convergence of existing scattered
implementations is follow-up work.

### casehub-worker-api — worker primitives

New repo: `casehub-worker`. API module: `casehub-worker-api`.
Package: `io.casehub.worker.api`.

| Type | What it is |
|------|-----------|
| `Worker` | Name, capabilities, function, execution policy. No PlanElement, no AgentDescriptor. |
| `WorkerFunction` | Interface (not sealed) — extensible by higher tiers. Method: `WorkerResult execute(Map<String, Object> input)` |
| `WorkerFunction.Sync` | Built-in variant: wraps `Function<Map<String, Object>, WorkerResult>` |
| `Capability` | Name, input/output schemas, description |
| `WorkerResult` | `record(Map<String, Object> output, WorkerOutcome outcome)` — no PlannedAction |
| `WorkerOutcome` | Sealed: Success, Declined, Failed, Expired |

**WorkerFunction extensibility:** Plain interface implementation. Any module can add
a variant by implementing `WorkerFunction`. `WorkerFunction.Flow` is added by
`casehub-engine-flow` (Serverless Workflow SDK dependency). `WorkerFunction.AgentExec`
is added by `casehub-engine-api` (LangChain4j dependency). The `WorkerExecutor` does
not need to know the variant type — it calls `execute(input)`.

**Dependencies:** `casehub-platform-api` only (for `ExecutionPolicy`).

### casehub-worker — runtime

Runtime module: `casehub-worker`. Package: `io.casehub.worker.runtime`.

| Type | What it does |
|------|-------------|
| `WorkerExecutor` | `@ApplicationScoped` — executes a Worker: extracts function, delegates to `PolicyEnforcer` with the Worker's execution policy, adds OTel tracing per execution. |

**Dependencies:** `casehub-worker-api` + `casehub-platform-governance` + `opentelemetry-api`.

### casehub-worker-testing — test support

| Type | What it does |
|------|-------------|
| `MockWorkerExecutor` | `@DefaultBean` — direct execution without policy enforcement, for `@QuarkusTest` isolation |
| `TestWorkerBuilder` | Convenience builder for test Workers with `WorkerFunction.Sync` |

## What stays in casehub-engine-api

| Type | Why it stays |
|------|-------------|
| `PlanElement` | Engine plan model marker — Workers at foundation don't participate in plans |
| `AgentDescriptor` on Worker | Engine associates descriptors via composition, not a field on Worker |
| `PlannedAction` | Action risk classification — engine-only. Recovered from output map via well-known key. |
| `WorkerProvisioner` | Infrastructure provisioning SPI — engine/deployment concern |
| `WorkerStatusListener` | Orchestration lifecycle SPI |
| `WorkerExecutionGuard` | Orchestration gating SPI |
| `WorkerContextProvider` | Orchestration context SPI |
| `Agent` (LangChain4j) | `WorkerFunction.AgentExec` variant — engine-api owns the LangChain4j dependency |
| `WorkerFunction.Flow` | engine-flow owns the Serverless Workflow SDK dependency |

## Engine adaptation

The engine uses foundation `Worker` directly via **composition** (not subclassing):

- `CaseDefinition.builder().workers(List<Worker>)` accepts foundation Workers unchanged
- `PlanElement` is no longer implemented by Worker; the engine's internal plan model
  wraps Workers as needed
- `AgentDescriptor` is associated via lookup in `WorkOrchestrator`, not a field on Worker
- `PlannedAction` is recovered from `WorkerResult.output()` via a well-known key
  (e.g., `"_plannedAction"`) — the engine inspects post-execution

## Impact on casehub-desiredstate

With `casehub-worker-api` at foundation tier, desiredstate can depend on it directly:

- `DesiredNode` specs can reference `Worker` definitions
- `NodeProvisioner` implementations can delegate to `WorkerExecutor`
- The `ExecutionBackend` concept in the pipeline example may collapse — if a pipeline
  stage IS a Worker, `ExecutionBackend` is redundant with `WorkerFunction` extensibility
- The `engine-adapter` module remains for `CaseTransitionExecutor` (which uses
  `CaseHubRuntime`, `CaseDefinition` — engine orchestration concepts)

## Package naming

| Module | Package |
|--------|---------|
| platform-api governance types | `io.casehub.platform.api.governance` |
| platform-governance runtime | `io.casehub.platform.governance` |
| worker-api | `io.casehub.worker.api` |
| worker runtime | `io.casehub.worker.runtime` |
| worker testing | `io.casehub.worker.testing` |

## Migration

All repos importing `io.casehub.api.model.Worker`, `Capability`, `WorkerFunction`,
`WorkerResult`, `WorkerOutcome`, `ExecutionPolicy`, `RetryPolicy`, `BackoffStrategy`
update imports to the new packages. This is mechanical find-and-replace.

Affected repos: casehub-engine, claudony, casehub-workers, casehub-openclaw, devtown,
casehub-aml, casehub-clinical, casehub-life, quarkmind, casehub-ops.

## Cross-repo issues needed

| Issue | Repo | What it tracks |
|-------|------|---------------|
| TBD | casehub-platform | Add governance types to platform-api + PolicyEnforcer submodule |
| TBD | casehubio (new repo) | Create casehub-worker repo with api, runtime, testing modules |
| TBD | casehub-engine | Migrate Worker types to casehub-worker-api dependency; adapt PlanElement, AgentDescriptor, PlannedAction |
| TBD | casehub-desiredstate | Depend on casehub-worker-api; evolve pipeline example (#27) |

## Open questions

None — all tensions resolved during brainstorming.
