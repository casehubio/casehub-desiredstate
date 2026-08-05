# io.casehub.desiredstate.api.TransitionPlan

**Package:** `io.casehub.desiredstate.api`

**Kind:** `record`

## Fields

### `additions` (`java.util.List<io.casehub.desiredstate.api.OrderedStep>`)

### `after` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `before` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `removals` (`java.util.List<io.casehub.desiredstate.api.OrderedStep>`)

## Record Components

### `additions` (`java.util.List<io.casehub.desiredstate.api.OrderedStep>`)

### `after` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `before` (`io.casehub.desiredstate.api.DesiredStateGraph`)

### `removals` (`java.util.List<io.casehub.desiredstate.api.OrderedStep>`)

## Constructors

### `public TransitionPlan(java.util.List<io.casehub.desiredstate.api.OrderedStep> removals, java.util.List<io.casehub.desiredstate.api.OrderedStep> additions, io.casehub.desiredstate.api.DesiredStateGraph before, io.casehub.desiredstate.api.DesiredStateGraph after)`

#### Parameters

- `removals` (`java.util.List<io.casehub.desiredstate.api.OrderedStep>`)
- `additions` (`java.util.List<io.casehub.desiredstate.api.OrderedStep>`)
- `before` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `after` (`io.casehub.desiredstate.api.DesiredStateGraph`)

## Methods

### `public java.util.List<io.casehub.desiredstate.api.OrderedStep> additions()`

### `public io.casehub.desiredstate.api.DesiredStateGraph after()`

### `public io.casehub.desiredstate.api.DesiredStateGraph before()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public boolean isEmpty()`

### `public java.util.List<io.casehub.desiredstate.api.OrderedStep> removals()`

### `public final java.lang.String toString()`
