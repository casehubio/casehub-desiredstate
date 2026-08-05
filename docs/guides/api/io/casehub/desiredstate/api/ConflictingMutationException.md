# io.casehub.desiredstate.api.ConflictingMutationException

**Package:** `io.casehub.desiredstate.api`

**Kind:** `class`

## Fields

### `mutationA` (`io.casehub.desiredstate.api.GraphMutation`)

### `mutationB` (`io.casehub.desiredstate.api.GraphMutation`)

### `nodeId` (`io.casehub.desiredstate.api.NodeId`)

## Constructors

### `public ConflictingMutationException(io.casehub.desiredstate.api.NodeId nodeId, io.casehub.desiredstate.api.GraphMutation mutationA, io.casehub.desiredstate.api.GraphMutation mutationB)`

#### Parameters

- `nodeId` (`io.casehub.desiredstate.api.NodeId`)
- `mutationA` (`io.casehub.desiredstate.api.GraphMutation`)
- `mutationB` (`io.casehub.desiredstate.api.GraphMutation`)

## Methods

### `public io.casehub.desiredstate.api.GraphMutation getMutationA()`

### `public io.casehub.desiredstate.api.GraphMutation getMutationB()`

### `public io.casehub.desiredstate.api.NodeId getNodeId()`
