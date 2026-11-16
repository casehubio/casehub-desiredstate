---
layout: post
title: "Write YAML, Not Java"
date: 2026-10-25
entry_type: note
subtype: diary
projects: [casehubio/casehub-desiredstate]
tags: [desiredstate, yaml, plugin-architecture, primitives, spi, parity]
series: issue-173-yaml-plugin-architecture
---

# Write YAML, Not Java

Back in September I wrote about the [plugin that writes itself](2026-09-09-mdp01-the-plugin-that-writes-itself.md) — the original spike for YAML-defined resource types. The foundation was solid: a step pipeline, interpolation, build-time validation. But it stopped short of the finish line. The primitive registry was a hardcoded set used only for validation. The step runner was a hand-rolled lambda in tests. And the auto-registration — the part that turns a YAML file into a real provisioner, actual-state adapter, fault policy, and RAS situation detector — didn't exist.

A YAML plugin that validates but can't provision anything is an interesting prototype. A YAML plugin that provisions, monitors, self-heals, and learns without a single line of Java is a product capability.

The gap was three issues wide.

## The Dispatch Chain

The step pipeline's execution model was already clean — `StructuralStepEvaluator` from the platform handles all control flow (if/else, parallel, try/catch, barrier), delegating only leaf steps to a `StepRunner`. What we lacked was the production `StepRunner` — the thing that maps a step name like `rest-call` to actual code that makes an HTTP request.

The answer was already in the platform. `CatalogStepRunner` resolves step names from a `PluginRegistry`, calls `Action.execute()`, returns a `Result`. The platform's `@Plugin` annotation processor generates compiled manifests at build time. At runtime, `AptPluginSource` scans those manifests from the classpath and registers real implementations — `rest-call`, `assert`, `process` — before application code runs.

The desiredstate module needed to do two things: register its own domain-specific primitives, and ensure every known primitive resolves regardless of which runtime environment is active.

![Step primitive dispatch chain — from YAML plugin through Walker, evaluator, and runner to the registry's three registration sources](images/primitive-dispatch-chain.svg)

## The Parity Principle

This is where a design decision shaped everything that followed. The YAML plugin system runs in both Java and TypeScript environments. A plugin written for a Java deployment must also load — and at minimum trace its execution — in a TypeScript environment. The reverse is true for TS-native primitives.

The `DesiredStatePrimitiveRegistrar` implements this through a two-pass registration. First, real implementations:

- `compare-state` — evaluates conditions against actual state to determine PRESENT, ABSENT, DRIFTED, or UNKNOWN
- `json-extract` — pulls fields from JSON responses using dot-path notation
- `graphql-call` — HTTP POST with query/variables construction
- `approval-gate` — blocks execution, returns pending status for the approval lifecycle

Then, gap-fill. For every primitive in `BUILT_IN_PRIMITIVES` that isn't already registered (because the platform provided it or the registrar just did), a `WriterAction` is created — it prints the step name and parameters to a `Writer` and returns success. In production, that writer goes to a logger. In tests, a `StringWriter` captures the output for assertions.

The gap-fill runs after `AptPluginSource` populates the registry with platform primitives. `CompositePluginRegistry` uses `putIfAbsent` semantics — first registration wins. So the ordering is: platform real → domain real → writer fallbacks for whatever is left. Every primitive always resolves. No "unknown step" errors from environment mismatch.

## What a Plugin Looks Like

A complete YAML plugin for a Redis cache resource. This is the entire file — no Java classes, no CDI beans, no build extensions:

```yaml
type: redis-cache
version: 1
resync-interval: 30s

spec:
  fields:
    host:
      type: string
      required: true
    port:
      type: integer
      default: 6379
    db-index:
      type: integer
      default: 0

actual-state:
  - rest-call:
      url: "http://${spec.host}:${spec.port}/info"
      method: GET
      result: health
  - compare-state:
      present-when: "${result.health.status} == 200"
      absent-when: "${result.health.status} == 404"
      drifted-when: "${result.health.status} != 200"

provisioner:
  provision:
    - rest-call:
        url: "http://${spec.host}:${spec.port}/config"
        method: POST
        body: '{"db": ${spec.db-index}}'
        result: setup
    - assert:
        condition: "${result.setup.status} == 200"

  deprovision:
    - rest-call:
        url: "http://${spec.host}:${spec.port}/flush"
        method: POST
        body: '{"db": ${spec.db-index}}'

fault-policies:
  - tiers:
      - threshold: 3
        action: retry
      - threshold: 5
        action: review

ras:
  situations:
    - name: repeated-provision-failure
      events: [NODE_FAULTED]
      correlation-window: 5m
      chain-mode:
        streak: 3
      trigger: create-case
```

Drop that file at `META-INF/desiredstate/plugins/redis-cache.yaml`. On startup, `PluginDiscovery.discoverAll()` produces six SPI beans from it — and the reconciliation loop treats it identically to a hand-coded Java plugin.

## Auto-Registration

The discovery pipeline is framework-neutral. `PluginDiscovery.discoverAll()` takes runtime dependencies — `StepRunner`, `StructuralStepEvaluator`, `CredentialResolver` — and returns a `List<BeanRegistration>`. Each registration is a named bean with its type and instance. The CDI and Spring layers iterate and register them into their respective containers.

![Auto-registration flow — one YAML file produces six SPI beans via PluginDiscovery](images/plugin-auto-registration.svg)

One `YamlPluginProvisioner` handles all YAML-defined types. It holds a `Map<NodeType, PluginDescriptor>` and dispatches by type — the same routing pattern `DefaultNodeProvisionerRouter` uses for Java provisioners. Same for `YamlPluginActualStateAdapter`. The reconciliation loop doesn't know or care whether a provisioner was written in Java or declared in YAML.

That last sentence is the point. The runtime is the same runtime. The loop is the same loop. The fault policies, CBR learning surface, and RAS situation detection all work identically. The only difference is where the provisioning logic lives — in a compiled class or in a YAML step pipeline.

## What This Opens Up

The operator audience changes. Until now, adding a new resource type to the desired-state system meant a Java engineer writing at minimum four SPI implementations, a build extension, a deployment module, and tests. Now it means writing one YAML file and knowing the HTTP API of the thing you're managing.

The three critical-path issues — primitive registry, step implementations, auto-registration wiring — are the infrastructure layer. The primitives that an operator actually invokes (`rest-call`, `json-extract`, `graphql-call`) come from the platform or from the registrar. The compound primitive expander from the September spike still works — a `k8s-api-call` compound wrapping `rest-call` with auth headers inlines at build time. What's new is that the expanded pipeline actually executes.

Next: #177 (spec field runtime validation) and #178 (suspend/resume lifecycle for YAML plugins) fill in production-grade guardrails. #179 puts a real operator-facing example in front of it all — a complete plugin with tests, fault injection, and infrastructure stubs. The architecture is wired. The next step is showing someone who manages Redis or Kubernetes how to use it.
