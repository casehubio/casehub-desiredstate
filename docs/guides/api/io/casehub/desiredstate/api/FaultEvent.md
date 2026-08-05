# io.casehub.desiredstate.api.FaultEvent

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

## Fields

### `detail` (`java.lang.String`)

### `node` (`io.casehub.desiredstate.api.NodeId`)

### `type` (`io.casehub.desiredstate.api.FaultType`)

## Record Components

### `detail` (`java.lang.String`)

### `node` (`io.casehub.desiredstate.api.NodeId`)

### `type` (`io.casehub.desiredstate.api.FaultType`)

## Constructors

### `public FaultEvent(io.casehub.desiredstate.api.NodeId node, io.casehub.desiredstate.api.FaultType type, java.lang.String detail)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.NodeId`)
- `type` (`io.casehub.desiredstate.api.FaultType`)
- `detail` (`java.lang.String`)

## Methods

### `public java.lang.String detail()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.desiredstate.api.NodeId node()`

### `public final java.lang.String toString()`

### `public io.casehub.desiredstate.api.FaultType type()`
