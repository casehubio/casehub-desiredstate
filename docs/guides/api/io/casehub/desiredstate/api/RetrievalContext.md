# io.casehub.desiredstate.api.RetrievalContext

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

## Fields

### `actualState` (`io.casehub.desiredstate.api.ActualState`)

### `currentGraph` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `faultEvent` (`io.casehub.desiredstate.api.FaultEvent`)

### `situation` (`ActiveSituation`)

## Record Components

### `actualState` (`io.casehub.desiredstate.api.ActualState`)

### `currentGraph` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `faultEvent` (`io.casehub.desiredstate.api.FaultEvent`)

### `situation` (`ActiveSituation`)

## Constructors

### `public RetrievalContext(io.casehub.desiredstate.api.DesiredStateGraph currentGraph, io.casehub.desiredstate.api.ActualState actualState, io.casehub.desiredstate.api.FaultEvent faultEvent, ActiveSituation situation)`

#### Parameters

- `currentGraph` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `actualState` (`io.casehub.desiredstate.api.ActualState`)
- `faultEvent` (`io.casehub.desiredstate.api.FaultEvent`)
- `situation` (`ActiveSituation`)

## Methods

### `public io.casehub.desiredstate.api.ActualState actualState()`

### `public io.casehub.desiredstate.api.DesiredStateGraph currentGraph()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public io.casehub.desiredstate.api.FaultEvent faultEvent()`

### `public static io.casehub.desiredstate.api.RetrievalContext forFault(io.casehub.desiredstate.api.DesiredStateGraph graph, io.casehub.desiredstate.api.ActualState actual, io.casehub.desiredstate.api.FaultEvent event)`

#### Parameters

- `graph` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `actual` (`io.casehub.desiredstate.api.ActualState`)
- `event` (`io.casehub.desiredstate.api.FaultEvent`)

### `public static io.casehub.desiredstate.api.RetrievalContext forSituation(io.casehub.desiredstate.api.DesiredStateGraph graph, io.casehub.desiredstate.api.ActualState actual, ActiveSituation situation)`

#### Parameters

- `graph` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `actual` (`io.casehub.desiredstate.api.ActualState`)
- `situation` (`ActiveSituation`)

### `public final int hashCode()`

### `public ActiveSituation situation()`

### `public final java.lang.String toString()`
