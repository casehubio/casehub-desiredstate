# io.casehub.desiredstate.api.ThresholdFaultPolicy

**Package:** `io.casehub.desiredstate.api`

**Kind:** `class`

## Fields

### `action` (`io.casehub.desiredstate.api.FaultPolicy`)

### `faultTypes` (`java.util.Set<io.casehub.desiredstate.api.FaultType>`)

### `ignoreTypes` (`java.util.Set<io.casehub.desiredstate.api.NodeType>`)

### `namespace` (`java.lang.String`)

### `nodeTypes` (`java.util.Set<io.casehub.desiredstate.api.NodeType>`)

### `store` (`io.casehub.desiredstate.api.FaultCountStore`)

### `threshold` (`int`)

## Constructors

### `private ThresholdFaultPolicy(io.casehub.desiredstate.api.ThresholdFaultPolicy.Builder builder)`

#### Parameters

- `builder` (`io.casehub.desiredstate.api.ThresholdFaultPolicy.Builder`)

## Methods

### `public static io.casehub.desiredstate.api.ThresholdFaultPolicy.Builder builder()`

### `private static java.lang.String deriveNamespace(java.util.Set<io.casehub.desiredstate.api.FaultType> faultTypes)`

#### Parameters

- `faultTypes` (`java.util.Set<io.casehub.desiredstate.api.FaultType>`)

### `public java.util.List<io.casehub.desiredstate.api.GraphMutation> onFault(java.lang.String tenancyId, io.casehub.desiredstate.api.FaultEvent event, io.casehub.desiredstate.api.DesiredStateGraph current, io.casehub.desiredstate.api.ActualState actual)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `event` (`io.casehub.desiredstate.api.FaultEvent`)
- `current` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `actual` (`io.casehub.desiredstate.api.ActualState`)

### `public void resetCount(java.lang.String tenancyId, io.casehub.desiredstate.api.NodeId nodeId)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `nodeId` (`io.casehub.desiredstate.api.NodeId`)
