# io.casehub.desiredstate.api.FaultPolicy

**Package:** `io.casehub.desiredstate.api`

**Kind:** `interface`

## Methods

### `public static io.casehub.desiredstate.api.FaultPolicy addReviewNode(io.casehub.desiredstate.api.NodeType reviewType, io.casehub.desiredstate.api.ReviewSpecFactory specFactory)`

#### Parameters

- `reviewType` (`io.casehub.desiredstate.api.NodeType`)
- `specFactory` (`io.casehub.desiredstate.api.ReviewSpecFactory`)

### `public abstract java.util.List<io.casehub.desiredstate.api.GraphMutation> onFault(java.lang.String tenancyId, io.casehub.desiredstate.api.FaultEvent event, io.casehub.desiredstate.api.DesiredStateGraph current, io.casehub.desiredstate.api.ActualState actual)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `event` (`io.casehub.desiredstate.api.FaultEvent`)
- `current` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `actual` (`io.casehub.desiredstate.api.ActualState`)
