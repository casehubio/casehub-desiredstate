# Operator-First Declaration Language — Research

**Date:** 2026-08-27
**Issue:** casehubio/casehub-desiredstate#116
**Status:** Active research

## 1. Problem Statement

Infrastructure and application lifecycle management currently requires combining
multiple tools — typically Terraform (or Pulumi) for provisioning with Ansible (or
Chef/Salt) for configuration management. These tools were designed independently,
operate on separate state models, and require manual integration at the boundary.

CaseHub desired-state has a graph-aware runtime with structural pattern matching,
continuous reconciliation, fault-driven adaptation, and lifecycle phases. The question
is whether these capabilities can be surfaced through a declaration language that
provides a unified model — covering the use cases currently addressed by Terraform,
Helm, Ansible, and Pulumi without becoming bloated for any individual use case.

The ambition is not to replace these tools feature-for-feature, but to provide a
coherent alternative that resolves the structural tensions between them.

---

## 2. Landscape Analysis

### 2.1 Terraform / OpenTofu

**Model:** Declarative. HCL (HashiCorp Configuration Language). Plan/apply cycle.

**Strengths:**
- Mature expression language: variables, functions, `count`, `for_each`, conditionals,
  `dynamic` blocks, `for` expressions
- Module system for composition and reuse
- Provider ecosystem: 3000+ providers covering cloud services, SaaS, and infrastructure
- Remote state backends with locking
- `terraform plan` provides a preview of changes before execution
- Drift detection via `terraform refresh` (on-demand, not continuous)

**Limitations in context:**
- Plan/apply is on-demand, not continuous — drift between runs is invisible
- No fault-driven structural adaptation — provider-level retries only
- No lifecycle phases — separate configs needed for provisioning vs steady-state
- Does not cover configuration management — Ansible or similar is needed for
  post-provisioning setup, leading to split state and manual handoffs
- State is flat (resource list) — no structural awareness of topology

### 2.2 Helm

**Model:** Templated YAML for Kubernetes. Package manager semantics (charts, releases).

**Strengths:**
- Packaging model (charts) enables distribution and versioning of deployment units
- Values system for environment-specific configuration
- Dependency management between charts
- Lifecycle hooks (pre-install, post-upgrade, etc.)
- Established ecosystem with Artifact Hub

**Limitations in context:**
- Go templates in YAML are widely regarded as fragile and hard to read
- Kubernetes-only — not a general infrastructure tool
- No drift detection or reconciliation
- No structural awareness — templates produce flat manifests
- No fault handling beyond Kubernetes' own restart policies

### 2.3 Ansible

**Model:** Imperative playbooks in YAML with Jinja2 templating. Task-based execution.

**Strengths:**
- YAML as the primary surface — operators never feel like they're programming
- Jinja2 provides variable substitution, loops, and conditionals within YAML strings
- Inventory system for targeting hosts and groups
- Roles and includes for composition
- Agent-free (SSH-based execution)
- `when:` conditionals on individual tasks
- Facts system for discovering host state

**Limitations in context:**
- Fire-and-forget — no continuous reconciliation or drift detection
- Imperative ordering is the operator's responsibility
- Individual modules aim for idempotency but the overall playbook is not guaranteed
  idempotent
- Limited structural awareness — tasks are a flat list, not a dependency graph
- Fault handling is `rescue`/`always` blocks — no structural escalation

**Key design observation:** Ansible's YAML approach is significant because operators
accept it as "configuration, not programming" — even when they're writing complex
conditionals and loops via Jinja2. The psychological contract matters: the moment you
hand someone a `.ts` file with `import` statements and function calls, that contract
breaks, even if the TypeScript is more constrained than what they were writing in Jinja2.

### 2.4 Pulumi

**Model:** Infrastructure as code in real programming languages (TypeScript, Python,
Go, Java, C#). Plan/apply cycle.

**Strengths:**
- Full programming language with IDE support, type checking, and testing frameworks
- Multi-language SDK — operators use what they already know
- Provider ecosystem via Terraform Bridge (inherits Terraform's provider coverage)
- Automation API for embedding in larger systems
- Policy as Code (CrossGuard) for organisational guardrails
- State management service (Pulumi Cloud) with secrets encryption

**Limitations in context:**
- Same plan/apply model as Terraform — on-demand, not continuous
- Does not cover configuration management — the Terraform+Ansible gap persists
- No structural graph awareness — resources are produced imperatively, relationships
  are explicit dependencies only
- No fault-driven adaptation
- No lifecycle phases

**Positioning note:** Pulumi innovated on the declaration surface (real languages
instead of HCL). CaseHub innovates on the runtime model (continuous reconciliation,
structural graph rewriting, fault-driven adaptation). These are complementary axes.
Pulumi could serve as a `NodeProvisioner` backend — the graph declares topology,
Pulumi handles cloud API calls.

---

## 3. The Terraform + Ansible Unification Problem

The most common production deployment pattern is Terraform + Ansible (or Pulumi +
Ansible). Terraform provisions infrastructure; Ansible configures it. The split
creates several structural problems:

**Split state.** Terraform manages its state (tfstate). Ansible has no persistent state
(or facts cache). Neither system sees the other's state. When an Ansible configuration
step fails, Terraform's state says the resource is provisioned (it is, from Terraform's
perspective). The operator must manually reconcile.

**Manual handoff.** A CI pipeline or wrapper script coordinates the two tools.
Terraform outputs feed into Ansible variables. The integration is bespoke per project.

**Split fault handling.** Terraform retries at the provider level. Ansible has
`rescue`/`always` blocks. Neither system can structurally adapt to the other's failures.
A failed database migration (Ansible) cannot trigger a Terraform state change.

**No unified drift detection.** Terraform detects infrastructure drift (on demand).
Ansible has no drift detection — it re-applies tasks and hopes for idempotency.
Configuration drift between Ansible runs is invisible.

### 3.1 How CaseHub Resolves This

The desired-state graph declares *what should exist* (the Terraform concern). The
transition executor handles *what to do when changing state* (the Ansible concern).
Both live in the same runtime, share the same state model, and participate in the same
fault handling chain.

```
DesiredStateGraph                    → "what should exist" (Terraform)
  └── TransitionExecutor             → "how to get there" (Ansible)
       └── CaseTransitionExecutor    → Worker(Workflow) phases per node
            └── Lifecycle hooks      → imperative steps within a transition
```

A node's provisioning can include multi-step workflows — drain connections, run
migrations, restart services, verify health — orchestrated by casehub-engine-flow.
These are achievement goals scoped to a node's lifecycle transition, not a separate
tool's responsibility.

When a lifecycle step fails, it's a fault on the node — the same fault handling that
works for any other failure. The fault policy can retry, escalate to an AI agent, or
create a human work item. One state model, one fault model, one reconciliation loop.

---

## 4. Four Differentiators

### 4.1 Structural Graph Rewriting

Graph rules (`@GraphRule`) provide pattern-based rewriting that fires during compilation
and — for standalone rules — whenever the graph changes. "Every database gets a backup
agent" is a structural rule, not an imperative loop.

Rules compose: multiple rules can independently add monitoring, security, and compliance
nodes to the same graph without coordination. Template loops in Terraform/Helm/Ansible
require explicit authoring for each structural pattern.

The pattern vocabulary (`@Match`, `@DirectDep`, `@Reaches`, `@NotExists`) enables
topological matching that flat resource lists cannot express: "every node reachable
from a data source through more than two hops needs a cache," "no sink should exist
without an upstream transformer."

### 4.2 Fault-Driven Adaptation

No mainstream infrastructure tool provides structural fault response.
`ThresholdFaultPolicy` enables multi-tier escalation:

1. **Automatic retry** — standard provider retry
2. **AI agent review** — `addReviewNode` creates a review node with dependency on the
   faulted node, delegating diagnosis to an AI agent
3. **Human work item** — `WorkItemPendingApprovalHandler` creates a human-reviewable
   work item via casehub-work

`SituationRecompiler` goes further — detecting aggregate fault patterns via RAS Ganglia
and recompiling the graph in response. A zone-wide failure doesn't just retry individual
nodes; it triggers a structural replanning.

### 4.3 Lifecycle Phases

`CompilationResult.Lifecycle` allows a single declaration to describe multi-phase
deployment: build infrastructure, then configure services, then enable traffic. Each
phase has its own graph, its own completion condition, and its own invariants.

Terraform requires separate configurations (or workspaces) for each phase. Ansible
requires separate playbooks. Helm has lifecycle hooks but they're limited to
pre/post-install/upgrade. CaseHub phases are first-class: each is a full graph with
dependency ordering, fault handling, and reconciliation.

### 4.4 Live Invariants and Built-in Static Analysis

`@GraphInvariant` provides structural assertions that are enforced at two points:

1. **Compile time** — invariants validate the graph during `GoalCompiler.compile()`.
   Invalid graphs fail before any infrastructure is touched.
2. **Continuously** — the reconciliation loop validates invariants after every cycle.
   A fault mutation that violates an invariant is detected immediately.

This is richer static analysis than Terraform's `validate` + Sentinel, Pulumi's
CrossGuard, or Ansible's `--check` mode. Those are separate policy systems bolted onto
the declaration tool. CaseHub's invariants use the same graph language and pattern
vocabulary as the rules — write a rule once, it guards at compile time and enforces at
runtime.

The graph-structural nature enables assertions that policy-as-code tools struggle to
express: "no node is more than three dependency hops from a data source," "every fault
policy has a human tier as its final escalation," "no cyclic dependency exists between
zones."

---

## 5. Four Declaration Surfaces, One Runtime

### 5.1 YAML — Universal Operator Surface

The primary surface for operators who manage deployments without writing application
code. Must provide:

- Node declarations with typed specs
- Dependency expressions
- Graph rules expressible in YAML
- Invariants expressible in YAML
- Fault policy declarations with tier escalation
- Lifecycle phases
- Variable substitution and environment-aware configuration
- Conditional subgraph inclusion (`when:` on nodes/subgraphs)
- Cardinality-driven subgraph stamping (`forEach:` producing N copies from a collection)
- Composable modules (import + override)
- Lifecycle hooks for imperative steps within transitions

**Design principle:** Operators accepted Ansible's YAML because it never feels like
programming. Our YAML must maintain that contract — no Jinja2-style embedded
templating language, no escape hatches to a different syntax. The graph model should
handle what templating handles elsewhere.

### 5.2 TypeScript DSL — Type-Safe Power User Surface

For DevOps engineers who prefer programmatic control with type safety. Constrained
DSL for graph declarations with the TypeScript type checker as guardrails.

**Target persona:** Engineers who already use Pulumi or CDK and want type-safe
infrastructure definitions. Not a replacement for YAML — a complement for users who
value IDE autocomplete, type checking, and programmatic graph construction.

**LLM advantage:** TypeScript has massive training data representation. LLMs generate
TypeScript more effectively than HCL or custom DSLs. A TS surface enables
natural-language-to-infrastructure via LLM generation with type checking as guardrails.

### 5.3 Java Annotations — Platform Developer Surface

The existing `@DesiredState` / `@Node` / `@GraphRule` / `@GraphInvariant` model. Full
power, full type safety, direct access to the runtime API.

**Target persona:** Platform developers building the runtime itself, or application
developers embedding desired-state management in their Quarkus applications.

### 5.4 Visual Graph Editor — Direct Topology Manipulation

CaseHub is building a graphical tool that takes any YAML schema representing a graph
and displays it visually with full editing capabilities — add/remove/insert nodes, draw
edges, edit properties. It already targets case definitions, Quarkus Flow (serverless
workflow), and HTN plans. Desired-state graphs become another schema it supports.

No mainstream infrastructure tool has a visual graph editor. Terraform produces
read-only dependency diagrams via `terraform graph | dot`. Helm has no visualisation.
Ansible Tower/AWX manages jobs but doesn't show topology. Pulumi's console shows
resources as a list.

**What makes this compelling for desired-state:**

- **Live rule visualisation** — add a database node in the editor, the rule engine fires
  and a backup agent appears automatically in the graph view. Operators see structural
  rules working, not just read about them in documentation.
- **Invariant feedback** — delete an upstream dependency, the invariant violation
  highlights immediately with red borders and error annotations. Feedback before
  deployment, in the graph itself.
- **Lifecycle phase view** — phase 1 and phase 2 graphs side by side. Drag nodes between
  phases. Completion conditions and transition flow displayed visually.
- **Fault policy overlay** — show escalation tiers on nodes. Which nodes have three-tier
  escalation? Which have only retry? Visual at a glance.

**Target persona:** All operator personas. The visual editor is the primary authoring
experience; YAML is the serialisation format. An operator can work entirely in the
visual editor and never see YAML — or switch between them freely.

**Design constraint:** The YAML schema defined in #117 must be compatible with the
graph editor tool's expectations. The tool should inform the schema design.

### 5.5 Convergence on GraphDescriptor

All four surfaces compile to the same intermediate representation:

```
Visual editor ──→ YAML ──→ GraphDescriptor ──→ GoalCompiler ──→ ReconciliationLoop
YAML file            ──→ GraphDescriptor ──→ GoalCompiler ──→ ReconciliationLoop
TS DSL               ──→ GraphDescriptor ──→ GoalCompiler ──→ ReconciliationLoop
Java @annotations    ──→ GraphDescriptor ──→ GoalCompiler ──→ ReconciliationLoop
```

The runtime is surface-agnostic. The visual editor operates on YAML as its
serialisation format — it is a graphical frontend to the YAML surface, not a separate
compilation path. `GraphDescriptor` already carries nodes, dependencies,
fault policies, graph rules, and graph invariants. Extending it with lifecycle hooks,
conditional inclusion, and module composition is the design work ahead.

---

## 6. Key Design Challenge — Conditional and Iterated Subgraph Inclusion

The analysis of Terraform, Helm, and Ansible converges on one capability gap:
**conditional/iterated subgraph inclusion**. Everything else is either already covered
by the runtime, handled at a different layer (secrets, inventory), or straightforward
engineering (variable substitution).

### 6.1 The Problem

An operator needs to express:
- "Include this subgraph when environment is production" (conditional — 0 or 1 copies)
- "Stamp out one ingestion node per data source" (iteration — N copies from a collection)

Terraform's `count`/`for_each`, Helm's `range`, and Ansible's `loop`/`with_items` all
solve variants of this. Our declaration language needs an equivalent.

### 6.2 Approaches

**Approach A — Explicit directives in YAML:**

```yaml
nodes:
  ingestion-${source.id}:
    forEach: ${data_sources}
    as: source
    type: ingestion
    dependsOn: [${source.id}]
    spec:
      sourceRef: ${source.id}
      batchSize: ${source.batch_size}

  validator:
    when: ${environment == "production"}
    type: validator
    dependsOn: [quality-check]
```

Pros: Familiar to Terraform/Ansible users. Explicit control.
Cons: Approaches a templating language. The `forEach` directive is imperative — it runs
once at compile time, doesn't respond to graph changes.

**Approach B — Graph rules as the iteration mechanism:**

```yaml
rules:
  - name: ensure-ingestion-per-source
    match:
      source: { type: data-source }
    notExists:
      ingest: { type: ingestion, dependencyOf: source }
    add:
      - id: ingestion-${source.id}
        type: ingestion
        dependsOn: [${source.id}]
        spec:
          sourceRef: ${source.id}
```

Pros: Uses the existing rule engine. Rules fire on graph changes, not just at compile
time. Composable — multiple rules can independently add structure.
Cons: More indirect. An operator thinking "I need three ingestion nodes" has to think
in terms of rules ("when a source exists without an ingestion node, add one").

**Approach C — Hybrid: directives for simple cases, rules for structural patterns:**

`forEach` and `when` handle the simple iteration and conditional cases that operators
encounter most. Rules handle the structural patterns that emerge from complex topologies.
The two mechanisms coexist — `forEach` produces nodes; rules ensure structural invariants
across those nodes.

### 6.3 Recommendation

Approach C. The hybrid model plays to both audiences:
- Operators who think imperatively ("deploy one per region") use `forEach`/`when`
- Platform engineers who think structurally ("every database needs a backup agent") use
  rules
- Both compile to the same graph and participate in the same reconciliation loop

The `forEach` directive is syntactic sugar — it produces nodes during graph compilation,
which then participate in rule evaluation and invariant validation like any other node.

### 6.4 Interaction with Graph Rules

A critical design question: when `forEach` produces N copies of a subgraph, do rules
see the individual copies or the template? The answer must be: **individual copies**.

The compilation order is:
1. Resolve variables and `when` conditionals
2. Expand `forEach` directives into concrete nodes
3. Merge with base graph
4. Fire graph rules (fixed-point loop) on the expanded graph
5. Validate graph invariants
6. Emit final graph

Rules and invariants operate on the fully expanded graph. This means a rule like
"every ingestion node needs a downstream validator" applies to each `forEach`-generated
ingestion node individually — the structural pattern is enforced regardless of how
nodes were declared.

---

## 7. Module and Composition Model

### 7.1 Requirements

Operators need to compose deployment units from reusable building blocks:
- A "database tier" module with primary, replica, and backup nodes
- A "web tier" module with load balancer, app servers, and health checks
- A "monitoring" module that attaches to any tier

Each module should be:
- Parameterisable (override default values)
- Composable (combine modules into larger topologies)
- Self-contained (bring its own rules, invariants, and fault policies)

### 7.2 Relationship to Existing Tools

| Tool | Composition unit | Override mechanism |
|------|-----------------|-------------------|
| Terraform | Module | Input variables |
| Helm | Subchart | Values override |
| Ansible | Role | Role variables, defaults |
| CaseHub | Graph module (TBD) | Parameters (TBD) |

### 7.3 Open Questions

- Does a module define a subgraph with its own namespace, or do module nodes merge
  into the parent namespace?
- Can a module bring its own rules and invariants? If so, do they scope to the
  module's nodes or the entire graph?
- How do inter-module dependencies work? (A web tier depends on a database tier)
- What's the packaging model? (File inclusion, registry, artifact repository)

These questions need their own design session. The module model is the largest
unresolved design area.

---

## 8. Graph Rules in YAML

The pattern vocabulary developed for Java annotations (`@Match`, `@DirectDep`,
`@Reaches`, `@NotExists`) needs a YAML representation. The semantics are identical —
only the syntax changes.

### 8.1 Proposed YAML Syntax

```yaml
rules:
  - name: ensure-monitoring
    match:
      sink: { type: sink }
    notExists:
      monitor: { type: monitor, dependentOf: sink }
    add:
      - id: monitor-${sink.id}
        type: monitor
        dependsOn: [${sink.id}]
        spec:
          target: ${sink.id}

  - name: ensure-upstream-validator
    match:
      transformer: { type: transformer }
    directDep:
      source: { type: data-source, of: transformer, direction: dependencies }
    notExists:
      validator: { type: validator, of: source, direction: dependents }
    add:
      - id: validator-${source.id}
        type: validator
        dependsOn: [${source.id}]

invariants:
  - name: every-sink-has-upstream
    match:
      sink: { type: sink }
    directDep:
      upstream: { type: transformer, of: sink, direction: dependencies }

  - name: no-orphaned-nodes
    imperative: |
      for node in graph.nodes:
        if graph.dependenciesOf(node).isEmpty() and graph.dependentsOf(node).isEmpty():
          violation("Orphaned node: " + node.id)
```

### 8.2 Mapping to Internal Types

| YAML key | Java annotation | PatternKind |
|----------|----------------|-------------|
| `match:` | `@Match(type = "...")` | `MATCH` |
| `directDep:` | `@DirectDep(type = "...", of = "...", direction = ...)` | `DIRECT_DEP` |
| `reaches:` | `@Reaches(type = "...", of = "...", direction = ...)` | `REACHES` |
| `notExists:` | `@NotExists(type = "...", of = "...", direction = ...)` | `NOT_EXISTS` |
| `add:` | `return GraphMutations.addNodeDependingOn(...)` | (mutation output) |

The YAML rule compiles to a `GraphRuleDescriptor` with the same
`PatternParameterDescriptor` chain as the annotation path. The `GraphRuleEngine`
(and `GraphInvariantEngine`) doesn't know or care whether the descriptor came from
YAML, TypeScript, or Java annotations.

---

## 9. The CaseHub Platform Advantage

The four differentiators in §4 describe what desiredstate brings on its own. But
desiredstate doesn't operate in isolation — it's one module in a platform that includes
casehub-engine, casehub-ledger, casehub-work, casehub-blocks, casehub-qhorus, and
casehub-platform. The full platform provides capabilities that no standalone
infrastructure tool can match.

### 9.1 casehub-engine — Workflow Orchestration for Transitions

The `CaseTransitionExecutor` bridges desired-state declarations to casehub-engine's
case management. Each graph transition becomes a case with Worker(Workflow) phases —
not a flat script execution but a structured workflow with stages, milestones, and
human task integration. This means infrastructure transitions get the same workflow
management that business processes get: approval gates, parallel execution, conditional
branching, and full audit trail.

Terraform's providers execute API calls. Ansible's modules execute commands. CaseHub's
transition executor orchestrates multi-step workflows with governance.

### 9.2 casehub-ledger — Audit and Compliance

Every graph mutation, every fault event, every reconciliation cycle is auditable via
casehub-ledger. Infrastructure tools typically bolt on audit logging after the fact —
cloud provider audit logs, Terraform state history, Ansible callback plugins. CaseHub's
ledger is structural: it tracks what changed in the graph, why (which rule or fault
policy triggered it), and what the outcome was.

For regulated environments — healthcare, finance, government — the question isn't "can
you deploy infrastructure" but "can you prove what happened and why." Integrated audit
at the graph level answers that without additional tooling.

### 9.3 casehub-work — Human-in-the-Loop Escalation

When automated fault handling is exhausted, `WorkItemPendingApprovalHandler` creates
human work items via casehub-work. An operator gets a structured work item — not an
alert, not a page, but a work item with context: what failed, what was tried, what the
graph looks like, what approval is needed.

No infrastructure tool has native human-task integration. PagerDuty, OpsGenie, and
ServiceNow are external systems bolted on via webhooks. CaseHub's work management is
native — the same system that manages the graph manages the human response.

### 9.4 casehub-blocks — Event Summarisation for Operational Intelligence

casehub-blocks provides layered event summarisation (L1 raw → L2 classified → L3 phases
→ L4 narrative). Applied to infrastructure operations, this means the reconciliation
loop's CloudEvents don't just fire alerts — they're summarised into operational phases.

Instead of "node X faulted 47 times in the last hour" (L1), the operator sees "zone-3
entered degraded phase at 14:23, affecting 12 services, currently in recovery" (L3).
RAS Ganglia detect phase transitions, not raw event counts. This is qualitatively
different operational intelligence.

### 9.5 casehub-qhorus — AI Agent Integration

The three-tier fault escalation (auto-fix → AI agent → human) uses casehub-qhorus for
the AI tier. An AI agent receives the fault context — what failed, the graph topology,
the actual state — and can reason about the fix. This isn't "run a playbook" — it's
structured AI reasoning about infrastructure state with type-safe access to the graph
model.

As AI agents mature, this integration becomes more valuable. The infrastructure
management system that has native AI agent integration — with structured context, typed
graph access, and pluggable reasoning — has a structural advantage over systems where
AI is bolted on as an external tool.

### 9.6 The Compound Effect

Each platform module adds incremental value. But the compound effect is greater than
the sum: workflow-orchestrated transitions with audit trails, human escalation with
structured work items, AI-assisted fault diagnosis with graph context, operational
intelligence with phase-aware summarisation — all operating on the same graph model,
the same state, the same fault handling chain.

This is the moat. An infrastructure tool can add one of these capabilities. Integrating
all of them coherently requires a platform — and that platform is CaseHub.

---

## 10. Rule Engine Backend — Drools

### 10.1 The Problem

The `GraphRuleEngine` in `annotations/runtime` is a domain-specific mini rule engine:
pattern matching against graph nodes, fixed-point evaluation, conflict detection. This
is a subset of what Drools already does — and as YAML rules grow more expressive
(aggregations, temporal patterns, complex conditionals), the custom engine would need
to reimplement more and more of what PHREAK already handles.

The YAML rule syntax proposed in §8 maps directly to Drools patterns:

```yaml
# YAML rule
rules:
  - name: ensure-monitoring
    match:
      sink: { type: sink }
    notExists:
      monitor: { type: monitor, dependentOf: sink }
    add:
      - id: monitor-${sink.id}
        type: monitor
```

```drools
// Equivalent DRL
rule "ensure-monitoring"
when
    $sink: DesiredNode(type == "sink")
    not DesiredNode(type == "monitor", isDependentOf($sink))
then
    insert(addMonitorMutation($sink));
end
```

The fixed-point loop in `GraphRuleEngine` is a simplified forward chaining engine.
Drools does this with decades of optimisation behind it — Rete/PHREAK network, node
sharing, truth maintenance, conflict resolution strategies.

### 10.2 Decision

**YAML and operator-facing rules use Drools as the rule engine backend.** The
annotation path's lightweight custom engine (`GraphRuleEngine`, `GraphInvariantEngine`)
stays for compile-time evaluation where the overhead of Drools initialisation isn't
justified.

Two engines, two contexts:
- **Compile-time (annotations):** Custom engine. Simple, fast, no external dependency
  in the annotations module. Adequate for structural graph rewriting at Quarkus runtime
  init.
- **Runtime (YAML/operator path):** Drools. YAML rules compile to Drools rule
  definitions. Graph nodes become facts in working memory. Graph traversal operations
  (`dependenciesOf`, `dependentsOf`, BFS reachability) surface as custom constraint
  evaluators.

This split is interim — the target is Drools everywhere. The next version of Drools
(in development) will make unified integration practical for both the annotation and
YAML paths. Current Drools is used for the YAML/operator surface now; when the next
version lands, the custom annotation engines can migrate to Drools too, eliminating the
split entirely.

### 10.3 What Drools Adds Beyond Pattern Matching

Drools brings capabilities that the custom engine would eventually need to reimplement:

- **Conflict resolution strategies** — when multiple rules match, which fires first?
  The custom engine uses simple iteration order. Drools provides salience, agenda groups,
  and activation-group semantics.
- **Truth maintenance** — when a fact is retracted (a node is removed), Drools
  automatically retracts derived conclusions. The custom engine rechecks from scratch
  each iteration.
- **Temporal reasoning** — rules that reason about time ("if a node has been in
  DEGRADED state for more than 5 minutes, escalate"). The custom engine has no temporal
  model.
- **Accumulate/aggregate** — "if more than 3 nodes in a zone are faulted, trigger
  zone-level response." The custom engine can't aggregate.

These capabilities are directly relevant to the operator declaration language. An
operator writing "escalate if more than 3 services in a zone fail within 5 minutes"
needs temporal aggregation — and that's where Drools shines.

---

## 11. Self-Management as Forcing Function

CaseHub deploying and managing itself via desired-state declarations is the forcing
function for the design. If the language can manage CaseHub — with its multi-module
architecture, Quarkus runtime, database migrations, inter-service dependencies, and
fault handling requirements — it can manage any application.

### 9.1 What CaseHub Self-Management Looks Like

```yaml
desiredState:
  namespace: casehub
  name: platform

nodes:
  postgres:
    type: database
    spec:
      engine: postgres
      version: 16
    provision:
      - run: flyway -url=${db.url} migrate
      - verify: pg_isready -h ${node.host}

  engine:
    type: quarkus-service
    dependsOn: [postgres]
    spec:
      artifact: io.casehub:casehub-engine
      version: ${versions.engine}
      config:
        quarkus.datasource.jdbc.url: ${db.url}

  ledger:
    type: quarkus-service
    dependsOn: [postgres]
    spec:
      artifact: io.casehub:casehub-ledger
      version: ${versions.ledger}

  work:
    type: quarkus-service
    dependsOn: [engine, ledger]
    spec:
      artifact: io.casehub:casehub-work
      version: ${versions.work}

faultPolicies:
  - faultTypes: [PROVISION_FAILED]
    nodeTypes: [quarkus-service]
    tiers:
      - threshold: 3
        action: restart
      - threshold: 5
        action: rollback-version
      - threshold: 10
        action: human-review

rules:
  - name: every-service-gets-health-check
    match:
      service: { type: quarkus-service }
    notExists:
      check: { type: health-check, dependentOf: service }
    add:
      - id: health-${service.id}
        type: health-check
        dependsOn: [${service.id}]
        spec:
          endpoint: /q/health
          interval: 30s

invariants:
  - name: database-before-services
    match:
      service: { type: quarkus-service }
    directDep:
      db: { type: database, of: service, direction: dependencies }
```

This single declaration covers what would currently require Terraform (database,
service instances), Ansible (Flyway migration, health check configuration), and Helm
(Kubernetes manifests for services) — with continuous reconciliation, fault escalation,
and structural invariants that none of those tools provide individually.

---

## 12. Phasing

### Phase 1 — Graph capabilities (this repo, desiredstate)

Build the YAML surface and graph capabilities with mock provisioners:
- YAML → GraphDescriptor pipeline (#109 plumbing)
- Conditional subgraph inclusion (`when:`)
- Iterated subgraph inclusion (`forEach:`)
- Variable substitution in spec values
- YAML-expressed rules and invariants
- Module composition model
- Lifecycle hooks in node declarations

Mock provisioners simulate the ops scenarios (deploy a cluster, scale a tier, handle
a failure) without real infrastructure. The pipeline-annotated and expansion examples
serve as the proving ground.

### Phase 2 — OPS integration

Real `NodeProvisioner` implementations backed by actual infrastructure:
- Kubernetes provisioner (pod, service, deployment, configmap)
- VM provisioner (SSH-based, replacing Ansible's agent-free model)
- Database provisioner (create, migrate, verify)
- Quarkus service provisioner (build, deploy, configure)
- `ActualStateAdapter` reading real cluster state

CaseHub self-management as the first real deployment target.

### Phase 3 — Validation

Side-by-side comparisons with real deployment scenarios:
- Take a CaseHub deployment currently managed by existing tools
- Express it in desired-state YAML
- Compare: readability, fault handling, composition, operational experience
- Identify gaps and iterate

---

## 13. Open Questions

### Visual editor and code constructs

The visual editor operates on the graph — concrete nodes and edges. But the YAML
declaration can contain code constructs that don't map directly to graph elements:

- **`forEach:` loops** — the YAML declares a template node that expands into N concrete
  nodes. The editor needs to represent both the template (the authoring intent) and
  the expansion (the runtime result). Options: (a) show the template as a special
  "group" node with a cardinality badge, expanding on demand; (b) show only the
  expanded concrete nodes and reconstruct the template when serialising back to YAML;
  (c) dual view — template view for authoring, expanded view for verification.

- **`when:` conditionals** — a node that may or may not exist depending on a condition.
  The editor needs to show it as present-but-conditional (dimmed? dashed border?) or
  offer a toggle to preview different condition states.

- **Rules** — rules produce nodes that don't exist in the YAML declaration. The editor
  could show rule-generated nodes differently (auto-generated badge, different colour)
  and make them read-only — they're produced by rules, not manually authored.

- **Variable references** — `${env.batch_size}` in a spec field. The editor shows the
  raw reference or resolves it against a selected environment profile.

This is a design challenge that needs its own exploration — the graph editor team and
the YAML schema design (#117) should coordinate early. The resolution will likely
influence the YAML schema itself: constructs that are impossible to represent visually
should be reconsidered.

---

1. **Imperative scripting in YAML lifecycle hooks** — how expressive should the
   `provision:` / `deprovision:` sections be? Full shell commands? A constrained
   step vocabulary? Reference to external scripts?

2. **Provider/provisioner ecosystem strategy** — build from scratch, bridge from
   Terraform providers (like Pulumi does), or start with CaseHub-specific provisioners
   and expand?

3. **Module packaging and distribution** — file inclusion on classpath? A registry?
   Versioned artifacts? This is a significant design area that determines the
   ecosystem's growth model.

4. **TS DSL scope** — if YAML can express rules and invariants, when does an operator
   reach for TypeScript? Programmatic graph generation? Complex conditionals? LLM-generated
   declarations?

5. **State management** — the reconciliation loop maintains runtime state. But what about
   the declaration state? Versioning, rollback, environment promotion (dev → staging →
   prod)?

6. **Multi-tenancy** — desired-state already has `tenancyId` throughout. How does the
   declaration language express per-tenant graph variations?

---

## References

- [casehubio/casehub-desiredstate#116](https://github.com/casehubio/casehub-desiredstate/issues/116) — design issue
- [casehubio/casehub-desiredstate#109](https://github.com/casehubio/casehub-desiredstate/issues/109) — YAML plumbing
- [casehubio/casehub-desiredstate#108](https://github.com/casehubio/casehub-desiredstate/issues/108) — TypeScript DSL
- [casehubio/casehub-desiredstate#114](https://github.com/casehubio/casehub-desiredstate/issues/114) — shared pattern matching
- [casehubio/casehub-desiredstate#25](https://github.com/casehubio/casehub-desiredstate/issues/25) — invariant vs achievement goals
- [2026-06-07-desired-state-management-research.md](/Users/mdproctor/claude/casehub/desiredstate/docs/research/2026-06-07-desired-state-management-research.md) — original research doc
- [2026-08-26-graph-rules-invariants-design.md](/Users/mdproctor/claude/casehub/desiredstate/docs/specs/issue-115-graph-rules-invariants/2026-08-26-graph-rules-invariants-design.md) — graph rules + invariants spec
