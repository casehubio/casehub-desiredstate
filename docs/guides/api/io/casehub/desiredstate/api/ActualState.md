# io.casehub.desiredstate.api.ActualState

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

Immutable snapshot of the actual state: the observed status of each known node.
Produced by an `io.casehub.desiredstate.api.spi.ActualStateAdapter`.

## Fields

### `statuses` (`java.util.Map<io.casehub.desiredstate.api.NodeId,io.casehub.desiredstate.api.NodeStatus>`)

## Record Components

### `statuses` (`java.util.Map<io.casehub.desiredstate.api.NodeId,io.casehub.desiredstate.api.NodeStatus>`)

## Constructors

### `public ActualState(java.util.Map<io.casehub.desiredstate.api.NodeId,io.casehub.desiredstate.api.NodeStatus> statuses)`

#### Parameters

- `statuses` (`java.util.Map<io.casehub.desiredstate.api.NodeId,io.casehub.desiredstate.api.NodeStatus>`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.Optional<io.casehub.desiredstate.api.NodeStatus> statusOf(io.casehub.desiredstate.api.NodeId nodeId)`

Returns the status of a node, or empty if the node was not observed.

#### Parameters

- `nodeId` (`io.casehub.desiredstate.api.NodeId`)

### `public java.util.Map<io.casehub.desiredstate.api.NodeId,io.casehub.desiredstate.api.NodeStatus> statuses()`

### `public final java.lang.String toString()`
