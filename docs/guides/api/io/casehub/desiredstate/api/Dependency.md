# io.casehub.desiredstate.api.Dependency

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

A directed dependency edge: node `from` depends on node `to`.
The runtime guarantees `to` is provisioned before `from`.

## Fields

### `from` (`io.casehub.desiredstate.api.NodeId`)

### `to` (`io.casehub.desiredstate.api.NodeId`)

## Record Components

### `from` (`io.casehub.desiredstate.api.NodeId`)

### `to` (`io.casehub.desiredstate.api.NodeId`)

## Constructors

### `public Dependency(io.casehub.desiredstate.api.NodeId from, io.casehub.desiredstate.api.NodeId to)`

#### Parameters

- `from` (`io.casehub.desiredstate.api.NodeId`)
- `to` (`io.casehub.desiredstate.api.NodeId`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public io.casehub.desiredstate.api.NodeId from()`

### `public final int hashCode()`

### `public io.casehub.desiredstate.api.NodeId to()`

### `public final java.lang.String toString()`
