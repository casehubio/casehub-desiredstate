# io.casehub.desiredstate.api.GlobalReconciliationListener

**Package:** `io.casehub.desiredstate.api`

**Kind:** `interface`

## Methods

### `public abstract void onReconciliationCycleCompleted(java.lang.String tenancyId, io.casehub.desiredstate.api.DesiredStateGraph desired, io.casehub.desiredstate.api.ActualState actual)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `desired` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `actual` (`io.casehub.desiredstate.api.ActualState`)

### `public default void onTenantStopped(java.lang.String tenancyId)`

#### Parameters

- `tenancyId` (`java.lang.String`)
