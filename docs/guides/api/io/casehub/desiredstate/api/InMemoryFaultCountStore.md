# io.casehub.desiredstate.api.InMemoryFaultCountStore

**Package:** `io.casehub.desiredstate.api`

**Kind:** `class`

## Fields

### `counts` (`java.util.concurrent.ConcurrentHashMap<io.casehub.desiredstate.api.InMemoryFaultCountStore.Key,java.lang.Integer>`)

## Constructors

### `public InMemoryFaultCountStore()`

## Methods

### `public void evict(java.lang.String namespace, java.lang.String tenancyId, java.util.Set<io.casehub.desiredstate.api.NodeId> retainedNodes)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `retainedNodes` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `public void evictAcrossNamespaces(java.lang.String tenancyId, java.util.Set<io.casehub.desiredstate.api.NodeId> retainedNodes)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `retainedNodes` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `public int getCount(java.lang.String namespace, java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)

### `public int incrementAndGet(java.lang.String namespace, java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)

### `public void remove(java.lang.String namespace, java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)

### `public void reset(java.lang.String namespace, java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)
