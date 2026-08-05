# io.casehub.desiredstate.api.HumanGating

**Package:** `io.casehub.desiredstate.api`

**Kind:** `enum`

## Enum Constants

### `ALL` (`io.casehub.desiredstate.api.HumanGating`)

### `DEPROVISION_ONLY` (`io.casehub.desiredstate.api.HumanGating`)

### `NONE` (`io.casehub.desiredstate.api.HumanGating`)

### `PROVISION_ONLY` (`io.casehub.desiredstate.api.HumanGating`)

## Constructors

### `private HumanGating()`

## Methods

### `public boolean any()`

### `public io.casehub.desiredstate.api.HumanGating merge(io.casehub.desiredstate.api.HumanGating other)`

#### Parameters

- `other` (`io.casehub.desiredstate.api.HumanGating`)

### `public boolean requiresHuman(io.casehub.desiredstate.api.StepAction action)`

#### Parameters

- `action` (`io.casehub.desiredstate.api.StepAction`)

### `public static io.casehub.desiredstate.api.HumanGating valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.desiredstate.api.HumanGating[] values()`
