# io.casehub.desiredstate.api.NodeProvisioner

**Package:** `io.casehub.desiredstate.api`

**Kind:** `interface`

SPI for provisioning and deprovisioning nodes in the desired-state graph.

<p><b>Re-entry protocol for PendingApproval:</b>
<ul>
  <li>`provision()` may return `PendingApproval(nodeId, planReference)`
      to request human approval before proceeding.</li>
  <li>If approval is granted, `provision()` will be called again with
      `context.approval()` non-null, carrying the `PlanApproval`
      (planReference, approvedBy, approvedAt).</li>
  <li>Provisioners should check `context.hasApproval()` and behave accordingly:
      proceed with the approved plan, or return a new `PendingApproval` if
      the plan is stale.</li>
  <li>The `planReference` returned in `PendingApproval` is opaque to the
      runtime — it is round-tripped back to the provisioner unchanged.</li>
</ul>

<p>Same protocol applies to `deprovision()` via
`DeprovisionContext.approval()`.

## Methods

### `public abstract io.casehub.desiredstate.api.DeprovisionResult deprovision(io.casehub.desiredstate.api.DesiredNode node, io.casehub.desiredstate.api.DeprovisionContext context)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.DesiredNode`)
- `context` (`io.casehub.desiredstate.api.DeprovisionContext`)

### `public abstract java.util.Set<io.casehub.desiredstate.api.NodeType> handledTypes()`

Declares the node types this provisioner handles. The runtime routes
provision/deprovision calls by NodeType via `NodeProvisionerRouter`.

#### Returns

non-empty set of handled types; overlapping types across provisioners
        cause construction-time failure

### `public abstract io.casehub.desiredstate.api.ProvisionResult provision(io.casehub.desiredstate.api.DesiredNode node, io.casehub.desiredstate.api.ProvisionContext context)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.DesiredNode`)
- `context` (`io.casehub.desiredstate.api.ProvisionContext`)

### `public default java.time.Duration resyncInterval()`

Declares the resync interval for periodic reconciliation of handled types.
Must be >= 1 second; validated at router construction time.

#### Returns

resync interval (default: 5 minutes)
