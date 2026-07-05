# Aggregate Fault Detection via RAS Integration

**Issue:** casehubio/casehub-desiredstate#59
**Epic:** #61
**Date:** 2026-07-04
**Status:** Design

## 1. Problem Statement

The spatial/vector POC (#57) documented six findings across three failure layers.
The root cause is architectural: the reconciliation loop operates exclusively on
individual nodes. There is no phase where the system reasons about groups of nodes,
detects patterns across fault events, or evaluates aggregate outcomes.

**Finding #3 — Fault policy information gap:** `FaultPolicy.onFault(FaultEvent,
DesiredStateGraph)` is blind to actual state. When a zone is DRIFTED, the policy
cannot determine which child unit was lost. The `ZoneRebalanceFaultPolicy`
demonstrates this by returning empty — it cannot do its job.

**Finding #9 — Planner/policy conflict:** `detectDrift()` fires fault policies
before the planner runs. The policy might redistribute, but the planner
independently detects the ABSENT unit and re-provisions with the original spec.
No mechanism exists for the policy to say "don't re-provision this node."

**Findings #4, #5, #6 — No aggregate evaluation:** Three consecutive losses at
the same position are three independent fault events. No correlated fault detection,
no "this approach is failing" signal, no strategic alternatives. The system handles
any individual failure but cannot reason about whether an entire approach is
succeeding or failing.

### Architectural separation

These findings split into two distinct concerns:

- **Tactical (node-level):** FaultPolicy lacks information to make good individual
  decisions. Fix: give it ActualState. Finding #3.
- **Strategic (aggregate-level):** No mechanism for pattern detection, correlated
  faults, or strategic alternatives. Fix: integrate with the platform's situation
  detection infrastructure (RAS). Findings #4, #5, #6.

Finding #9 (planner/policy conflict) is resolved by this separation: FaultPolicy
handles tactical reactions; the situation layer handles strategic patterns. No
planner-policy coordination mechanism needed.

## 2. FaultPolicy SPI Fix

Add `ActualState` as the third parameter:

```java
// Before
interface FaultPolicy {
    List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current);
}

// After
interface FaultPolicy {
    List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual);
}
```

**Propagation:**

| Component | Change |
|-----------|--------|
| `FaultPolicyEngine.evaluate()` | Accept and pass through `ActualState` |
| `ReconciliationLoop.detectDrift()` | Pass `actual` (already in scope) |
| `ReconciliationLoop.faultFeedback()` | Add `ActualState` parameter; pass the pre-execution snapshot (read at step 1, already in `reconcile()`/`reconcileTypes()` scope) |
| All 5 domain FaultPolicy implementations | Add parameter; most ignore it initially |
| `FaultPolicyEngineTest` inline lambdas | Add parameter |

**Breaking change.** All `FaultPolicy` implementations must add the parameter.
Migration is mechanical. The policy was missing essential information — this is
the right design.

**Issue:** #62

## 3. CloudEvent Emission from ReconciliationLoop

The reconciliation loop becomes an event emitter. After each cycle completes, it
emits CloudEvents that RAS subscribes to. The loop gains no knowledge of situations,
ganglia, or responses.

### Event types

| CloudEvent type | Subject | When | Data payload |
|----------------|---------|------|-------------|
| `io.casehub.desiredstate.reconciliation.completed` | tenancyId | End of every cycle | `ReconciliationCompletedData` |
| `io.casehub.desiredstate.node.faulted` | nodeId | Per failed/rejected step outcome | `NodeFaultedData` |
| `io.casehub.desiredstate.node.drifted` | nodeId | Per drifted node detected | `NodeDriftedData` |
| `io.casehub.desiredstate.node.recovered` | nodeId | Per previously-faulted/drifted node now PRESENT | `NodeRecoveredData` |

**CloudEvent extension attributes.** Every emitted event carries `tenancyid` as a
CloudEvent extension attribute (not just in the data payload). `RasEngine` routes
events by reading `event.getExtension("tenancyid")` and drops events without it.

### Data payload types

Structured record types define the emitter/consumer contract:

```java
record ReconciliationCompletedData(String tenancyId, long graphVersion,
    int nodeCount, int additionsCount, int removalsCount, int faultCount,
    Instant timestamp) {}

record NodeFaultedData(String tenancyId, String nodeId, String nodeType,
    String faultType, String reason, long graphVersion,
    String parentNodeId) {}

record NodeDriftedData(String tenancyId, String nodeId, String nodeType,
    long graphVersion, String parentNodeId) {}

record NodeRecoveredData(String tenancyId, String nodeId, String nodeType,
    long graphVersion, String parentNodeId) {}
```

`parentNodeId` is the node's direct parent in the dependency graph (null for root
nodes). The reconciliation loop resolves this from `DesiredStateGraph.dependencies()`
before emitting. This enables the `DesiredStateCorrelationKeyExtractor` to derive
zone-level correlation keys from the CloudEvent data alone, without graph access.

### Design choices

**Per-node events, not a single fat event.** RAS Ganglia subscribe to specific
event types. A `NodeFaultGanglion` subscribes to `node.faulted` and counts
occurrences per correlation key. Per-node events match CloudEvents' grain.

**Correlation key.** The CloudEvent `subject` field carries the node ID. Ganglia
that need zone-level correlation extract the zone from the event data (the zone's
node ID is derivable from graph dependencies). A custom `CorrelationKeyExtractor`
handles this mapping.

**Emission timing — batch at cycle end.** All events are emitted in a post-cycle
emission phase after `faultFeedback()` completes. `node.drifted` events detected
during `detectDrift()` (step 2) are collected and emitted at cycle end, not
mid-cycle. This avoids emitting events for transient states that the planner
corrects within the same cycle. CDI `@ObservesAsync` provides no ordering
guarantees between events from the same cycle; RAS processes events independently.

**Recovery tracking.** `TenantLoop` maintains a `Set<NodeId>` of nodes with active
problems (faulted or drifted). At the start of each cycle's emission phase, nodes
in this set that are now PRESENT in actual state produce `node.recovered` events.
The set is updated with newly faulted/drifted nodes after emission.

**Emission mechanism.** CDI `Event<CloudEvent>` fired from the reconciliation
loop's `reconcile()` and `reconcileTypes()` methods. Async observation in RAS
means the loop is not blocked.

**No `ReconciliationOutcome` type.** CloudEvents carry the data. No
desiredstate-specific outcome record needed.

**Issue:** #63

## 4. RAS Integration

The platform's RAS (Reasoning and Assessment System) provides the complete
detection-to-trigger pipeline. Desiredstate plugs in via domain-specific Ganglia,
situation definitions, and correlation key extraction.

### RAS pipeline (existing infrastructure)

```
CloudEvent → RasEngine → Ganglion.detect() → DetectionResult
    → SituationContext accumulation → RasTriggerPolicy
    → TriggerDecision → CaseTrigger.fire() → CaseHub case
```

Key RAS capabilities used:
- **Ganglia** — pluggable detectors, receive CloudEvents, return DetectionResult
  with confidence (NOISE / ANTI / WEAK / DETECTED)
- **ChainMode** — composable trigger logic (And / Or / Threshold / Sequence /
  Count / Streak / Rate)
- **SituationContext** — persistent accumulation of detections over time per
  correlation key
- **SituationStore** — persistent storage with conflict-retrying CAS
- **TriggerMode** — FireOnce or Repeating with cooldown
- **CaseTrigger** — fires a CaseHub case when a situation triggers

### Desiredstate Ganglia

Implemented as `JavaSwitchGanglion` subclasses in `ras-adapter/`.

| Ganglion | Handles event type | Signal logic |
|----------|-------------------|--------------|
| `NodeFaultGanglion` | `node.faulted`, `node.recovered` | DETECTED on fault; ANTI on recovery |
| `PersistentDriftGanglion` | `node.drifted`, `node.recovered` | DETECTED on drift; ANTI on recovery |

### Situation definitions

Declared via `SituationDefinitionProvider` SPI. Each provider returns
`List<SituationRegistration>`, where `SituationRegistration` bundles a
`SituationDefinition` with a `CorrelationKeyExtractor`. This is the composition
point where definition and extractor are paired.

**Per-node situations** use the default extractor (`DefaultCorrelationKeyExtractor`,
which returns `event.getSubject()` = node ID). These can use YAML configuration
via `YamlSituationDefinitionProvider`.

**Zone-level situations** require the custom `DesiredStateCorrelationKeyExtractor`
and must be registered programmatically via `DesiredStateSituationDefinitionProvider`.

**Per-node situations (YAML — default extractor):**

```yaml
# Repeated node failure: 3 consecutive faults without recovery (Streak — ras#25)
- situationId: desiredstate.repeated-failure
  eventTypes:
    - io.casehub.desiredstate.node.faulted
    - io.casehub.desiredstate.node.recovered
  correlationWindow: PT10M
  chainMode:
    streak:
      ganglionId: node-fault
      requiredCount: 3
  triggerConfig:
    caseNamespace: desiredstate
    caseName: replan
    caseVersion: "1.0"
  triggerMode:
    fireOnce: {}

# Persistent drift: same node drifts 3+ times within window
- situationId: desiredstate.persistent-drift
  eventTypes:
    - io.casehub.desiredstate.node.drifted
    - io.casehub.desiredstate.node.recovered
  correlationWindow: PT15M
  chainMode:
    count:
      ganglionId: persistent-drift
      requiredCount: 3
  triggerConfig:
    caseNamespace: desiredstate
    caseName: escalate
    caseVersion: "1.0"
  triggerMode:
    fireOnce: {}
```

**Zone-level situation (programmatic — custom extractor):**

Zone degradation requires the `DesiredStateCorrelationKeyExtractor` to map
node-level events to zone-level correlation. YAML cannot specify custom
extractors, so this must be registered programmatically in
`DesiredStateSituationDefinitionProvider`:

```java
// Zone degradation rate: ≥60% fault rate across 10 events per zone (Rate — ras#26)
new SituationRegistration(
    new SituationDefinition(
        "desiredstate.zone-degradation",
        Set.of("io.casehub.desiredstate.node.faulted",
               "io.casehub.desiredstate.node.recovered"),
        Duration.ofMinutes(30),        // correlationWindow
        null,                          // eventBufferDelay
        new ChainMode.Rate(            // requires ras#26
            List.of("node-fault"), 0.6, 10),
        new CaseTriggerConfig("desiredstate", "escalate", "1.0", Map.of()),
        new TriggerMode.Repeating(Duration.ofMinutes(5))
    ),
    new DesiredStateCorrelationKeyExtractor()
)
```

### Correlation key extraction

Custom `DesiredStateCorrelationKeyExtractor` maps node-level events to zone-level
correlation. For per-node situations, the default extractor (event subject = node
ID) works. For zone-level detection, the extractor reads the parent zone from the
event's data payload.

### Response cases

RAS triggers CaseHub cases. The case IS the response — no separate
`SituationResponder` SPI needed.

| Case | Purpose | Workflow |
|------|---------|----------|
| `desiredstate/replan` | GoalCompiler recompilation | Domain retrieves goals, modifies based on situation context, recompiles, calls `updateDesired()` |
| `desiredstate/escalate` | Human escalation | Creates WorkItem for operator review |

**SituationRecompiler SPI** — situation-driven graph recompilation, independent
of GoalCompiler. GoalCompiler stays pure (goals → graph). SituationRecompiler
is a separate concern (current graph + situation → possibly a new graph). Domains
implement both — the recompiler might internally call the GoalCompiler with
modified goals, mutate the graph directly, or call CBR. That's the domain's business.

```java
interface SituationRecompiler {
    Optional<DesiredStateGraph> recompile(
        DesiredStateGraph current,
        ActiveSituation situation,
        DesiredStateGraphFactory factory);
}
```

`NoOpSituationRecompiler` (`@DefaultBean`) returns empty until a domain provides
a real implementation. `DesiredStateReplanDispatch` registers `desiredstate:replan`
via `CallableDispatchRegistry` — the bridge between "RAS triggered a case" and
"the case calls SituationRecompiler."

**New runtime API:**

```java
// ReconciliationLoop — read access for response cases
public DesiredStateGraph getDesired(String tenancyId);
// updateDesired() already exists
```

**Issues:** #64 (ras-adapter), #65 (getDesired), #68 (replan dispatch)

## 5. RAS Capability Verification

Rigorous verification of each desiredstate detection requirement against RAS
mechanics.

| Requirement | RAS mechanism | Verdict |
|-------------|--------------|---------|
| Repeated failures (count) | `ChainMode.Count` + `correlationWindow` | ✅ Direct |
| Consecutive failures (reset on success) | `ChainMode.Streak` (ras#25) | ✅ With ras#25; Count + window as interim |
| Persistent drift | `desiredstate.persistent-drift`: `ChainMode.Count` + `correlationWindow` | ✅ Direct |
| Aggregate failure rate | `ChainMode.Rate` (ras#26) | ✅ With ras#26; Threshold as interim (net-count) |
| Correlated failures (same zone) | Zone-correlated Count via custom `CorrelationKeyExtractor` | ✅ Direct |
| Repeating monitoring | `TriggerMode.Repeating(cooldown)` | ✅ Direct |
| Strategic response | `CaseTrigger` → case workflow | ✅ Direct |

**ANTI signal semantics.** `DefaultRasTriggerPolicy.countQualifying()` filters
to `isAtLeast(WEAK)` — ANTI (ordinal 1) is excluded from Count. ANTI does NOT
reset Count. For Threshold, ANTI contributes negative confidence.
`ChainMode.Streak` (ras#25) adds ANTI-reset semantics.

## 6. Precursor Type Cleanup

Remove types from `casehub-desiredstate` that are superseded by the canonical
RAS versions in `casehub-ras-api`. These were early precursor types (e.g.
desiredstate `ActiveSituation` has 4 fields; the RAS version has 8) with zero
production references.

**API types (api/):**
- `ActiveSituation.java`
- `SituationSource.java`
- `SituationChangeEvent.java`

**Runtime dependents (must be deleted alongside):**
- `DefaultSituationSource.java` (`@DefaultBean @ApplicationScoped`, implements `SituationSource`)
- `DefaultSituationSourceTest.java`
- `ActiveSituationTest.java`

**Issue:** #66

## 7. Spatial POC Proof

### FaultPolicy fix proof

`ZoneRebalanceFaultPolicy` updated to use `ActualState`:
- Inspects `actual.statusOf(unitId)` to find the ABSENT unit
- Emits `UpdateNode` mutations to redistribute zone allocation among surviving
  units
- `ForceDistributionTest.faultPolicyInformationGap()` asserts non-empty mutations

### RAS integration proof

New `SituationDetectionTest`:
- 3 reconciliation cycles, each destroying the same unit
- Verifies CloudEvents emitted per cycle
- Verifies `NodeFaultGanglion` + `ChainMode.Streak(3)` produces
  `TriggerDecision.CREATE_CASE`
- Verifies situation context carries correct evidence
- Uses `MockCaseTrigger` (ras-testing) — no full CaseHub runtime

### Strategic pivot updated

`strategicPivotRequiresExternalIntervention()` updated: the pivot decision now
has a home (RAS situation → replan case). Asserts situation is triggered with
correct evidence.

**Issue:** #67

## 8. Module Structure

### New module: `ras-adapter/`

| Property | Value |
|----------|-------|
| Artifact | `casehub-desiredstate-ras` |
| Package | `io.casehub.desiredstate.ras` |
| Purpose | CloudEvent emission bridge, Ganglia, CorrelationKeyExtractor, SituationDefinitionProvider |

**Dependencies:** casehub-desiredstate-api, casehub-desiredstate, casehub-ras-api,
casehub-ras (runtime — required for `SituationDefinitionProvider`,
`SituationRegistration`, `CorrelationKeyExtractor` which are currently in
`io.casehub.ras.runtime`), casehub-ras-testing (test), quarkus-arc (provided),
cloudevents-api (provided).

**Classpath activation.** CDI discovers beans when ras-adapter is on the classpath.
Same pattern as engine-adapter and work-adapter.

### Component placement

| Component | Module |
|-----------|--------|
| `FaultPolicy` signature change | api/ |
| `FaultPolicyEngine` ActualState propagation | runtime/ |
| CloudEvent emission from ReconciliationLoop | runtime/ |
| CloudEvent data payload types | api/ |
| `ReconciliationLoop.getDesired()` | runtime/ |
| `NodeFaultGanglion` | ras-adapter/ |
| `PersistentDriftGanglion` | ras-adapter/ |
| `DesiredStateCorrelationKeyExtractor` | ras-adapter/ |
| `DesiredStateSituationDefinitionProvider` | ras-adapter/ |
| Duplicate type deletion | api/ |
| `ZoneRebalanceFaultPolicy` update | examples/spatial/ |
| `SituationDetectionTest` | examples/spatial/ |

## 9. Cross-Repo Dependencies

| Issue | Repo | Breaking? | Interim |
|-------|------|-----------|---------|
| ras#25 | casehub-ras | Yes (sealed ChainMode) | Count + correlationWindow |
| ras#26 | casehub-ras | Yes (sealed ChainMode) | Threshold (net-count) |
| ras#27 | casehub-ras | No (move, not remove) | ras-adapter depends on casehub-ras runtime |
| engine#652 | casehub-engine | Additive | Namespace/name convention |

**ras#27 — Promote SPI types to casehub-ras-api.** `CorrelationKeyExtractor`,
`SituationDefinitionProvider`, and `SituationRegistration` are extension points
that domain modules implement, but they live in `io.casehub.ras.runtime` instead
of `io.casehub.ras.api`. This forces domain adapter modules to depend on the
runtime. Non-blocking: ras-adapter works with the runtime dependency; the
promotion cleans up the module boundary.

## 10. CBR Convergence

CBR integration (#23) slots into this architecture naturally:

- **As a Ganglion:** A `CbrGanglion` uses `CaseRetriever` to find similar past
  reconciliation situations. Returns DETECTED with high confidence when a
  similar past failure was resolved by a specific response.
- **As part of the response case:** The replan case workflow uses `CaseRetriever`
  to find past successful reconfigurations for the current situation and adapts
  the GoalCompiler input.

One canonical situation format: RAS `ActiveSituation`. No parallel type hierarchy.

## 11. Design Decisions

| Decision | Rationale |
|----------|-----------|
| Detection in RAS, not desiredstate | RAS has the full pipeline: Ganglia, ChainMode, SituationStore, CaseTrigger. Building parallel assessment SPIs creates divergence with CBR. |
| Response is a CaseHub case | Durability, audit trail, HITL, tracing — all free. CaseTrigger + case workflow IS the SituationResponder. |
| FaultPolicy = tactical, RAS = strategic | Separation of concerns resolves Finding #9 without coordination plumbing. |
| CloudEvents as the observation contract | Standard format. CDI event emission for same-deployment. Message broker for cross-deployment (future). |
| Goal retention = domain responsibility | Runtime stores compiled graphs. GoalCompiler<G> is parameterized — the runtime can't store type-erased goals cleanly. Domains own their goal storage. |
| No ReconciliationOutcome type | CloudEvents carry the data. A desiredstate-specific outcome record adds a type without architectural benefit. |
| Streak and Rate as RAS core improvements | Workarounds (custom TriggerPolicy for what should be core) are bad design. Breaking the sealed ChainMode is the right answer. |

## Out of Scope

- RAS core changes (ras#25, ras#26) — separate repo, separate issues
- Engine CaseDefinition labels (engine#652) — separate repo
- CBR integration (#23) — future, slots in via Ganglia or response case
- Runtime factoring (#60) — separate issue, informed by this design
- Replan case definition and workflow details — #68
- Cross-deployment event routing (message broker) — future infrastructure concern
