# io.casehub.desiredstate.api.ProvisionContext

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

Context passed to a provisioner when provisioning a node.
Carries tenancy identity, the full desired-state graph for reference,
and optional approval context when re-entering after a PendingApproval cycle.

## Fields

### `approval` (`io.casehub.desiredstate.api.PlanApproval`)

### `graph` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `approval` (`io.casehub.desiredstate.api.PlanApproval`)

### `graph` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `tenancyId` (`java.lang.String`)

## Constructors

### `public ProvisionContext(java.lang.String tenancyId, io.casehub.desiredstate.api.DesiredStateGraph graph)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `graph` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `public ProvisionContext(java.lang.String tenancyId, io.casehub.desiredstate.api.DesiredStateGraph graph, io.casehub.desiredstate.api.PlanApproval approval)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `graph` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `approval` (`io.casehub.desiredstate.api.PlanApproval`)

## Methods

### `public io.casehub.desiredstate.api.PlanApproval approval()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public io.casehub.desiredstate.api.DesiredStateGraph graph()`

### `public boolean hasApproval()`

### `public final int hashCode()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public io.casehub.desiredstate.api.ProvisionContext withApproval(io.casehub.desiredstate.api.PlanApproval approval)`

#### Parameters

- `approval` (`io.casehub.desiredstate.api.PlanApproval`)
