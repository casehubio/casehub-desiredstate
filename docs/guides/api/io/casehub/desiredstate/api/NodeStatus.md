# io.casehub.desiredstate.api.NodeStatus

**Package:** `io.casehub.desiredstate.api`

**Kind:** `enum`

The observed status of a node in the actual environment.

## Enum Constants

### `ABSENT` (`io.casehub.desiredstate.api.NodeStatus`)

Node does not exist.

### `DRIFTED` (`io.casehub.desiredstate.api.NodeStatus`)

Node exists but has diverged from its spec.

### `PRESENT` (`io.casehub.desiredstate.api.NodeStatus`)

Node exists and matches its spec.

### `UNKNOWN` (`io.casehub.desiredstate.api.NodeStatus`)

Node status could not be determined.

## Constructors

### `private NodeStatus()`

## Methods

### `public static io.casehub.desiredstate.api.NodeStatus valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.desiredstate.api.NodeStatus[] values()`
