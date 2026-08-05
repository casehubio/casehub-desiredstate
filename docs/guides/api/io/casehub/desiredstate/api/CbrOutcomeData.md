# io.casehub.desiredstate.api.CbrOutcomeData

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

## Fields

### `failureCount` (`int`)

### `nodeOutcomes` (`java.util.Map<java.lang.String,java.lang.String>`)

### `observedAt` (`java.time.Instant`)

### `path` (`io.casehub.desiredstate.api.CbrPath`)

### `proposedAt` (`java.time.Instant`)

### `resolvedCount` (`int`)

### `sourceId` (`java.lang.String`)

### `successCount` (`int`)

### `successRate` (`double`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `failureCount` (`int`)

### `nodeOutcomes` (`java.util.Map<java.lang.String,java.lang.String>`)

### `observedAt` (`java.time.Instant`)

### `path` (`io.casehub.desiredstate.api.CbrPath`)

### `proposedAt` (`java.time.Instant`)

### `resolvedCount` (`int`)

### `sourceId` (`java.lang.String`)

### `successCount` (`int`)

### `successRate` (`double`)

### `tenancyId` (`java.lang.String`)

## Constructors

### `public CbrOutcomeData(java.lang.String tenancyId, java.lang.String sourceId, io.casehub.desiredstate.api.CbrPath path, java.util.Map<java.lang.String,java.lang.String> nodeOutcomes, int successCount, int failureCount, int resolvedCount, double successRate, java.time.Instant proposedAt, java.time.Instant observedAt)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `sourceId` (`java.lang.String`)
- `path` (`io.casehub.desiredstate.api.CbrPath`)
- `nodeOutcomes` (`java.util.Map<java.lang.String,java.lang.String>`)
- `successCount` (`int`)
- `failureCount` (`int`)
- `resolvedCount` (`int`)
- `successRate` (`double`)
- `proposedAt` (`java.time.Instant`)
- `observedAt` (`java.time.Instant`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public int failureCount()`

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.String> nodeOutcomes()`

### `public java.time.Instant observedAt()`

### `public io.casehub.desiredstate.api.CbrPath path()`

### `public java.time.Instant proposedAt()`

### `public int resolvedCount()`

### `public java.lang.String sourceId()`

### `public int successCount()`

### `public double successRate()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`
