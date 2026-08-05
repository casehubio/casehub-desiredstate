# io.casehub.desiredstate.api.StateEvent

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

## Fields

### `detail` (`java.lang.String`)

### `newStatus` (`io.casehub.desiredstate.api.NodeStatus`)

### `node` (`io.casehub.desiredstate.api.NodeId`)

## Record Components

### `detail` (`java.lang.String`)

### `newStatus` (`io.casehub.desiredstate.api.NodeStatus`)

### `node` (`io.casehub.desiredstate.api.NodeId`)

## Constructors

### `public StateEvent(io.casehub.desiredstate.api.NodeId node, io.casehub.desiredstate.api.NodeStatus newStatus, java.lang.String detail)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.NodeId`)
- `newStatus` (`io.casehub.desiredstate.api.NodeStatus`)
- `detail` (`java.lang.String`)

## Methods

### `public java.lang.String detail()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.desiredstate.api.NodeStatus newStatus()`

### `public io.casehub.desiredstate.api.NodeId node()`

### `public final java.lang.String toString()`
