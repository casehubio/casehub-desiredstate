# Plugin Testing Framework — Design Spec

**Issue:** #135  
**Status:** Design  
**Scale/Complexity:** M / Med  
**Companion to:** #87 D13 (deferred testing model — YAML plugin architecture spec)  
**D13 coverage:** v1 addresses requirements 1-3 (schema validation, step pipeline testing, interpolation verification). Requirement 4 (fault policy behavior testing) is partially addressed via fault injection; full FaultPolicyEngine integration is v2.

## Problem Statement

YAML plugin authors have no dedicated test harness. The existing `casehub-desiredstate-testing` module provides runtime-level mocks (MockNodeProvisioner, MockActualStateAdapter) that test the reconciliation loop, not individual plugins. `PluginIntegrationTest` in `plugin/runtime/` demonstrates ad-hoc end-to-end testing of a plugin, but requires writing Java and manually assembling a `YamlPluginProvisioner` with custom `StepRunner` and `ConditionEvaluator`.

For YAML plugin authors — the "no Java required" audience — there is no declarative way to verify that their plugin provisions correctly, reads actual state accurately, handles failures gracefully, or validates spec schemas.

## Scope

### v1 (this issue)

- YAML test format: declarative `*.test.yaml` files discovered at `META-INF/desiredstate/tests/`
- Single `PluginTestExtension` managing full test lifecycle
- Two TestInfrastructure implementations: HTTP mock (embedded WireMock), shell-sandbox (temp dir + ProcessBuilder)
- Build-time validation as first-tier test (requires `PluginValidator` extraction)
- Declarative assertion model (provision result, actual-state status, error matching)
- Fault injection via StepRunner wrapping
- Setup/teardown lifecycle hooks (shared stubs + variables)

### v2 (deferred)

- Java fluent API (`PluginTestHarness`) for Java plugin authors
- K3s Testcontainer backend for real K8s API testing
- CLI standalone validator (no Maven required)
- WireMock interaction verification (`verify:` section)
- Fault policy mutation assertions
- Per-test-case setup/teardown
- Step pipeline execution in setup (pre-create dependency resources)

## Module Structure

### New module: `plugin/testing/`

```
plugin/testing/
├── pom.xml                           # casehub-desiredstate-plugin-testing
└── src/main/java/io/casehub/desiredstate/plugin/testing/
    ├── PluginTestExtension.java       # JUnit5 @RegisterExtension — lifecycle orchestrator
    ├── PluginTestCase.java            # Parsed test case record
    ├── PluginTestSuite.java           # Parsed test YAML file (cases + setup + teardown)
    ├── PluginTestYamlParser.java      # Jackson-based YAML → PluginTestSuite
    ├── PluginTestRunner.java          # Executes a single test case against production code
    ├── PluginTestAssertions.java      # Evaluates declarative assertions
    ├── FaultInjectingStepRunner.java  # StepRunner decorator for fault injection
    └── infrastructure/
        ├── TestInfrastructure.java        # SPI: start, configure, bindings, stop
        ├── HttpMockInfrastructure.java    # Embedded WireMock
        ├── ShellSandboxInfrastructure.java # Temp dir + ProcessBuilder
        └── InfrastructureFactory.java     # Resolves infrastructure: declaration → impl
```

### Modified module: `plugin/runtime/`

```
plugin/runtime/src/main/java/io/casehub/desiredstate/plugin/runtime/
└── PluginValidator.java              # Extracted from YamlPluginProcessor
```

### Modified module: `plugin/deployment/`

`YamlPluginProcessor` delegates to `PluginValidator` for all validation logic.

## Dependencies

```
plugin/testing/ depends on:
  ├── plugin/runtime/         (PluginModel, PluginParser, PluginDescriptor, PluginValidator,
  │                            YamlPluginProvisioner, YamlPluginActualStateAdapter)
  ├── desiredstate-api/       (NodeProvisioner, ActualStateAdapter, DesiredNode, etc.)
  ├── runtime-core/           (DefaultDesiredStateGraphFactory)
  ├── platform-yaml-step-runtime/ (StructuralStepEvaluator, StepRunner, ConditionEvaluator)
  ├── platform-api/           (CredentialResolver)
  ├── junit-jupiter-api       (JUnit5 extension model)
  ├── wiremock                (WireMockExtension — embedded, no Docker)
  └── assertj-core            (assertion helpers)
```

No Quarkus dependencies. No Testcontainers dependency in v1 (embedded WireMock is in-process).

## Test YAML Format

### Full example

```yaml
# META-INF/desiredstate/tests/load-balancer.test.yaml
plugin: load-balancer
infrastructure: http-mock

setup:
  stubs:
    - request:
        method: POST
        path: /api/v1/auth/token
      response:
        status: 200
        body:
          token: "test-bearer-token"
  variables:
    spec:
      namespace: test-ns
    auth:
      api:
        endpoint: "${wiremock.url}"
        token: "test-token"

tests:
  - name: provision creates load balancer
    spec:
      name: test-lb
      type: APPLICATION
      targetServices: [web]
    expectations:
      - request:
          method: POST
          path: /api/v1/load-balancers
          body:
            name: test-lb
        response:
          status: 201
          body:
            id: "lb-001"
    action: provision
    assert:
      provision: success

  - name: actual state reads PRESENT for existing resource
    spec:
      name: test-lb
      type: APPLICATION
    expectations:
      - request:
          method: GET
          path: /api/v1/load-balancers/test-lb
        response:
          status: 200
          body:
            name: test-lb
            status: active
    action: actual-state
    assert:
      actual-state: PRESENT

  - name: provision fails with clear error on missing name
    spec:
      type: APPLICATION
    action: validate
    assert:
      error-matches: "name is required"

  - name: deprovision removes load balancer
    spec:
      name: test-lb
    expectations:
      - request:
          method: DELETE
          path: /api/v1/load-balancers/test-lb
        response:
          status: 200
    action: deprovision
    assert:
      deprovision: success

  - name: multi-step provisioning with verification
    spec:
      name: test-lb
      type: APPLICATION
    expectations:
      - scenario: create-and-verify
        request:
          method: POST
          path: /api/v1/load-balancers
          body:
            name: test-lb
        response:
          status: 201
          body:
            id: "lb-001"
        when-state: Started
        set-state: Created
      - scenario: create-and-verify
        request:
          method: GET
          path: /api/v1/load-balancers/lb-001
        response:
          status: 200
          body:
            status: active
        when-state: Created
    action: provision
    assert:
      provision: success

  - name: fault injection after 3 failures
    spec:
      name: test-lb
      type: APPLICATION
    fault-injection:
      action: provision
      fail-count: 3
      error: "Connection refused"
    assert:
      provision: failed
      error-matches: "Connection refused"
```

### Shell-sandbox example

```yaml
# META-INF/desiredstate/tests/config-file.test.yaml
plugin: config-file
infrastructure: shell-sandbox

tests:
  - name: provision creates config file
    spec:
      path: "${sandbox.dir}/app/config.yaml"
      content: |
        key: value
        nested:
          setting: true
    action: provision
    assert:
      provision: success
      file-exists: "${sandbox.dir}/app/config.yaml"

  - name: deprovision removes config file
    spec:
      path: "${sandbox.dir}/app/config.yaml"
    action: deprovision
    assert:
      deprovision: success
      file-absent: "${sandbox.dir}/app/config.yaml"
```

### External stubs example

```yaml
plugin: complex-api
infrastructure: http-mock

tests:
  - name: complex response body
    spec:
      name: test-resource
    stubs-from: stubs/complex-api/   # classpath-relative path under src/test/resources/
    action: provision
    assert:
      provision: success
```

### Format reference

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `plugin` | yes | string | Plugin type name (matches `plugin.type` in plugin YAML) |
| `infrastructure` | yes | string | `http-mock` or `shell-sandbox` |
| `setup.stubs` | no | list | WireMock stubs loaded before first test case |
| `setup.variables` | no | map | Default spec/auth values inherited by all test cases |
| `tests[].name` | yes | string | Test case display name |
| `tests[].spec` | yes | map | NodeSpec field values |
| `tests[].expectations` | no | list | WireMock stubs for this test case (http-mock only) |
| `tests[].expectations[].scenario` | no | string | WireMock Scenario name for multi-step interactions |
| `tests[].expectations[].when-state` | no | string | WireMock Scenario required state (default: `Started`) |
| `tests[].expectations[].set-state` | no | string | WireMock Scenario new state after match |
| `tests[].stubs-from` | no | string | Path to external WireMock stub directory |
| `tests[].action` | yes | string | `provision`, `deprovision`, `actual-state`, `validate`, `reconcile` |
| `tests[].fault-injection.action` | no | string | Which action to inject faults into |
| `tests[].fault-injection.fail-count` | no | int | Number of consecutive failures |
| `tests[].fault-injection.error` | no | string | Error message for injected failures |
| `tests[].assert.provision` | no | string | `success`, `failed` |
| `tests[].assert.deprovision` | no | string | `success`, `failed` |
| `tests[].assert.actual-state` | no | string | `PRESENT`, `ABSENT`, `DRIFTED`, `UNKNOWN`, `SUSPENDED` |
| `tests[].assert.error-matches` | no | string | Regex or substring match on failure message |
| `tests[].assert.file-exists` | no | string | Path must exist (shell-sandbox) |
| `tests[].assert.file-absent` | no | string | Path must not exist (shell-sandbox) |

## Architecture

### PluginTestExtension

The central orchestrator, registered via `@RegisterExtension` in the test class.

```java
public class PluginTestExtension implements BeforeAllCallback, AfterAllCallback {

    private final String pluginType;
    private final String testResourcePath;
    // ... infrastructure, provisioner, adapter

    public static PluginTestExtension forPlugin(String pluginType) { ... }

    public Stream<DynamicTest> discoverTests() { ... }
}
```

**Lifecycle:**

1. `beforeAll()`: Parse plugin YAML from classpath (`META-INF/desiredstate/plugins/<type>.yaml`), run `PluginValidator` (D11), parse all test YAML files for this plugin, determine infrastructure type, start infrastructure
2. `discoverTests()`: Return `Stream<DynamicTest>` — one per test case. Each DynamicTest:
   a. Load setup stubs/variables if first test in suite
   b. Reset WireMock stubs (keeping setup stubs)
   c. Configure test-case-specific expectations
   d. Build `DesiredNode` from spec + variables
   e. Execute action (provision/deprovision/actual-state/validate)
   f. Evaluate assertions
3. `afterAll()`: Stop infrastructure, clean up temp directories

### TestInfrastructure SPI

Internal to `PluginTestExtension` — not exposed to plugin authors.

```java
interface TestInfrastructure {
    void start();
    void configure(List<StubExpectation> expectations);
    void resetBetweenTests();
    Map<String, Object> variableBindings();
    void stop();
}
```

**`HttpMockInfrastructure`**: Wraps embedded `WireMockServer`. `variableBindings()` returns `{"wiremock.url": "http://localhost:<port>"}`. `configure()` translates YAML expectations to WireMock `stubFor()` calls. `resetBetweenTests()` calls `resetAll()` then reloads setup stubs.

**`ShellSandboxInfrastructure`**: Creates a temp directory. `variableBindings()` returns `{"sandbox.dir": "/tmp/plugin-test-<uuid>"}`. `configure()` is a no-op. `resetBetweenTests()` preserves files (does not clean the sandbox directory) — matching WireMock's setup-stub-persistence model. Test cases within a file can build on each other (provision → verify → deprovision). `stop()` deletes the temp directory. Test authors who need a clean sandbox per case should use a unique subdirectory per test name in their spec.

### PluginTestRunner

Executes a single test case using production code:

```java
class PluginTestRunner {

    ProvisionResult runProvision(PluginDescriptor plugin, DesiredNode node,
                                 StructuralStepEvaluator evaluator, StepRunner runner,
                                 CredentialResolver creds, String tenancyId) {
        var provisioner = new YamlPluginProvisioner(
            Map.of(node.type(), plugin), evaluator, runner, creds);
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        return provisioner.provision(node, new ProvisionContext(tenancyId, graph));
    }

    DeprovisionResult runDeprovision(PluginDescriptor plugin, DesiredNode node,
                                      StructuralStepEvaluator evaluator, StepRunner runner,
                                      CredentialResolver creds, String tenancyId) {
        var provisioner = new YamlPluginProvisioner(
            Map.of(node.type(), plugin), evaluator, runner, creds);
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        return provisioner.deprovision(node, new DeprovisionContext(tenancyId, graph));
    }

    NodeStatus runActualState(PluginDescriptor plugin, DesiredNode node,
                               StructuralStepEvaluator evaluator, StepRunner runner,
                               CredentialResolver creds, String tenancyId) {
        var adapter = new YamlPluginActualStateAdapter(
            Map.of(node.type(), plugin), evaluator, runner, creds);
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var actual = adapter.readActual(graph, tenancyId);
        return actual.statusOf(node.id()).orElse(NodeStatus.UNKNOWN);
    }
}
```

This delegates to the real `YamlPluginProvisioner` and `YamlPluginActualStateAdapter` — no test engine abstraction layer. The framework's value is in lifecycle management, YAML parsing, and assertion evaluation, not in duplicating production code.

### FaultInjectingStepRunner

Wraps the real `StepRunner` to inject failures:

```java
class FaultInjectingStepRunner implements StepRunner {

    private final StepRunner delegate;
    private final AtomicInteger failuresRemaining;
    private final String errorMessage;

    @Override
    public Result run(ResolvedStep step, VariableResolver resolver) {
        if (failuresRemaining.getAndDecrement() > 0) {
            throw new RuntimeException(errorMessage);
        }
        return delegate.run(step, resolver);
    }
}
```

Fault injection works by throwing `RuntimeException`, which `YamlPluginProvisioner.provision()` catches and wraps as `ProvisionResult.Failed(e.getMessage())`. This exercises the real error-handling path.

### PluginValidator (extraction from YamlPluginProcessor)

Extracted to `plugin/runtime/` as a framework-neutral class:

```java
public final class PluginValidator {

    public static final Set<String> BUILT_IN_PRIMITIVES = Set.of(
        "rest-call", "assert", "json-extract", "compare-state", "set-var", ...);

    public static final Set<String> SUPPORTED_FIELD_TYPES = Set.of(
        "string", "integer", "boolean", "enum", "list", "map");

    public static void validatePlugin(PluginModel model) { ... }
    public static void validateSpecSchema(PluginSpecSchema schema) { ... }
    public static void validateSteps(List<ResolvedStep> steps, String context) { ... }
    public static void validateInterpolationRefs(PluginModel model) { ... }
    public static void validateActualStateHasCompareState(PluginModel model) { ... }
    public static void validateActualStateNoApprovalGate(PluginModel model) { ... }
    public static String suggestSimilar(String input, Set<String> candidates) { ... }
    static int levenshtein(String a, String b) { ... }
}
```

`YamlPluginProcessor` in `plugin/deployment/` delegates to `PluginValidator` for all validation, retaining only Quarkus-specific concerns (BuildItem registration, Jandex scan, type conflict detection across Jandex index).

### Node Construction

The test framework builds a `DesiredNode` from the test YAML spec fields as follows:

```java
NodeType type = NodeType.of(pluginDescriptor.type());
Map<String, Object> specFields = mergeVariables(
    infrastructure.variableBindings(),  // infra: ${wiremock.url}, ${sandbox.dir}
    suite.setup().variables(),           // file-level defaults
    testCase.spec()                      // per-case values
);
// Pre-process: resolve infrastructure variable references in string values
specFields = resolveInfraBindings(specFields, infrastructure.variableBindings());
NodeSpec nodeSpec = new YamlNodeSpec(type, HumanGating.NONE, specFields);
NodeId nodeId = NodeId.of("test-" + testCase.name().replaceAll("\\s+", "-"));
DesiredNode node = new DesiredNode(nodeId, nodeSpec, HumanGating.NONE);
```

Key points:
- `YamlNodeSpec` is required (not plain `NodeSpec`) — `YamlPluginProvisioner.extractSpecFields()` pattern-matches on `YamlNodeSpec`. A plain `NodeSpec` silently returns `Map.of()`, breaking all `${spec.*}` interpolation.
- `NodeId` is derived from the test case name (sanitised) for readable error messages.
- `HumanGating` defaults to `NONE`. Human-gating and PendingApproval testing is deferred to v2 (Java fluent API).
- `TargetStatus` defaults to `ACTIVE`. Suspend/resume testing is deferred to v2.
- **Infrastructure variable pre-processing:** String values in the merged spec map containing `${wiremock.url}` or `${sandbox.dir}` are resolved before constructing the `YamlNodeSpec`. This avoids relying on recursive `VariableResolver` resolution (which is not supported). Pre-processing only resolves infrastructure-provided bindings; `${spec.*}` and `${auth.*}` references within plugin step definitions are resolved at step execution time by the `VariableResolver` as normal.

### Step Evaluator Bootstrapping

The test framework constructs the step evaluation chain matching production:

```java
// ConditionEvaluator — same expression delegate as production
// In production, this comes from the platform expression module (MVEL or similar).
// The test framework uses the same evaluator to avoid behavioral divergence.
ConditionEvaluator condEval = new ConditionEvaluator(
    PluginTestExtension::evaluateExpression);

StructuralStepEvaluator evaluator = new StructuralStepEvaluator(condEval);
```

The `evaluateExpression` delegate must support the expression syntax used in plugin YAML `when:` conditions and `compare-state` predicates (e.g., `"${spec.count} > 0"`, `"${result.response.status} == 200"`). The default implementation uses basic comparison operators (`==`, `!=`, `<`, `>`, `in`). Plugins requiring MVEL or SpEL expressions must configure the evaluator explicitly via `PluginTestExtension.withConditionEvaluator()`.

### Test CredentialResolver

The test framework provides a `CredentialResolver` that bridges from `setup.variables.auth` to the production `resolve(credentialRef)` API:

```java
class TestCredentialResolver implements CredentialResolver {
    private final Map<String, Map<String, String>> authBindings;

    // Built from: plugin descriptor's authCredentialRefs (stanza → credentialRef)
    // + test YAML setup.variables.auth (stanza → key/value pairs)
    // Maps: credentialRef → test values

    @Override
    public Map<String, String> resolve(String credentialRef) {
        return authBindings.getOrDefault(credentialRef, Map.of());
    }
}
```

Example: If the plugin declares `auth: { api: { credentialRef: "my-api-creds" } }` and the test YAML has `setup.variables.auth.api: { endpoint: "http://localhost:8080", token: "test" }`, then `TestCredentialResolver` maps `"my-api-creds"` → `{ "endpoint": "http://localhost:8080", "token": "test" }`. The stanza name (`api`) is the join key between the plugin descriptor and the test YAML.

### Variable Resolution Flow

The test framework constructs variable bindings from three sources, merged in precedence order (later wins):

1. **Infrastructure bindings** — `${wiremock.url}`, `${sandbox.dir}` (from `TestInfrastructure.variableBindings()`)
2. **Setup variables** — `setup.variables` from test YAML (file-level defaults)
3. **Test case spec** — `tests[].spec` values (per-case overrides)

These are assembled into a `VariableResolver` with appropriate scopes (`spec`, `auth`) and passed to the real `YamlPluginProvisioner` / `YamlPluginActualStateAdapter`. The `${auth.*}` variables are resolved via a test `CredentialResolver` that maps auth stanza names to the variable-declared values.

### WireMock Stub Translation

The inline YAML expectations map to WireMock API calls:

```yaml
expectations:
  - request:
      method: POST
      path: /api/v1/resources
      body:
        name: test
    response:
      status: 201
      body:
        id: "r-001"
```

Translates to:

```java
wireMock.stubFor(post(urlPathEqualTo("/api/v1/resources"))
    .withRequestBody(matchingJsonPath("$.name", equalTo("test")))
    .willReturn(aResponse()
        .withStatus(201)
        .withJsonBody(Map.of("id", "r-001"))));
```

Multi-step scenarios add `.inScenario(name).whenScenarioStateIs(state).willSetStateTo(newState)`.

## Test Actions

| Action | What it exercises | Assertions available |
|--------|-------------------|---------------------|
| `provision` | `YamlPluginProvisioner.provision()` | `provision: success/failed`, `error-matches` |
| `deprovision` | `YamlPluginProvisioner.deprovision()` | `deprovision: success/failed`, `error-matches` |
| `actual-state` | `YamlPluginActualStateAdapter.readActual()` | `actual-state: PRESENT/ABSENT/DRIFTED/UNKNOWN/SUSPENDED` |
| `validate` | `PluginValidator.validatePlugin()` + spec schema | `error-matches` (validation error message) |
| `reconcile` | Convenience smoke test: runs actual-state check then provision if ABSENT/DRIFTED. Does NOT use the production `TransitionPlanner`/`TransitionExecutor` — this is a simplified sequential flow for verifying the provision+actual-state combination. For full reconciliation testing, use separate `actual-state` and `provision` test cases. | `actual-state` + `provision` |

## Error Reporting

Test failures produce structured messages:

```
Plugin test failed: load-balancer / provision creates load balancer

  Action:   provision
  Expected: provision = success
  Actual:   provision = failed (Connection refused)

  Plugin:  META-INF/desiredstate/plugins/load-balancer.yaml
  Test:    META-INF/desiredstate/tests/load-balancer.test.yaml (line 12)
  Spec:    {name: test-lb, type: APPLICATION, targetServices: [web]}
```

Validation failures include the plugin author-friendly messages from `PluginValidator`, including typo suggestions:

```
Plugin validation failed: load-balancer

  Error: Unknown primitive 'rest-cll' in provision step 1.
         Did you mean: rest-call?

  Plugin: META-INF/desiredstate/plugins/load-balancer.yaml
```

## Consumer Guide Integration

The test YAML format and `PluginTestExtension` usage are documented in `docs/guides/consumer-guide.md` under a new "Testing Plugins" section. This includes:

- Minimal Java test class (4 lines)
- Test YAML format reference
- Infrastructure type selection guide
- Setup/teardown patterns
- Fault injection examples
- Common assertion patterns

## Out of Scope

| Item | Reason | Future |
|------|--------|--------|
| Java fluent API (PluginTestHarness) | Genuine API design task, Java authors have existing test paths | v2 |
| K3s Testcontainer backend | Minority use case, K8s API testing via WireMock covers most cases | v2 |
| CLI standalone validator | IDE test runners serve the inner dev loop for most operators | v2 |
| WireMock `verify:` assertions | Scenarios provide implicit verification; explicit verification adds complexity | v2 |
| Fault policy mutation assertions | Requires FaultPolicyEngine integration | v2 |
| Per-test-case setup/teardown | File-level setup with per-case expectations is sufficient for v1 | v2 |
| Step pipeline execution in setup | Ordering complexity with infrastructure startup | v2 |
| PendingApproval / HumanGating testing | Requires HumanGating, PlanApproval fields in test YAML; untestable without Java API | v2 |
| Spring Boot test support | Framework-neutral engine; Spring test integration deferred | v2 |

## References

- `plugin/runtime/src/test/java/.../PluginIntegrationTest.java` — existing ad-hoc plugin testing pattern that this framework formalises
- `plugin/runtime/src/test/resources/META-INF/desiredstate/plugins/mock-resource.yaml` — existing test plugin fixture
- `plugin/deployment/src/main/java/.../YamlPluginProcessor.java` — validation methods to extract
- `testing/src/main/java/.../MockNodeProvisioner.java` — runtime-level mocks (different layer)
- #87 D13 — deferred testing companion spec (this issue fulfils it)
- #87 D6 — auth model (CredentialResolver SPI) used by test variable resolution
- WireMock 3.x JUnit5 API — `WireMockExtension`, `WireMockServer`
- JUnit5 `@TestFactory` + `DynamicTest` — test discovery mechanism
