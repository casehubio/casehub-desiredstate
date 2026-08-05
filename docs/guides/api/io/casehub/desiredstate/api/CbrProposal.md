# io.casehub.desiredstate.api.CbrProposal

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

## Fields

### `affectedNodeIds` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `path` (`io.casehub.desiredstate.api.CbrPath`)

### `sourceId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `affectedNodeIds` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `path` (`io.casehub.desiredstate.api.CbrPath`)

### `sourceId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Constructors

### `public CbrProposal(java.lang.String sourceId, io.casehub.desiredstate.api.CbrPath path, java.util.Set<io.casehub.desiredstate.api.NodeId> affectedNodeIds, java.time.Instant timestamp)`

#### Parameters

- `sourceId` (`java.lang.String`)
- `path` (`io.casehub.desiredstate.api.CbrPath`)
- `affectedNodeIds` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)
- `timestamp` (`java.time.Instant`)

## Methods

### `public java.util.Set<io.casehub.desiredstate.api.NodeId> affectedNodeIds()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.desiredstate.api.CbrPath path()`

### `public java.lang.String sourceId()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`
