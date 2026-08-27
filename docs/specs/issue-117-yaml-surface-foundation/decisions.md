# Decisions — #117 YAML Surface Foundation

## D1: Module structure

**Choice:** `yaml/runtime/` + `yaml/deployment/` — mirrors the `annotations/` Quarkus extension pattern
**Alternatives:**
- Single `yaml/` module without runtime/deployment split — simpler but breaks Quarkus extension convention, mixes build-time and runtime code
**Rationale:** Quarkus extensions require the split for build-time classpath scanning vs runtime code. Established pattern in this repo (`annotations/runtime/` + `annotations/deployment/`).
**Trade-offs:** Two modules instead of one, but this is the Quarkus-mandated structure. The runtime YAML path (visual editor, REST endpoint — when it arrives) will need separate plumbing outside the deployment module's build-step pipeline.
**Sources:** `annotations/runtime/pom.xml`, `annotations/deployment/pom.xml`
**Exploration:** quick
**Status:** captured

## D2: NodeDescriptor extension for YAML nodes

**Choice:** Add `InlineNode(String id, String specClassName, Map<String, Object> specValues, HumanGating humanGating)` to the sealed `NodeDescriptor` interface
**Alternatives:**
- `JsonNode` instead of `Map<String, Object>` for specValues — adds Jackson tree-model dependency to descriptor records
- Bypass GraphDescriptor entirely, YAML creates GoalCompiler directly — duplicates recorder logic, contradicts §5.5 convergence design
- Refactor GraphDescriptor into a surface-neutral IR (extract annotation-specific fields into a wrapper) — cleaner design but larger refactoring scope, not justified until a third surface is added
**Rationale:** `Map<String, Object>` is bytecode-recording friendly. `specClassName` is resolved at build time by the type registry. At runtime, the recorder uses `ObjectMapper.convertValue(resolvedValues, specClass)` to create NodeSpec instances. GraphDescriptor is annotation-centric today (3 of 10 fields are annotation-only: `interfaceName`, `implClassName`, `goalMethod`), but extending it is pragmatic — refactoring to a surface-neutral IR is a future option when a third surface (TypeScript DSL) justifies the cost.
**Trade-offs:** GraphDescriptor's annotation-specific fields are null for YAML graphs. Each NodeDescriptor variant is surface-specific (InterfaceNode = annotations, ClassNode = @DeclareNode, InlineNode = YAML). The sealed interface is a tagged union of surface data rather than a shared abstraction. Acceptable until the surface count grows.
**Sources:** `NodeDescriptor.java`, `DesiredStateGraphRecorder.java`, research doc §5.5
**Exploration:** quick
**Status:** captured

## D3: Variable substitution timing

**Choice:** Resolve at `GoalCompiler.compile()` time (RUNTIME_INIT), not at recorder init (bytecode recording)
**Alternatives:**
- Resolve at Quarkus augmentation (build-time) — Config/Preferences not available yet
- Resolve lazily at reconciliation time within the graph — would require a different graph model
**Rationale:** `GoalCompiler.compile()` runs once at Quarkus RUNTIME_INIT, not per reconciliation cycle. The reconciliation loop operates on the pre-compiled graph. Variables resolved at compile() time are frozen for the lifetime of the graph. This is the same behavior as the annotation path — graphs are compiled once. Re-resolution requires explicit recompilation via `LifecycleManager.updateDesired()` or `SituationRecompiler.recompile()`. Compile-time resolution is correct because MicroProfile Config and Platform Preferences are both available at RUNTIME_INIT but not at augmentation time.
**Trade-offs:** Variable values do not automatically track Config/Preferences changes at runtime. A preference change after startup requires explicit recompilation to take effect. This matches the existing architecture — no GoalCompiler in the codebase re-invokes per cycle.
**Sources:** `ReconciliationLoop.java` (takes pre-compiled DesiredStateGraph), `LifecycleManager.java` (compile() called once at start), `ExpansionLifecycleTest.java` (single compile() invocation pattern)
**Exploration:** quick
**Status:** revised (R1-01 corrected factual error in rationale)

## D4: Variable resolution chain

**Choice:** Three-layer fallthrough: Map (inline/contextual) → Platform Preferences (tenant-aware) → MicroProfile Config (environment/properties)
**Alternatives:**
- MicroProfile Config only — misses tenant-aware overrides and inline variables
- Custom variable context — invents a new API when the platform already has one
**Rationale:** Most-specific-wins override chain. Map layer comes from the YAML `variables:` section or programmatic bindings. Preferences provide tenant-scoped values available at RUNTIME_INIT. Config provides the global baseline. All three layers are available when compile() executes.
**Trade-offs:** Three-layer lookup per variable, but simple short-circuit on first hit. Variable values are frozen after compile() — Preferences changes after startup do not automatically propagate. Recompilation (via LifecycleManager or SituationRecompiler) is the mechanism for picking up changes.
**Sources:** `DesiredStatePreferenceKeys.java`, `io.casehub.platform.api.preferences.PreferenceKey`
**Exploration:** quick
**Depends on:** D3 (variables resolved at compile time, frozen until recompilation)
**Status:** revised (R1-04 corrected runtime override language)

## D5: Type registry design

**Choice:** Jandex-discovered NodeSpec implementations at build time. New `@NodeType("data-source")` annotation on NodeSpec classes provides the type string without instantiation. Registry is `Map<String, String>` (nodeType → className) passed to recorder, converted to `Map<String, Class<? extends NodeSpec>>` at startup. Build-time validation catches duplicates and unknown type references in YAML files.
**Alternatives:**
- Runtime-only discovery via CDI `Instance<NodeSpec>` — misses build-time validation, doesn't work for records that aren't CDI beans
- Probe `nodeType()` by instantiating NodeSpec at build time — records like `DataSourceSpec(String, String, String)` lack no-arg constructors, making instantiation impractical
- Reuse `@Tier(nodeType)` — invalid, `@Tier` has `@Target({})` and can only appear inside `@FaultPolicyDef`, not on NodeSpec classes
**Rationale:** Build-time validation prevents deployment failures. A dedicated `@NodeType` annotation is simple, explicit, and follows the same pattern as JPA's `@Entity` or Jackson's `@JsonTypeName`. Jandex scan is the established pattern for Quarkus extensions in this repo.
**Trade-offs:** Requires adding `@NodeType("data-source")` to every NodeSpec class that should be YAML-addressable. This is a one-time annotation per type — comparable to `@Entity` or `@JsonTypeName`.
**Sources:** `DesiredStateAnnotationsProcessor.java`, `NodeSpec.java`, `Tier.java` (@Target({}) — cannot annotate types)
**Exploration:** quick
**Status:** revised (R1-02 corrected @Tier conflation)

## D6: YAML top-level schema

**Choice:** Three top-level sections for #117: `desiredState` (namespace + name → GoalCompiler qualifier), `variables` (Map layer for resolution chain), `nodes` (map of id → node with `type`, `spec`, `dependsOn`, `humanGating`)
**Alternatives:**
- Nodes as a list with `id` field inside each entry — more verbose, less natural
- Separate `dependencies:` section instead of inline `dependsOn` — possible but less ergonomic
**Rationale:** Map-keyed nodes follow Kubernetes/Helm conventions. Inline `dependsOn` is more natural for operators. `desiredState` metadata mirrors `@DesiredState(namespace, name)`.
**Trade-offs:** Map keys must be unique (enforced by YAML spec). `dependsOn` references are strings that need build-time validation against declared node IDs.
**Extensibility:** The schema is designed to accommodate future sections without breaking changes: `faultPolicies:` (D8 deferral), `rules:` and `invariants:` (Drools epic), `when:`/`forEach:` directives on nodes (conditionals epic), lifecycle `hooks:` on nodes (Ansible-concern epic), and `modules:` (composition epic). These are additive — new top-level keys or new node-level keys, not changes to existing structure.
**Sources:** Research doc §5.1, §8, §11 (self-management example), `GraphDescriptor.java`
**Exploration:** quick
**Status:** revised (R1-05 added extensibility notes)

## D7: Java as source of truth for schemas

**Choice:** Java NodeSpec records define the schema. YAML/JSON Schema and TypeScript types are generated from Java, not the other way around.
**Alternatives:**
- YAML schema as source of truth, generate Java — lossy, ambiguous type mapping
**Rationale:** NodeSpec implementations are the runtime contract — the runtime requires them regardless of the declaration surface. Generating outward from Java (which already exists and is fully typed) is a mechanical transformation. Generating inward from YAML schema to Java is lossy and ambiguous. This is the same approach used in CaseHub case models.
**Trade-offs:** Domain developers must write Java records for every node type. But they already do this — NodeSpec implementations are required by the runtime.
**Sources:** Case model precedent (user input), `NodeSpec.java`, `DataSourceSpec.java`
**Exploration:** quick
**Status:** revised (R1-07 strengthened architectural rationale)

## D8: Fault policies deferred

**Choice:** YAML fault policy declaration deferred to a follow-up issue under #116
**Alternatives:**
- Include fault policies with `@ReviewAction` aliasing — adds a second registry, new annotation, more scope
**Rationale:** Fault policy tiers reference Java review methods (ReviewSpecFactory). YAML can't reference methods without an aliasing mechanism (`action: ai-review` → ReviewSpecFactory class). The research doc §11 sketches this aliasing model — the follow-up has a clear path, but it's separate scope.
**Trade-offs:** YAML-declared graphs in #117 cannot express fault escalation. Operators needing fault policies use the annotation path until the follow-up lands.
**Sources:** `FaultPolicyDescriptor.java`, `TierDescriptor.java`, research doc §11 (`action:` aliasing)
**Exploration:** quick
**Status:** captured

## D9: REST endpoint deferred

**Choice:** REST endpoint for runtime YAML submission deferred until the YAML language is proven through build-time usage and testing
**Alternatives:**
- Include REST endpoint in foundation — adds HTTP surface before the language is validated
**Rationale:** Focus on core pipeline (YAML → GraphDescriptor → GoalCompiler) with API-level testing. Once the language shape is stable and the operational model is understood, the REST endpoint is thin wiring on top.
**Trade-offs:** No runtime hot-reload in #117. Operators test via build-time classpath YAML files.
**Scope change:** Issue #117 originally included "REST endpoint for runtime YAML submission (hot-reload use case)" in scope. Deferred during brainstorming to keep the foundation focused on proving the language before adding HTTP surface.
**Sources:** Issue #117 scope discussion
**Exploration:** quick
**Status:** revised (R1-06 noted scope change)

## D10: YAML requires Java NodeSpec classes on classpath

**Choice:** Every `type:` string in a YAML file must resolve to a Java NodeSpec class compiled and present on the classpath. Operators cannot define new node types purely in YAML.
**Alternatives:**
- Allow YAML-only node types with `Map<String, Object>` spec (no Java class) — useful for lightweight/untyped nodes but loses type safety and Jackson deserialization
- Generic NodeSpec that accepts arbitrary properties — provisioner handles untyped specs dynamically, but breaks the typed NodeSpec contract that provisioners depend on
- Runtime class generation from YAML schema definitions — complex, fragile, and the provisioner still needs to know what fields to expect
**Rationale:** NodeSpec implementations are the runtime contract between the declaration surface and the provisioner. The provisioner casts the spec to a typed record (`DataSourceSpec`) to read its fields. Without a Java class, the provisioner gets an untyped map — which pushes type handling complexity into every provisioner implementation. Java-as-source-of-truth (D7) already requires these classes to exist. This constraint is the same one Terraform has — a provider (provisioner) defines its resource schema, and the declaration references it.
**Trade-offs:** An operator who needs a new node type must write a Java record, compile it, and add it to the classpath. This is a platform developer task, not an operator task. The operator's scope is composing existing types into graphs — defining new types is extending the platform.
**Sources:** `NodeSpec.java`, `DataSourceSpec.java`, `NodeProvisioner.java` (casts spec to typed record)
**Exploration:** quick (surfaced by decision review R1-11 — implicit consequence of D5+D7 made explicit)
**Status:** captured
