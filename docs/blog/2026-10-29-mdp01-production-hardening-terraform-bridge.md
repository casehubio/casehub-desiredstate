---
layout: post
title: "Five Gaps Between a Bridge and Production — Hardening the Terraform Adapter"
date: 2026-10-29
entry_type: article
subtype: diary
projects: [casehubio/casehub-desiredstate]
tags: [terraform, bridge-adapter, security, mtls, gpg, desired-state, production-readiness]
series: issue-190-terraform-bridge-phase-2
---

# Five Gaps Between a Bridge and Production — Hardening the Terraform Adapter

The [Phase 1 bridge](2026-10-27-mdp01-terraform-bridge-adapter.md) speaks the go-plugin gRPC protocol. It launches provider binaries, parses handshakes, translates plan/apply cycles. Point it at `hashicorp/null` and it works. Point it at `hashicorp/aws` and it works too — if you squint.

But "works" and "production-ready" are separated by everything Terraform CLI does silently. The protocol layer is table stakes. What makes `terraform apply` safe to run against real infrastructure is the invisible scaffolding around it: credential injection, channel encryption, binary verification, metadata handling. None of that existed in Phase 1.

Phase 2 is five issues, each closing one of those gaps. The [credential resolution entry](2026-10-28-mdp15-credential-resolution-terraform-bridge.md) covers the first and largest — per-tenant process isolation with `TenantProviderRegistry`. This entry covers the rest: the security surface that turns a protocol adapter into something you'd trust with an AWS account.

## The Gaps

Every one of these is something Terraform CLI handles by default. A user running `terraform apply` never thinks about them. A platform embedding the same providers has to.

**Sensitive attributes.** Provider schemas mark certain fields — passwords, API keys, connection strings — as `sensitive`. Terraform CLI never logs them, never shows them in plan output, never writes them to state in plaintext. The bridge's `PluginFieldDef` had no concept of this. A spec schema describing an RDS instance would happily expose the master password in validation error messages.

**Import.** `terraform import` reads an existing cloud resource's state and adopts it into management. Without it, every resource must be created from scratch — even ones that already exist. For a platform managing existing infrastructure, this is a hard requirement, not a convenience feature.

**Auto-mTLS.** Since go-plugin v5 (~2022), every Terraform provider starts with mutual TLS by default. The host generates ephemeral certificates, exchanges them with the provider via the handshake, and encrypts the gRPC channel. Without it, credentials and resource state flow over a plaintext Unix socket. On a shared host, that's a vulnerability.

**GPG verification.** The Terraform registry signs every provider release. The SHA256SUMS file is GPG-signed by the publisher's key. Terraform CLI verifies this chain on every download. The bridge was checking SHA-256 hashes — integrity — but not GPG signatures — authenticity. A compromised mirror could serve a binary with a matching checksum and no alarm would fire.

## Sensitive and Deprecated Attributes

The simplest change — two boolean flags on `PluginFieldDef`:

```java
public record PluginFieldDef(
    String name,
    String type,
    boolean required,
    boolean sensitive,    // new
    boolean deprecated    // new
) {}
```

`sensitive` means: mask this field in logs, validation errors, and any surface that could expose its value. `deprecated` means: warn when a spec uses this field, suggest the replacement. Both come from the provider schema's `Block.Attribute` proto message, where they've been present since tfplugin5.

The change is small but the principle matters. A bridge that treats all fields equally can't enforce the security boundaries the provider author defined. The sensitive flag is the provider saying "this field contains secrets" — ignoring it means CaseHub would be less secure than the tool it's replacing.

## Import — Adopting Existing Resources

When a team has existing infrastructure and wants CaseHub to manage it, they can't tear it down and recreate it. They need to say "this node IS this cloud resource" and have the reconciliation loop adopt it.

The design uses a node-level `importId` field on `BridgeNodeSpec`. Declarative, fits the desired-state model:

```yaml
nodes:
  my-existing-bucket:
    type: tf:hashicorp/aws:aws_s3_bucket
    spec:
      importId: my-production-bucket
      bucket: my-production-bucket
```

The first reconciliation cycle sees the node as ABSENT (no state in `BridgeOutputStateStore`), but instead of plan+apply, `BridgeProvisioner` calls `ImportResourceState`. The response populates the state store. Subsequent cycles see the resource as PRESENT and manage it normally — drift detection, fault escalation, the full reconciliation loop.

The `importId` stays on the spec permanently. It's provenance — "this node came from cloud resource X." If state store data is lost, the next cycle re-imports from cloud automatically. No recovery procedure needed.

The bridge exposes this through `ImportCapable`, a mixin interface on `BridgeProvider` that follows the existing `PlanCapable` and `SuspendCapable` pattern. Any bridge adapter — not just Terraform — can implement import by adding the mixin.

## Auto-mTLS — Encrypting the Provider Channel

This is the one that surprised me. I expected mTLS to be complex — certificate management, rotation policies, trust store configuration. It turned out the go-plugin protocol makes it almost trivial, once you understand the handshake.

![mTLS Handshake Flow](images/mtls-handshake.svg)

The host generates an ephemeral ECDSA P-521 certificate — self-signed, CN=localhost, valid for 30 years. It passes the PEM-encoded cert to the provider via `PLUGIN_CLIENT_CERT` in the process environment. The provider generates its own cert, starts its gRPC server with TLS, and writes the server certificate as base64-encoded DER in the handshake line — the sixth pipe-separated field that Phase 1 was silently ignoring.

Both sides now have each other's certificate. The host builds a Netty `SslContext` with the client cert and the server cert. The gRPC channel becomes mutually authenticated.

```java
SslContext sslContext = SslContextBuilder.forClient()
    .keyManager(clientBundle.privateKey(), clientBundle.certificate())
    .trustManager(serverCert)
    .sslProvider(SslProvider.JDK)
    .build();
```

Two things worth noting. First, `SslProvider.JDK` — not OpenSSL, not BoringSSL. Go-plugin uses ECDSA P-521, which BoringSSL doesn't support for client authentication. JDK's `SunJSSE` handles it fine, and performance is irrelevant for local subprocess IPC. Second, certificate rotation is free — `ProviderProcessManager.start()` calls `host.shutdown()` then `host.connect()`, and every `connect()` generates fresh certificates. Health check failure, credential rotation, tenant eviction — each triggers a restart with new certs. No timer, no TTL, no rotation policy.

The backward compatibility story writes itself. If we set `PLUGIN_CLIENT_CERT` and the provider doesn't support mTLS, it ignores the env var, starts plaintext, and the handshake line has no sixth field. `GoPluginHandshake.hasTls()` returns false. The channel falls back to `.usePlaintext()`. Old providers work. New providers get encryption. No configuration flag.

## GPG Verification — Proving the Binary Is Genuine

SHA-256 proves the binary wasn't corrupted. GPG proves it was signed by the publisher. Both matter; only one was implemented.

The Terraform registry protocol includes everything needed for verification in the download API response:

```json
{
  "shasums_url": "https://releases.hashicorp.com/.../SHA256SUMS",
  "shasums_signature_url": "https://releases.hashicorp.com/.../SHA256SUMS.sig",
  "signing_keys": {
    "gpg_public_keys": [{
      "key_id": "51852D87348FFC4C",
      "ascii_armor": "-----BEGIN PGP PUBLIC KEY BLOCK-----\n..."
    }]
  }
}
```

Phase 1's `ProviderRegistryClient` was parsing this response with raw `JsonNode` and extracting only two fields: `download_url` and `shasum`. Everything else was ignored. We replaced the raw JSON access with a typed `RegistryPackageResponse` record and added the full verification chain.

![GPG Verification Trust Chain](images/gpg-verification-chain.svg)

`GpgSignatureVerifier` is a pure function — give it the SHA256SUMS content, the `.sig` bytes, and the public keys from the API response, it returns `Verified(keyId)` or `Failed(reason)`. No I/O, no state, easy to test with programmatic fixtures. Bouncy Castle's `bcpg-jdk18on` does the OpenPGP heavy lifting — parse the ASCII-armored keys into a `PGPPublicKeyRingCollection`, read the detached binary signature, verify against the content.

The trust model has two modes. By default, any key the registry provides is trusted — this matches Terraform CLI and works with public and private registries out of the box. For production lockdown, the bridge manifest YAML accepts a `trustedKeyIds` list:

```yaml
bridge:
  protocol: terraform
  trustedKeyIds:
    - "51852D87348FFC4C"
```

With pinning enabled, signatures from unlisted keys are rejected even if the registry returns them. This protects against a compromised registry response that includes a rogue signing key — the signature would verify cryptographically, but the key ID wouldn't be in the trusted set.

When the registry response is missing signing data — older registries, custom registries that don't implement the full protocol — the verifier falls back to the direct `shasum` field. Degraded security, logged as a warning, but the download doesn't fail. Pragmatism over purity.

## The Production Readiness Checklist

Each of these five issues maps to something Terraform CLI users take for granted:

| What Terraform CLI does | Phase 1 | Phase 2 |
|---|---|---|
| Injects provider credentials via `ConfigureProvider` | Missing | Per-tenant processes with `CredentialResolver` |
| Respects `sensitive` and `deprecated` on schema attributes | Missing | `PluginFieldDef` flags |
| Imports existing resources via `terraform import` | Missing | `ImportCapable` mixin, declarative `importId` |
| Encrypts provider channel with mTLS | Plaintext | ECDSA P-521 ephemeral certs, automatic |
| Verifies GPG signatures on registry downloads | SHA-256 only | Full GPG chain + key pinning |

None of these required protocol changes. The gRPC wire format, the handshake, the registry API — all unchanged from Phase 1. What changed is everything around the protocol: the security posture, the operational model, the trust boundaries.

The bridge is two issues from complete: process pooling (#194) for resource management and integration tests (#195) against real cloud providers. After that, the gap between CaseHub's Terraform bridge and `terraform apply` closes to zero on the security and operational surface. What CaseHub adds — continuous reconciliation, drift detection, fault escalation, dependency-aware ordering, lifecycle phases — is the part Terraform CLI doesn't do at all.
