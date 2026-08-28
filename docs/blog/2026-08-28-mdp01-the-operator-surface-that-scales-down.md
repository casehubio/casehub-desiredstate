---
entry_type: note
subtype: diary
sequence: mdp01
tags: [yaml, operator-surface, iac, design, research]
---

# The Operator Surface That Scales Down

Terraform handles Day 0 well. Helm handles Day 2. Ansible handles Day 1 if you have machines to SSH into. Each tool chose to be excellent at one interface and left the rest to someone else. The result is that a greenfield platform in 2026 typically involves two tools — Terraform plus ArgoCD for K8s-native teams, Terraform plus Ansible for VM-heavy shops. Nobody deliberately architects a three-tool stack; it accumulates.

The lifecycle gap is not "these tools are bad." They are genuinely excellent at what they do. The gap is that provisioning, deployment, drift detection, and fault handling are treated as separate concerns requiring separate tools, separate state models, and glue scripts at the handoff points. Terraform's own documentation calls provisioners a "last resort." Helm has no drift detection at all.

We have been exploring whether a graph-based desired-state model can address this gap — not by replacing existing tools, but by investigating what becomes possible when the declaration language has structural awareness of the graph it manages.

## Four capabilities that existing tools do not offer

The CaseHub desired-state runtime provides four capabilities that none of the established IaC tools have individually:

**Structural graph rewriting.** An operator declares "every database gets a backup agent" as a pattern rule, not as N explicit resource blocks. The rule fires whenever the graph changes — add three databases, get three backup agents. Terraform requires one resource block per agent. Helm requires a Go template loop.

**Fault-driven adaptation.** When a node fails, the graph responds structurally. A three-tier escalation — automatic retry, then AI review, then human work item — is expressed as a fault policy declaration, not as a PagerDuty runbook. The fault count, the threshold, the review node spec: all declarative. No external incident management wiring.

**Continuous invariants.** "Every application must have an upstream load balancer" is not checked once at plan time. It runs on every reconciliation cycle. OPA can gate a Terraform plan; it cannot enforce a constraint continuously in a live system.

**Lifecycle phases.** A single declaration describes "provision infrastructure, then deploy the platform, then run the application in steady state." Each phase has its own completion condition. The runtime manages phase transitions. Today this requires separate Terraform configs and Helm releases with CI glue between them.

## The YAML design challenge: power without complexity

The runtime capabilities exist in Java. Making them accessible to operators who do not write Java — without turning YAML into a programming language — is the harder problem.

We spent time stress-testing whether the design scales down. The question is not "can it express complex graphs?" — it can. The question is "does it stay simple when the graph is simple?"

A flat deployment of a web application and a database:

```yaml
desiredState:
  namespace: myapp
  name: webapp
variables:
  db_password: "secret"
nodes:
  database:
    type: postgresql
    spec:
      name: myapp
      version: "15"
      password: "${var.db_password}"
  app:
    type: web-app
    dependsOn: [database]
    spec:
      image: myapp:latest
```

Twenty lines. One file. Six concepts (desiredState, variables, nodes, type, spec, dependsOn). Fifteen percent boilerplate. Compare: Terraform requires ~35 lines and 40% boilerplate for the same deployment. Helm requires ~35 lines across 3-5 files with Go template syntax.

An operator who only needs this never encounters graph rules, invariants, fault policies, or lifecycle phases. Those features are additive YAML blocks at higher levels of the complexity ladder — invisible until needed.

## Progressive disclosure, not progressive confusion

The design layers power in five levels:

| Level | What you add | What you still don't see |
|-------|-------------|-------------------------|
| L0 | Nodes + dependencies | Everything else |
| L1 | Variables, conditional nodes | Rules, invariants, faults, lifecycle |
| L2 | Fault policies, invariants | Rules, lifecycle, forEach, modules |
| L3 | Graph rules, lifecycle phases | forEach, modules |
| L4 | Cardinality stamping, modules | Nothing — full power |

Each level adds optional top-level blocks. Nothing at a higher level changes the syntax of lower levels. An L0 operator and an L4 operator write the same `nodes:` section.

## What we do not have

Honest assessment: the ecosystem gap is real. Terraform has ~4,000 providers. CaseHub has the providers the platform ships with. A team adopting this writes their own provisioners in Java — or uses the ones the platform provides.

There is no preview mode yet. Terraform `plan` is the primary operator interface; every production workflow gates on it. An operator writing YAML with graph rules and forEach expansion cannot see the expanded graph before it reaches the reconciliation loop. This is a known gap with a clear design, not a missing concept.

The runtime requires a JVM. Terraform is a 50MB static binary. For operators working within a JVM platform — which is the target — this is invisible. For the broader DevOps community, it is a filter.

## Where this leads

The same seven YAML features — rules, invariants, fault policies, conditional inclusion, cardinality stamping, modules, lifecycle phases — also map naturally to a constrained TypeScript DSL surface. TypeScript's type checker becomes the guardrail that prevents the Helm trap (where a templating language grows unboundedly). A pre-parser enforces purity: no side effects, no I/O, just typed graph declarations.

Three surfaces, one runtime. Operators author in YAML. DevOps engineers author in TypeScript. Platform engineers author in Java annotations. All three compile to the same intermediate representation and run through the same reconciliation loop.

The research question is whether a single graph-aware declaration language can cover the full infrastructure lifecycle — from provisioning through steady-state operation — without becoming another tool that tries to do everything and does nothing well. The answer, so far, is that the graph model makes this tractable. Whether it makes it practical is what implementation will prove.
