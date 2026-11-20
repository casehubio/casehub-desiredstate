# Decisions — #231 D9 GroupLifecycleService

## D1: Synchronisation model

**Choice:** Fire-and-forget — mutations return void (or lightweight identity for create operations), callers observe provisioning results via events (GroupLifecycleListener) or by reading actual state later.
**Alternatives:**
- Synchronous reconciliation — blocks until provisioning completes. Simpler for callers but defeats the decoupled desired-state model.
- Return desired intent — returns DesiredNode/intent record. Intermediate but doesn't match either paradigm cleanly.
**Rationale:** Matches the desired-state philosophy. GroupLifecycleService declares intent; reconciliation delivers reality. Events provide the observation channel.
**Trade-offs:** Callers can't assume the mutation is applied when the method returns. Code that reads GroupInstance immediately after createGroup() will see stale state.
**Sources:** ReconciliationLoop.updateDesired() API, spec §6.1
**Exploration:** quick
**Status:** captured

## D2: Graph mutation approach

**Choice:** Always compile through OrgGoalCompiler. Store OrgGoal as intent. Mutation → update OrgGoal → recompile → updateDesired(). Single code path.
**Alternatives:**
- Direct graph mutation only — fast, fine-grained, but graph can diverge from compiler output with no correction mechanism.
- Hybrid (direct + periodic recompile) — **REVISED AWAY** after decision review. Premature complexity: OrgGoalCompiler is O(n) at 10-200 nodes (microseconds). Dual code paths guarantee divergence. The recompile "safety net" is an admission the direct path will be wrong.
**Rationale:** Single code path. Compiler invariants always apply. No divergence possible. Eliminates the need for D4 (recompile source) and D5 (recompile trigger) entirely. OrgGoal storage is trivial — List<OrganizationalUnit> + List<AgentRelationship>.
**Trade-offs:** Every mutation pays compilation cost (O(n), microseconds at current scale). Requires OrgGoalStore for intent persistence. Assumption: OrgGoalCompiler remains cheap and side-effect-free.
**Sources:** OrgGoalCompiler.compile(), decision review R1-03
**Exploration:** quick → revised after decision review (light)
**Status:** revised

## D3: Interface change

**Choice:** Change GroupLifecycleService interface — createGroup/proposeGroup return lightweight identity (see D6). Other mutations already return void. Breaking change is smaller than initially framed.
**Alternatives:**
- Keep interface, change impl — return synthetic GroupInstance (FORMING state). No breaking change but semantically dishonest — the returned instance doesn't exist in the store yet.
**Rationale:** Returning GroupInstance from a desired-state mutation is misleading. Most mutations (joinGroup, dissolveGroup, changeTopology, adoptRole, etc.) already return void — only createGroup and proposeGroup need their return type resolved.
**Trade-offs:** Breaking change to createGroup/proposeGroup in eidos-org-api (cross-repo). Callers of these two methods need updating.
**Sources:** GroupLifecycleService interface in eidos-org-api, decision review R1-04, R1-09
**Exploration:** quick → refined after decision review (light)
**Status:** revised

## ~~D4: Recompilation source~~ DROPPED

**Dropped:** Unnecessary with always-compile approach (revised D2). The OrgGoal store IS the intent source. No recompilation from OrgRegistry needed.
**Original choice:** OrgRegistry as recompile source.
**Why dropped:** Decision review R1-02 identified this as a data flow inversion — OrgRegistry is actual state (populated by provisioning), not intent. Recompiling from it overwrites desired state with reality.
**Status:** dropped

## ~~D5: Recompilation trigger~~ DROPPED

**Dropped:** Unnecessary with always-compile approach (revised D2). Every mutation compiles — there's nothing to periodically correct.
**Original choice:** Startup + fault recovery.
**Why dropped:** D5 depended on D2 (hybrid) and D4 (OrgRegistry source). Both revised/dropped. Decision review R1-08 also identified SituationRecompiler as the wrong mechanism for consistency correction.
**Status:** dropped

## D6: Return type for create operations

**Choice:** Return lightweight identity — CreateGroupResult(String instanceId). Server generates ID, returns it synchronously. Provisioning happens async.
**Alternatives:**
- Client-generated ID (Kubernetes pattern) — caller provides the ID. Clean but changes the creation API surface.
- Event-based discovery — caller waits for GroupLifecycleListener event. Maximally decoupled but forces async model for a synchronous "give me an ID" operation.
**Rationale:** Callers need instanceId immediately for subsequent calls (joinGroup(instanceId, ...), adoptRole(instanceId, ...)). Returning an ID is practical without implying the group exists in the store yet.
**Trade-offs:** Introduces CreateGroupResult type. Server generates and persists the ID before provisioning — the ID exists in the OrgGoal store even before OrgNodeProvisioner runs.
**Depends on:** D1 (fire-and-forget), D3 (interface change)
**Sources:** Decision review R1-04
**Exploration:** quick
**Status:** captured

## D7: Layering — provisioner writes to stores directly

**Choice:** OrgNodeProvisioner writes to OrgRegistry and GroupInstanceStore directly. GroupLifecycleService writes to the desired graph only. No circular delegation.
**Alternatives:**
- OrgNodeProvisioner delegates to GroupLifecycleService — creates a cycle: GroupLifecycleService → graph → reconciliation → OrgNodeProvisioner → GroupLifecycleService → graph → ... Requires context-sensitive behavior (skip graph write when called from reconciliation).
**Rationale:** This is what provisioners do in every other domain (DungeonProvisioner writes to DungeonWorld, PipelineProvisioner writes to PipelineWorld). The provisioner IS the bridge between desired and actual. Delegating back to the intent-declaring service is a layering violation.
**Trade-offs:** Clarifies that #234's scope is ensuring OrgNodeProvisioner is the ONLY store writer (rather than making it delegate to GroupLifecycleService).
**Sources:** Decision review R1-06, existing domain provisioner patterns
**Exploration:** quick
**Status:** captured
