---
layout: post
title: "The Configure Call Nobody Made — Credential Resolution for the Terraform Bridge"
date: 2026-10-28
entry_type: article
subtype: diary
projects: [casehubio/casehub-desiredstate]
tags: [terraform, bridge-adapter, credentials, multi-tenant, desired-state]
series: issue-190-terraform-bridge-phase-2
---

# The Configure Call Nobody Made — Credential Resolution for the Terraform Bridge

The Terraform bridge from [Phase 1](2026-10-27-mdp01-terraform-bridge-adapter.md) works
with `hashicorp/null` and `hashicorp/random`. Those providers don't need credentials —
they generate values or do nothing, which is perfect for testing the protocol layer. But
point the bridge at `hashicorp/aws` and nothing happens. No error, no authentication
failure, just a provider that hasn't been told who it's acting for.

The gap turned out to be exactly one missing function call.

## The Gap

Every Terraform provider expects a `ConfigureProvider` RPC after the gRPC channel is
established. This is where credentials go — AWS access keys, GCP service account JSON,
Azure tenant IDs. Without it, the provider starts up, happily reports its schema, and
then fails on every operation that touches real infrastructure.

The Java side had the code. `ProtocolV6Adapter.configure()` encodes a config map as
msgpack, sends it to `stub.configureProvider()`, checks the diagnostics. `ProtocolV5Adapter`
does the same with JSON encoding. Both were implemented during Phase 1. Neither was ever
called.

`ProviderProcessManager.start()` creates the channel, builds the protocol adapter, and
returns. No configure step. The method that does the actual work existed — it just had
no caller. A classic case of building the mechanism without the trigger.

## The Statefulness Insight

I initially assumed credential injection would be straightforward — resolve credentials,
call `configure()`, done. But Claude's exploration of the proto files surfaced something
that changed the design: Terraform providers are stateful after `ConfigureProvider`.

Once you configure a provider with Tenant A's AWS credentials, every subsequent
`PlanResourceChange` and `ApplyResourceChange` call uses those credentials. There's no
per-request credential parameter. There's no "reconfigure" RPC. The credentials are baked
into the provider process for its lifetime.

This kills the obvious approach of sharing one provider process across tenants and
reconfiguring it on each call. Even if reconfiguration worked (and the Terraform ecosystem
has never tested that path — providers don't expect it), the risk of credential leakage
between tenants makes it a non-starter.

The answer is per-tenant provider processes.

## TenantProviderRegistry

`TenantProviderRegistry` maps `(providerAddress, tenancyId)` to a dedicated
`ProviderProcessManager`. On first access for a tenant, it starts a fresh provider
binary, resolves the tenant's credentials, and calls `configure()`.

![Credential Resolution — Per-Tenant Provider Processes](images/credential-resolution-flow.svg)

The registry uses `ConcurrentHashMap.computeIfAbsent` for lazy creation — the first
reconciliation cycle for a tenant pays the process startup cost; subsequent cycles get
the cached protocol immediately. `ProviderProcessManager` itself didn't change. It still
manages a single process. The multi-tenancy concern lives entirely in the registry.

This separation matters for what comes next. Issue #194 adds process pooling — eviction
policies, idle timeouts, maximum process counts. All of that complexity lands in the
registry without touching the single-process manager or the bridge provider. Clean
boundaries pay forward.

## Schema-Driven Credential Mapping

CaseHub's platform already has a `CredentialResolver` SPI — `resolve(String credentialRef)`
returns a `Map<String, String>`. Quarkus deployments back it with vault or AWS Secrets
Manager. Spring deployments read from environment properties. The SPI exists, has
implementations, and is tested. No reason to build another one.

The question was how to map the generic credential map to provider-specific config
attributes. AWS wants `access_key`, `secret_key`, `region`. GCP wants `credentials`,
`project`, `region`. Azure wants `client_id`, `client_secret`, `tenant_id`,
`subscription_id`. Different providers, different shapes.

The answer was already in the proto. `GetProviderSchema` returns not just resource schemas
but a provider-level schema — field 1 in the response, which the Phase 1 code was ignoring
entirely. That schema declares exactly what config attributes the provider expects, with
types and required/optional flags. The AWS provider's schema says "I need `access_key`
(string, optional), `secret_key` (string, optional, sensitive), `region` (string, optional)"
and fifty more attributes.

So the mapping is a convention, not a mapping layer. Users store credentials under the
`credentialRef` name with provider-native keys:

```properties
my-aws-creds.access_key=<REDACTED>
my-aws-creds.secret_key=<REDACTED>
my-aws-creds.region=us-east-1
```

`CredentialResolver.resolve("my-aws-creds")` returns those as a flat map. The map goes
straight into `protocol.configure()`. The provider schema is there for validation — we
can warn if required attributes are missing — but no translation is needed. The provider
already told us what it wants.

## Lazy Credential Refresh

Credentials expire. STS tokens, OAuth tokens, anything time-bounded. When they do, the
provider process is poisoned — every operation fails with authentication errors until
fresh credentials arrive.

`TerraformErrorClassifier` already classifies errors containing `auth`, `credential`,
`unauthorized`, `permission_denied`, or `accessdenied` as `AUTHENTICATION`. That
classification was there for error reporting. Now it drives recovery.

![Lazy Credential Refresh — Auth Error Retry](images/auth-retry-flow.svg)

`TerraformBridgeProvider` wraps every operation in a `withAuthRetry` method. If the
result is a `Failed` with `AUTHENTICATION` category, it evicts the tenant's process
from the registry and retries once. The retry triggers a fresh `getProtocol()` call —
new process, new `CredentialResolver.resolve()`, new `configure()`. If the retry also
fails with an auth error, the failure propagates. Bad credentials are bad credentials;
retrying won't fix them.

One failed request per credential rotation event. That's the cost. Proactive refresh
(tracking `expires-at`, restarting before expiry) would avoid even that, but adds timer
management and TTL tracking for something that happens infrequently. The reactive
approach costs almost nothing to implement and covers the common case.

## The Bridge Manifest Gets a Field

The `credentialRef` — the logical name passed to `CredentialResolver` — lives in the
bridge manifest YAML:

```yaml
bridge:
  protocol: terraform
  credentialRef: my-aws-creds
  provider:
    namespace: hashicorp
    type: aws
    version: "5.0.0"
```

Optional. Omit it and the bridge skips `configure()` entirely — `hashicorp/null` and
`hashicorp/random` continue to work exactly as before. Backward compatibility wasn't
an afterthought; it was a constraint from the start. Existing bridge manifests, existing
tests, existing integrations — all unchanged.

## What This Opens Up

With credential resolution in place, the Terraform bridge can talk to real cloud
providers. That's the immediate practical win. But the per-tenant process architecture
opens up more than just credentials.

Process pooling (#194) can now manage the resource cost — idle timeout eviction, LRU
policies, maximum process counts per provider type. The `TenantProviderRegistry` already
has `evict()` and `close()` — pooling adds the scheduling logic on top.

Cloud integration tests (#195) can exercise the full lifecycle against actual AWS or GCP
resources — provision, drift detection, deprovision — with real credentials flowing
through the same path production will use.

And the provider schema enhancement — capturing the provider-level config schema that
Phase 1 was ignoring — has uses beyond credential validation. It's the foundation for
auto-generating documentation, validating bridge manifests at build time, and potentially
exposing provider configuration as a first-class surface in the YAML graph declarations.

The bridge is no longer a proof of concept. It's a production credential path away from
managing real infrastructure.
