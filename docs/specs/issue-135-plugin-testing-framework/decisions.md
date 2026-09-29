# Decisions — #135 Plugin Testing Framework

## D1: Primary audience — YAML plugins only for v1

**Choice:** YAML plugin authors are the primary and sole v1 audience. Java fluent API (PluginTestHarness) deferred to v2.
**Alternatives:**
- Java plugins only — simpler runtime model but Java authors already have MockNodeProvisioner and can write JUnit tests manually
- Both YAML + Java in v1 — proves surface-agnosticity but adds genuine API design scope; Java authors already have a testing path via PluginIntegrationTest patterns and casehub-desiredstate-testing mocks
- Defer Java API to v2 (chosen) — delivers the highest-value surface first; surface-agnosticity can be validated by an internal integration test without shipping a public Java API
**Rationale:** The "no Java required" promise for YAML plugins demands a declarative testing story. Java plugin authors already have MockNodeProvisioner, MockActualStateAdapter, and the PluginIntegrationTest pattern. The Java fluent API adds convenience, not capability, and is a genuine API design task (~80 lines of boilerplate to abstract). An internal test can verify surface-agnosticity without a public API.
**Trade-offs:** No Java fluent API in v1. Surface-agnosticity unproven to external consumers until v2.
**Sources:** #87 D13 (deferred testing companion spec), existing testing/ module, PluginIntegrationTest (lines 140–195 show the boilerplate a fluent API must abstract)
**Exploration:** quick
**Status:** revised — deferred Java API to v2 per R1-06; "low marginal cost" claim was unsubstantiated

## D2: Test runtime — plain JUnit5, no Quarkus container

**Choice:** Tests run as plain JUnit5 without Quarkus container boot. The test framework instantiates YamlPluginProvisioner and YamlPluginActualStateAdapter directly, like PluginIntegrationTest does today.
**Alternatives:**
- @QuarkusTest — full CDI container catches integration issues but adds ~5s boot per test class
- Both tiers (plain + @QuarkusTest) — more complete but more module complexity
**Rationale:** Fast feedback loop is critical for plugin authors. The "no Java required" audience should not need to understand Quarkus boot. Direct instantiation avoids CDI and keeps the test engine framework-neutral.
**Trade-offs:** Does not exercise CDI wiring or deployment processor validation. Plugin authors must rely on build-time validation for that — but see D11 which integrates build-time validation into the test cycle.
**Sources:** plugin/runtime/src/test/java PluginIntegrationTest (existing plain JUnit pattern)
**Exploration:** quick
**Status:** captured

## D3: Test discovery — single PluginTestExtension with @TestFactory delegation

**Choice:** A single `PluginTestExtension` registered via `@RegisterExtension` manages the complete test lifecycle: YAML scanning, infrastructure startup (WireMock, temp dir), build-time validation, provision/actual-state cycle execution, and assertion evaluation. Plugin author writes one `@RegisterExtension` field and one `@TestFactory` method that delegates to `extension.discoverTests()`. WireMock is managed internally by the extension — the plugin author never registers `WireMockExtension` directly.
**Alternatives:**
- Two extensions (test discovery + WireMock) — requires cross-extension coordination via JUnit5 `ExtensionContext` store. More ceremony, harder for YAML-audience
- Base class with abstract @TestFactory — forces single inheritance, known anti-pattern in test framework design
- Maven plugin code generation — generates Java test classes at generate-test-sources phase. More IDE-friendly but more moving parts
**Rationale:** The plugin author's ceremony is minimal: one extension registration, one `@TestFactory` method. The extension reads test YAML files, determines infrastructure needs from the `infrastructure:` declaration in each file (e.g., `infrastructure: http-mock`), starts the appropriate TestInfrastructure (D4) internally, and returns `Stream<DynamicTest>`. WireMockExtension is used internally by the `HttpMockInfrastructure` implementation — it is not exposed to the test class. This eliminates cross-extension coordination entirely.
**Trade-offs:** The extension is heavier — it owns infrastructure lifecycle, not just test discovery. But for the "no Java required" audience, minimizing test class ceremony is the right trade-off.
**Sources:** JUnit5 DynamicTest API, WireMockExtension (used internally, not exposed)
**Exploration:** quick
**Status:** revised — committed to single-extension model per R1-07 and R2-04; clarified WireMock is internal

## D4: Test architecture — production code path with pluggable test infrastructure

**Choice:** The test framework uses YamlPluginProvisioner and YamlPluginActualStateAdapter directly with the real StepRunner (including real primitives like rest-call). A `TestInfrastructure` strategy interface manages test resource lifecycle (start, configure, provide variable bindings, stop) and is internal to `PluginTestExtension` (D3) — plugin authors never interact with it directly. The extension: (1) parses test YAML, (2) determines infrastructure from the test file's `infrastructure:` declaration, (3) starts the appropriate TestInfrastructure internally, (4) runs build-time validation via PluginValidator (D11), (5) creates real provisioner/adapter with test-configured variable bindings from infrastructure, (6) runs provision→actual-state cycle, (7) evaluates assertions from test YAML.
**Alternatives:**
- PluginTestEngine with TestBackend strategy (original D4) — re-formalises the provision→actual-state→assert cycle that YamlPluginProvisioner/Adapter already implement. The "engine" abstraction adds a layer that duplicates production code
- Test-mode StepRunners (WireMockStepRunner, K3sStepRunner) — intercepts step execution rather than providing mock infrastructure. This defeats integration testing: a WireMockStepRunner mocks the HTTP call itself, so the real RestCallPlugin is never exercised. Integration tests should exercise the real code path with the real StepRunner hitting mock endpoints
**Rationale:** The provision→actual-state→assert cycle is the responsibility of YamlPluginProvisioner and YamlPluginActualStateAdapter — duplicating it in a test engine adds unnecessary abstraction. The test framework's value is: parsing test YAML, managing infrastructure lifecycle, configuring variable bindings to redirect real primitives to test infrastructure (e.g., `${auth.api.endpoint}` → `http://localhost:<wiremock-port>`), and evaluating declarative assertions. The real StepRunner with the real RestCallPlugin making real HTTP calls to WireMock exercises the actual plugin execution path. TestInfrastructure implementations (`HttpMockInfrastructure`, `ShellSandboxInfrastructure`) are internal to the extension — they are SPI extension points for framework developers, not plugin authors.
**Trade-offs:** TestInfrastructure is a thinner abstraction than TestBackend — less encapsulation, more reliance on production code stability. But production code stability IS what we're testing.
**Sources:** YamlPluginProvisioner (uses StepRunner + VariableResolver), RestCallPlugin (url parameter resolved via interpolation — confirms A1), PluginIntegrationTest (existing direct-instantiation pattern)
**Exploration:** quick
**Status:** revised — removed PluginTestEngine per R1-02; TestInfrastructure internal to extension per R2-04; rejected test-mode StepRunners

## D5: HTTP mock lifecycle — embedded WireMock via WireMockExtension

**Choice:** HTTP mock testing uses embedded in-process WireMock via JUnit5 `WireMockExtension` (`@RegisterExtension`). No Docker dependency. Per-test-class server instance, stub reset between test cases via `WireMockExtension`'s built-in `resetOnEachTest`.
**Alternatives:**
- Testcontainers WireMock (original D5) — adds Docker as a hard dependency for ALL plugin testing including the simplest REST API plugin. ~2s container startup vs ~200ms embedded startup. Docker is appropriate for K3s (containerization is inherent) but excessive for an in-process mock HTTP server
- Per-test-case WireMock — fresh server per test. Maximum isolation but unnecessary given stub reset
**Rationale:** WireMock 3.13.0 provides `WireMockExtension` implementing `BeforeAllCallback`, `AfterAllCallback`, `BeforeEachCallback`, `AfterEachCallback` — full JUnit5 lifecycle integration. Embedded WireMock starts in ~200ms with negligible resource overhead. No Docker installation required. This aligns with the "no Java required" audience — operators should not need Docker to run `mvn test` for REST API plugins.
**Trade-offs:** WireMock version is coupled to the test framework dependency (currently 3.13.0 already on classpath). Embedded mode shares JVM — cannot test network-level failures (timeout, connection reset). For network-level testing, the future K3s backend (v2) with Testcontainers is appropriate.
**Sources:** WireMock 3.13.0 `WireMockExtension` (confirmed in project dependencies), WireMock JUnit5 documentation
**Exploration:** quick
**Status:** revised — switched to embedded WireMock per R1-03; Testcontainers reserved for K3s (v2)

## D6: Backend implementations — http-mock + shell-sandbox in v1, K3s deferred

**Choice:** v1 ships two TestInfrastructure implementations: http-mock (embedded WireMock) and shell-sandbox (temp directory + ProcessBuilder). K3s Testcontainer deferred to v2.
**Alternatives:**
- All three in v1 (original D6) — K3s adds ~40% implementation scope for a minority use case. Most K8s-managing plugins talk to the K8s API server, which is a REST API testable with WireMock
- http-mock only — shell-sandbox is trivial (temp dir + ProcessBuilder), near-zero cost to include
**Rationale:** http-mock covers the primary use case — REST API plugins (K8s Deployment, Cloudflare DNS, database provisioners are all REST/HTTP). shell-sandbox covers file-system-based plugins at trivial cost. K3s adds ~30s startup, significant CI resource requirements (Docker-in-Docker or privileged containers), and covers the niche case of plugins needing a real K8s cluster. K8s API mocking via WireMock handles the majority of K8s-managing plugins. K3s is deferred until the test framework is proven with http-mock + shell-sandbox.
**Trade-offs:** Plugins requiring a real K8s cluster for integration testing must wait for v2 or manage their own K3s lifecycle. This affects a minority of plugin authors.
**Sources:** #87 D6 decisions (Kind-on-Podman for K8s, WireMock containers for vendor APIs)
**Exploration:** quick
**Status:** revised — phased K3s to v2 per R1-04

## D7: WireMock expectations — inline YAML with multi-step interaction support

**Choice:** WireMock expectations declared inline in test YAML with support for ordered multi-step API interactions via WireMock Scenarios. External stub files supported via a `stubs-from:` directive for complex scenarios (binary payloads, large response bodies).
**Alternatives:**
- Inline single-stub only (original D7 implication) — covers only single-request provisioning, which is the uncommon case. Most REST API provisioning involves 2-4 ordered API calls (create → verify → configure)
- External files only — WireMock-native JSON stubs in __files/mappings. Maximally flexible but splits test definition across files, losing self-containment
**Rationale:** Inline keeps test cases self-contained. Multi-step interactions are the norm for REST API provisioning. The inline YAML format maps to WireMock Scenarios — each step in a `scenario:` sequence specifies `when-state:` (defaults to "Started") and `set-state:`, mapping directly to WireMock's state machine model. This covers 2-4 step ordered interactions inline without falling back to external files.
**Trade-offs:** Scenario syntax adds complexity to the inline format. Plugin authors must understand the state machine model for multi-step interactions. External file fallback remains available for cases that exceed inline expressiveness.
**Sources:** WireMock Scenario API, WireMock stub mapping model
**Exploration:** quick
**Status:** revised — added multi-step interaction support per R1-10

## D8: Fault injection — step-level failure injection (YAML plugins)

**Choice:** The test framework wraps the StepRunner to throw on provision/deprovision steps for the specified number of times. This exercises the real YamlPluginProvisioner failure path. The test YAML declares fault injection via a `fault-injection:` section specifying which steps fail and how many times.
**Alternatives:**
- Provisioner result mocking — replace provisioner with mock returning Failed(N). Simpler but skips the real provisioner code path
**Rationale:** Step-level injection exercises the full provisioner code path including error handling. Fault policy testing works regardless since ThresholdFaultPolicy only sees ProvisionResult, but the test is more realistic.
**Trade-offs:** More complex StepRunner wrapping. The failure injection must be transparent to the provisioner code.
**Sources:** YamlPluginProvisioner.provision() catch block, ThresholdFaultPolicy API
**Exploration:** quick
**Status:** revised — scoped to YAML-only per D1 revision (R2-03); Java fault injection deferred to v2 alongside Java fluent API

## D9: Module placement — plugin/testing/ child of plugin parent

**Choice:** `plugin/testing/` as a child module of the `plugin/` parent POM. Artifact: `casehub-desiredstate-plugin-testing`. Contains the YAML test framework (test infrastructure, YAML parser, assertion engine).
**Alternatives:**
- Top-level `plugin-testing/` — more visible but unclear relationship to plugin module family
- Extend existing `testing/` module — mixes runtime SPI mocks with plugin-specific test harness
**Rationale:** Clear ownership under plugin/. Inherits plugin parent dependencies. The existing `testing/` module has runtime-level mocks for the reconciliation loop; plugin testing is a different concern at a different layer.
**Trade-offs:** Deeper module nesting. Must coordinate with plugin/ parent POM.
**Sources:** testing/ pom.xml (existing module for runtime mocks), plugin/ pom.xml (existing parent)
**Exploration:** quick
**Status:** captured

## D10: Test file location — META-INF/desiredstate/tests/

**Choice:** Test YAML files live at `src/test/resources/META-INF/desiredstate/tests/`. Convention-based discovery by the @TestFactory scanner.
**Alternatives:**
- Alongside plugin YAML in `plugins/` — *.test.yaml next to *.yaml. Tighter coupling but risk of packaging test files
- `src/test/resources/desiredstate-tests/` — outside META-INF. Shallower path but breaks the META-INF/desiredstate convention that plugin authors already learn for production YAML
**Rationale:** Mirrors the plugin YAML location (META-INF/desiredstate/plugins/). Convention-based — plugin and test YAML are siblings under META-INF/desiredstate/. Being in src/test/resources means they're never packaged into production JARs. Plugin authors who know where their plugin YAML goes (META-INF/desiredstate/plugins/) will find their test YAML in the parallel location (META-INF/desiredstate/tests/) without learning a second convention.
**Trade-offs:** Deep path. Authors must know the convention. But the convention is already established by plugin YAML placement — tests follow the same pattern.
**Sources:** YAML plugin discovery path (META-INF/desiredstate/plugins/), YAML graph discovery path (META-INF/desiredstate/)
**Exploration:** quick
**Status:** captured

## D11: Build-time validation as first-tier test

**Choice:** The @TestFactory runs validation on the plugin's YAML before executing any provision/actual-state cycle. Validation errors produce clear DynamicTest failures with the same error messages (including Levenshtein-based typo suggestions) that the Quarkus build step produces.

**Prerequisite: validation extraction.** The validation methods (`validatePlugin()`, `validateSpecSchema()`, `validateSteps()`, `validateInterpolationRefs()`, `validateActualStateHasCompareState()`, `validateActualStateNoApprovalGate()`, `suggestSimilar()`, `levenshtein()`) and `PluginValidationException` currently reside in `YamlPluginProcessor` in `plugin/deployment/`, which depends on `quarkus-arc-deployment`. The `plugin/testing/` module cannot depend on `plugin/deployment/` without transitively pulling in Quarkus deployment infrastructure, violating D2. These must be extracted to a new `PluginValidator` class in `plugin/runtime/` (where `PluginModel`, `PluginParser`, and `PluginDescriptor` already live). Constants (`BUILT_IN_PRIMITIVES`, `SUPPORTED_FIELD_TYPES`, `INTERPOLATION_REF`, `KNOWN_PREFIXES`) move to `PluginValidator`. `YamlPluginProcessor` in `plugin/deployment/` delegates to `PluginValidator` for all validation. `plugin/testing/` depends on `plugin/runtime/` which it already must for `PluginModel` and `PluginParser`.

**Alternatives:**
- Omit build-time validation from test framework — plugin authors only get validation feedback when running the full Quarkus build. This relegates the most common error category (spec reference typos, unknown primitives, missing compare-state) to a slow feedback path
- Separate validation-only test mode — a dedicated "validate only" test YAML mode that skips provision/actual-state. Unnecessary overhead; validation should always run as a precondition
- Leave validation in plugin/deployment/ and add a test-scope dependency — violates D2 by pulling Quarkus deployment infrastructure into the test framework
**Rationale:** The validation methods perform extensive static validation: spec schema checks, primitive reference validation (with typo suggestions via Levenshtein), interpolation reference resolution, compare-state presence in actual-state steps, approval-gate exclusion from actual-state, and duplicate type detection. These are the most common plugin authoring errors. The static methods use only `PluginModel` types — no Quarkus APIs. `YamlPluginProcessorTest` already calls them without Quarkus and duplicates `BUILT_IN_PRIMITIVES` as a local constant (`YamlPluginProcessorTest.java:24`), confirming the constants belong in a shared location. Running validation as the first test step gives plugin authors fast feedback without a full build.
**Trade-offs:** Validation requires access to the type registry (Map<String, String> of existing @NodeTypeId declarations) for type conflict detection. In the test context without Quarkus, the registry is empty — type conflict detection is a build-time concern that requires the full Jandex index. All other validation checks work without the registry.
**Sources:** YamlPluginProcessor.validatePlugin() (lines 86-105), PluginValidationException (plugin/deployment/ — to be moved), YamlPluginProcessorTest (line 24 — duplicated PRIMITIVES constant)
**Exploration:** quick — surfaced by reviewer R1-05; extraction prerequisite surfaced by R2-02
**Status:** revised — added validation extraction prerequisite per R2-02; implementation path now viable under D2 constraints

## D12: Declarative assertion model

**Choice:** YAML test cases declare assertions against five result dimensions: provision result type, deprovision result type, actual-state status, error message patterns, and step result values.
**Alternatives:**
- Unstructured assertions — free-form Java assertions only, losing the declarative YAML model
- Result-type-only assertions — simplest model but insufficient for failure testing and step output verification
**Rationale:** Plugin tests need to verify both happy-path and failure behaviour. The assertion model covers:
1. **Provision result type** — `success`, `failed`, `pending-approval`
2. **Deprovision result type** — `success`, `failed`, `pending-approval`
3. **Actual-state status** — `PRESENT`, `ABSENT`, `DRIFTED`, `UNKNOWN`
4. **Error message matching** — regex or substring match on failure messages (for testing that the right error is produced)
5. **Step result values** — assertions on intermediate step output values (e.g., `rest-call` response body fields)

The assertion syntax in test YAML:
```yaml
assert:
  provision: success
  actual-state: PRESENT
  # or for failure testing:
  provision: failed
  error-matches: "No plugin registered for type.*"
```
**Trade-offs:** Step result assertions require the test framework to capture intermediate step outputs, adding complexity to the StepRunner wrapping. Fault policy assertions (which mutations are produced) are excluded from v1 — they require deeper integration with FaultPolicyEngine. WireMock interaction verification (asserting that specific HTTP requests were made with specific bodies/headers) is not included in v1 — WireMock Scenarios (D7) provide implicit verification via state machine advancement, and over-broad stubs are a test authoring error. A `verify:` section mapping to WireMock's `verify(requestedFor(...))` API could be added in v2.
**Sources:** ProvisionResult sealed variants (Success, Failed, PendingApproval), DeprovisionResult sealed variants, NodeStatus enum, StepResult output map
**Exploration:** quick — surfaced by reviewer R1-08; WireMock verification gap noted per R2-06
**Status:** revised — acknowledged WireMock interaction verification gap per R2-06; deferred to v2

## D13: CLI validator — deferred to v2

**Choice:** v1 provides a JUnit5-based test framework only. A standalone CLI validator (runnable without Maven) is deferred to v2.
**Alternatives:**
- CLI + JUnit5 in v1 — serves the inner development loop ("I changed my YAML, does it still work?") without a Maven build. But adds a second execution model (CLI vs JUnit5) that must produce identical results
- CLI only — loses JUnit5 integration (IDE test runners, Maven Surefire reporting, CI integration)
**Rationale:** `mvn test` is a reasonable CI invocation. The inner development loop is served by IDE test runners (IntelliJ, VS Code) that execute JUnit5 @TestFactory tests with fast feedback. A CLI validator adds value for operators who don't use an IDE, but this is a minority workflow. The D11 build-time validation integration already provides fast validation feedback within the JUnit5 framework. A standalone validator in v2 can extract the validation and assertion logic into a CLI wrapper.
**Trade-offs:** Operators without IDE access must run `mvn test` for the full cycle or manually invoke `YamlPluginProcessor.validatePlugin()` for validation-only feedback. The v2 CLI will resolve this gap.
**Sources:** #87 D13 (deferred testing companion spec mentions "CLI tool or standalone validator")
**Exploration:** quick — surfaced by reviewer R1-11
**Status:** captured

## D14: Test YAML lifecycle hooks — setup and teardown sections

**Choice:** Test YAML files support `setup:` and `teardown:` sections at the file level, executed before the first test case and after the last test case respectively. Individual test cases inherit the file-level setup. No per-test-case setup/teardown.

The `setup:` section supports two forms in v1:
1. **`stubs:`** — WireMock stub declarations shared across all test cases. Common pattern: an auth token endpoint that every test case needs. These stubs are loaded into WireMock after infrastructure startup and before the first test case.
2. **`variables:`** — Spec or auth value overrides applied as defaults across all test cases. Individual test cases can override these per-case values. Common pattern: shared base URL, API version, or credential bindings.

Step pipeline execution in setup (running real provisioner steps to pre-create dependency resources) is deferred to v2.

**Alternatives:**
- No lifecycle hooks — plugin authors must duplicate setup logic across every test case. Works for simple plugins but scales poorly
- Per-test-case setup/teardown — maximum flexibility but adds YAML complexity and may conflict with the per-test-class infrastructure lifecycle (D5)
- File-level + per-case — both levels available. More powerful but more YAML format complexity for v1
- All three forms (stubs + variables + step execution) — step execution in setup reuses StepRunner but introduces ordering complexity between setup steps and infrastructure startup
**Rationale:** Common setup patterns include: pre-seeding WireMock stubs shared across all test cases (e.g., auth token endpoint) and configuring shared spec/auth values. File-level setup/teardown aligns with the per-test-class infrastructure lifecycle from D5 — the WireMock server starts once per file, setup stubs are loaded once, all test cases share the configured state, teardown runs after all cases. Stubs + variables cover the most common setup needs without introducing step execution complexity.
**Trade-offs:** No per-test-case setup. No step pipeline execution in setup. Test cases that need unique setup must include it in their individual `expectations:` section. This is a conscious simplicity trade-off for v1.
**Sources:** JUnit5 @BeforeAll/@AfterAll (file-level), @BeforeEach/@AfterEach (per-case — deferred)
**Exploration:** quick — surfaced by reviewer R1-12; semantics clarified per R2-05
**Status:** revised — defined setup semantics (stubs + variables) per R2-05; step execution deferred to v2
