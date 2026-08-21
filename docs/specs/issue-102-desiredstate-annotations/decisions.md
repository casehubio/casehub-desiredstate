# Design Decisions — #102 desiredstate-annotations

## D1: NodeSpec gains nodeType()

**Choice:** Add abstract `NodeType nodeType()` to `NodeSpec` interface — every implementation must declare its node type
**Alternatives:**
- Keep NodeSpec as marker, put type info only in annotations — patches a design gap instead of fixing it
- Default implementation deriving from class name convention — fragile, magic
**Rationale:** A spec IS a specific type of node. That relationship is intrinsic. Making it explicit enables compile-time type safety, simplifies GoalCompiler boilerplate, and makes the annotation model natural (return type carries type info).
**Trade-offs:** Breaking change across ~50 NodeSpec implementations in desiredstate + ops repos. Mechanical migration.
**Sources:** NodeSpec.java, DesiredNode.java, PipelineGoalCompiler.java, ops/api/ NodeSpec implementations
**Exploration:** deep-analysis
**Status:** captured

## D2: DesiredNode loses type field

**Choice:** Remove `type` from DesiredNode record, derive via `spec.nodeType()` method
**Alternatives:**
- Keep type field with validation against spec.nodeType() — backward compatible but redundant
**Rationale:** Eliminates redundancy. The type is always spec.nodeType(). Keeping both invites inconsistency. Pre-release platform — clean design over backward compat.
**Trade-offs:** Every DesiredNode construction site changes. All callers of `new DesiredNode(id, type, spec, gating)` become `new DesiredNode(id, spec, gating)`.
**Sources:** DesiredNode.java, all GoalCompiler implementations, FaultPolicy.addReviewNode, GraphMutation
**Exploration:** deep-analysis
**Depends on:** D1 (NodeSpec.nodeType() must exist)
**Status:** captured

## D3: No langchain4j-agentic dependency

**Choice:** Module depends on desiredstate-api only, no langchain4j-agentic
**Alternatives:**
- Include langchain4j-agentic per epic template — no LC4j annotations compose onto graph declarations
**Rationale:** Desiredstate is infrastructure reconciliation, not LLM agent orchestration. None of LC4j's 36 annotations apply to graph structure declaration.
**Sources:** blocks#115 epic, langchain4j-agentic annotation surface
**Exploration:** quick
**Status:** captured

## D4: @DesiredState produces GoalCompiler<Void>

**Choice:** Build extension generates a synthetic `GoalCompiler<Void>` CDI bean that returns `CompilationResult.SingleGraph`
**Alternatives:**
- Generate DesiredStateGraph bean directly — bypasses GoalCompiler SPI, requires new runtime discovery path
- Generate CompilationResult bean — still needs new discovery path
**Rationale:** Matches engine-annotations pattern (annotations → same types as builders). Runtime unchanged — it discovers GoalCompiler beans via CDI as before.
**Sources:** GoalCompiler.java, CompilationResult.java, EngineAnnotationsProcessor.java, CaseDefinitionRecorder.java
**Exploration:** quick
**Status:** captured

## D5: @Node methods with default bodies provide NodeSpec

**Choice:** Each `@Node("id")` method is a default method returning a NodeSpec implementation. Build extension calls methods at runtime init to get spec values.
**Alternatives:**
- NodeSpec as annotation attributes — can't express typed records, limited to primitives
**Rationale:** Follows engine-annotations pattern where @Worker method IS the worker implementation. Method bodies support CDI injection for config values. Return type carries nodeType() — no type= attribute needed on the annotation.
**Sources:** EngineAnnotationsProcessor.java, @Worker method semantics in engine-annotations
**Exploration:** deep-analysis
**Depends on:** D1 (return type's nodeType() provides the type)
**Status:** captured

## D6: SingleGraph only for v1

**Choice:** Annotations produce `CompilationResult.SingleGraph` only. Lifecycle (multi-phase) stays as GoalCompiler concern.
**Alternatives:**
- Support @Phase annotation for lifecycle compilation — significant complexity, not in issue scope
**Rationale:** Issue scope is @DesiredState + @NodeSpec + @DependsOn + @FaultPolicy. Lifecycle phases require phase ordering, completion conditions — better served by GoalCompiler or future annotation extension.
**Sources:** CompilationResult.java (SingleGraph | Lifecycle), issue #102 scope
**Exploration:** quick
**Status:** captured

## D7: @FaultPolicy at interface and method level

**Choice:** `@FaultPolicy` supported on the interface (with nodeTypes filter) and on `@Node` methods (scoped to that node's type). Each `@Tier` references a review spec factory method by name.
**Alternatives:**
- @FaultPolicy only at interface level — loses node-specific scoping
- @FaultPolicy only at method level — verbose when the same policy applies to multiple types
**Rationale:** Interface-level for broad policies, method-level for node-specific ones. Method references for ReviewSpecFactory keep the annotation model clean while preserving full flexibility in spec creation.
**Trade-offs:** Custom fault policies (with world-state checks) stay as hand-written code — annotations handle ThresholdFaultPolicy only.
**Sources:** ThresholdFaultPolicy.java, FaultPolicy.addReviewNode, SchemaDriftFaultPolicy.java, QuarantineFaultPolicy.java
**Exploration:** deep-analysis
**Status:** captured

## D8: Module structure — runtime/ + deployment/

**Choice:** `annotations/runtime/` (annotations + descriptors, depends on desiredstate-api) + `annotations/deployment/` (Quarkus build extension). Parent POM at `annotations/`.
**Alternatives:**
- Annotations in api/ module — violates epic pattern, mixes concerns
- Single module — can't separate build-time from runtime
**Rationale:** Follows engine-annotations pattern exactly. Runtime has no Quarkus deployment dependency. Deployment has Jandex, Gizmo, SyntheticBeanBuildItem.
**Sources:** engine/annotations/ module structure, blocks#115 epic design principles
**Exploration:** quick
**Status:** captured

## D9: @Customize escape hatch

**Choice:** Follow engine-annotations `@Customize` pattern — static method receiving the generated builder (GoalCompiler result or ThresholdFaultPolicy.Builder) before CDI registration.
**Alternatives:**
- No escape hatch — forces complex configs to abandon annotations entirely
**Rationale:** Eliminates the annotation cliff. Same principle as engine-annotations: annotations handle 80%, @Customize handles the tail.
**Sources:** engine-annotations @Customize, CaseDefinition.Builder
**Exploration:** quick
**Status:** captured
