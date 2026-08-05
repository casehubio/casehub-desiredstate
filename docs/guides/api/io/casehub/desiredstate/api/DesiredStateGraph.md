# io.casehub.desiredstate.api.DesiredStateGraph

**Package:** `io.casehub.desiredstate.api`

**Kind:** `interface`

## Methods

### `public abstract io.casehub.desiredstate.api.DesiredStateGraph connect(io.casehub.desiredstate.api.DesiredStateGraph other)`

#### Parameters

- `other` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `public abstract java.util.Set<io.casehub.desiredstate.api.Dependency> dependencies()`

### `public abstract java.util.Set<io.casehub.desiredstate.api.NodeId> dependenciesOf(io.casehub.desiredstate.api.NodeId node)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.NodeId`)

### `public abstract java.util.Set<io.casehub.desiredstate.api.NodeId> dependentsOf(io.casehub.desiredstate.api.NodeId node)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.NodeId`)

### `public default io.casehub.desiredstate.api.DesiredStateGraph filterByTypes(java.util.Set<io.casehub.desiredstate.api.NodeType> types)`

#### Parameters

- `types` (`java.util.Set<io.casehub.desiredstate.api.NodeType>`)

### `public abstract boolean isEmpty()`

### `public abstract java.util.Set<io.casehub.desiredstate.api.NodeId> leaves()`

### `public abstract java.util.Map<io.casehub.desiredstate.api.NodeId,io.casehub.desiredstate.api.DesiredNode> nodes()`

### `public abstract io.casehub.desiredstate.api.DesiredStateGraph overlay(io.casehub.desiredstate.api.DesiredStateGraph other)`

#### Parameters

- `other` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `public abstract java.util.Set<io.casehub.desiredstate.api.NodeId> roots()`

### `public abstract int version()`

### `public abstract io.casehub.desiredstate.api.DesiredStateGraph withDependency(io.casehub.desiredstate.api.Dependency dep)`

#### Parameters

- `dep` (`io.casehub.desiredstate.api.Dependency`)

### `public abstract io.casehub.desiredstate.api.DesiredStateGraph withMutation(io.casehub.desiredstate.api.GraphMutation mutation)`

#### Parameters

- `mutation` (`io.casehub.desiredstate.api.GraphMutation`)

### `public abstract io.casehub.desiredstate.api.DesiredStateGraph withNode(io.casehub.desiredstate.api.DesiredNode node)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.DesiredNode`)

### `public abstract io.casehub.desiredstate.api.DesiredStateGraph withoutDependency(io.casehub.desiredstate.api.Dependency dep)`

#### Parameters

- `dep` (`io.casehub.desiredstate.api.Dependency`)

### `public abstract io.casehub.desiredstate.api.DesiredStateGraph withoutNode(io.casehub.desiredstate.api.NodeId id)`

#### Parameters

- `id` (`io.casehub.desiredstate.api.NodeId`)
