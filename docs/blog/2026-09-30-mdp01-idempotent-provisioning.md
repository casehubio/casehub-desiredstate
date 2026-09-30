---
layout: post
title: "Idempotent provisioning — teaching the runtime to say 'nothing to do'"
date: 2026-09-30
entry_type: note
subtype: diary
projects: [casehubio/casehub-desiredstate]
tags: [idempotent, provisioning, cloudevents, sealed-types]
series: issue-157-idempotent-provisioning
---

The desired-state runtime had a blind spot: every provision call returned Success or Failed. A provisioner that checked the world and found the node already at the desired spec had to lie — return Success even though it did nothing. Operationally, "38 already converged, 4 provisioned, 0 failed" is far more useful than "42 succeeded, 0 failed." The distinction matters most at scale, where the majority of reconciliation cycles are re-confirming existing state.

`ProvisionResult.AlreadyConverged` is the fix. A provisioner checks actual state first, and if the node already matches the desired spec, returns AlreadyConverged instead of provisioning again. The result type propagates through the entire pipeline as `StepOutcome.AlreadyConverged` — distinct from Succeeded so the reconciliation loop can emit a separate `NODE_ALREADY_CONVERGED` CloudEvent. Consumers watching the event stream can now distinguish real mutations from idempotent no-ops.

The sealed interface design made this straightforward. Adding a new variant to `ProvisionResult` and `StepOutcome` forced every exhaustive switch in the codebase to handle it — the compiler caught every site. Claude flagged `CbrProposalTracker` as having an exhaustive switch I'd overlooked in the initial scan; that tracker counts AlreadyConverged as success (correct — the node is in the right state).

The runtime handling is minimal by design. `NodeStepExecutor` maps the result and still runs post-provision hooks — a node that's already converged still deserves its health check. `StatefulNodeProvisioner` transitions to PRESENT, same as Success. `ParallelTransitionExecutor.isFailed()` ignores it. The fault feedback loop ignores it. These are all correct: AlreadyConverged is a success with extra metadata, not a new category of outcome.

I updated both teaching examples — dungeon and pipeline — to demonstrate the check-before-dispatch pattern. The dungeon provisioner checks `roomState() == BUILT` before calling `setRoom()`; the pipeline checks `hasSource()` and `hasSchema()`. Simple guards that return early. This is the pattern consumer provisioners should follow.

The IoT consumer requirement that surfaced this (#153) wants exactly this signal for fleet reconciliation dashboards. When you're managing 10,000 devices and 9,996 are already at the desired state, you don't want 10,000 "provisioned" events — you want 4 "provisioned" and 9,996 "already converged."
