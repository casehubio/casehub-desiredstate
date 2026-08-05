# io.casehub.desiredstate.api.FaultCountStore

**Package:** `io.casehub.desiredstate.api`

**Kind:** `interface`

## Methods

### `public abstract void evict(java.lang.String namespace, java.lang.String tenancyId, java.util.Set<io.casehub.desiredstate.api.NodeId> retainedNodes)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `retainedNodes` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `public abstract void evictAcrossNamespaces(java.lang.String tenancyId, java.util.Set<io.casehub.desiredstate.api.NodeId> retainedNodes)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `retainedNodes` (`java.util.Set<io.casehub.desiredstate.api.NodeId>`)

### `public abstract int getCount(java.lang.String namespace, java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)

### `public abstract int incrementAndGet(java.lang.String namespace, java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)

### `public abstract void remove(java.lang.String namespace, java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)

### `public abstract void reset(java.lang.String namespace, java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `namespace` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)
