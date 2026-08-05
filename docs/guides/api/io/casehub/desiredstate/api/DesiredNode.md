# io.casehub.desiredstate.api.DesiredNode

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

## Fields

### `humanGating` (`io.casehub.desiredstate.api.HumanGating`)

### `id` (`io.casehub.desiredstate.api.NodeId`)

### `spec` (`io.casehub.desiredstate.api.NodeSpec`)

### `type` (`io.casehub.desiredstate.api.NodeType`)

## Record Components

### `humanGating` (`io.casehub.desiredstate.api.HumanGating`)

### `id` (`io.casehub.desiredstate.api.NodeId`)

### `spec` (`io.casehub.desiredstate.api.NodeSpec`)

### `type` (`io.casehub.desiredstate.api.NodeType`)

## Constructors

### `public DesiredNode(io.casehub.desiredstate.api.NodeId id, io.casehub.desiredstate.api.NodeType type, io.casehub.desiredstate.api.NodeSpec spec, io.casehub.desiredstate.api.HumanGating humanGating)`

#### Parameters

- `id` (`io.casehub.desiredstate.api.NodeId`)
- `type` (`io.casehub.desiredstate.api.NodeType`)
- `spec` (`io.casehub.desiredstate.api.NodeSpec`)
- `humanGating` (`io.casehub.desiredstate.api.HumanGating`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.desiredstate.api.HumanGating humanGating()`

### `public io.casehub.desiredstate.api.NodeId id()`

### `public boolean requiresHuman()`

### `public boolean requiresHuman(io.casehub.desiredstate.api.StepAction action)`

#### Parameters

- `action` (`io.casehub.desiredstate.api.StepAction`)

### `public io.casehub.desiredstate.api.NodeSpec spec()`

### `public final java.lang.String toString()`

### `public io.casehub.desiredstate.api.NodeType type()`
