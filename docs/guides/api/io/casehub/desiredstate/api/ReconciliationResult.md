# io.casehub.desiredstate.api.ReconciliationResult

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

## Fields

### `drifted` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `faulted` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `mutations` (`java.util.List<io.casehub.desiredstate.api.GraphMutation>`)

### `resolved` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

## Record Components

### `drifted` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `faulted` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `mutations` (`java.util.List<io.casehub.desiredstate.api.GraphMutation>`)

### `resolved` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

## Constructors

### `public ReconciliationResult(java.util.Set<io.casehub.desiredstate.api.NodeId> resolved, java.util.Set<io.casehub.desiredstate.api.NodeId> drifted, java.util.Set<io.casehub.desiredstate.api.NodeId> faulted, java.util.List<io.casehub.desiredstate.api.GraphMutation> mutations)`

#### Parameters

- `resolved` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)
- `drifted` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)
- `faulted` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)
- `mutations` (`java.util.List<io.casehub.desiredstate.api.GraphMutation>`)

## Methods

### `public java.util.Set<io.casehub.desiredstate.api.NodeId> drifted()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Set<io.casehub.desiredstate.api.NodeId> faulted()`

### `public final int hashCode()`

### `public java.util.List<io.casehub.desiredstate.api.GraphMutation> mutations()`

### `public java.util.Set<io.casehub.desiredstate.api.NodeId> resolved()`

### `public final java.lang.String toString()`
