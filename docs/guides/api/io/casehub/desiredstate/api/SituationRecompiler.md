# io.casehub.desiredstate.api.SituationRecompiler

**Package:** `io.casehub.desiredstate.api`

**Kind:** `interface`

SPI for situation-driven graph recompilation.

<p>Triggered by RAS (Runtime Anomaly Service) when a situation reaches a threshold
or pattern that requires desired-state recalculation — for example, persistent drift
in a specific zone triggering fallback provisioning, or cascading failures triggering
circuit-breaker topology changes.

<p>Participates in `SituationRecompilerEngine` chain-of-responsibility.
Multiple recompilers may be registered; the engine tries each in `.priority()` order
(ascending) until one returns a non-empty result.

## Methods

### `public default int priority()`

### `public abstract java.util.Optional<io.casehub.desiredstate.api.CompilationResult> recompile(java.lang.String tenancyId, io.casehub.desiredstate.api.DesiredStateGraph current, io.casehub.desiredstate.api.ActualState actual, ActiveSituation situation, io.casehub.desiredstate.api.DesiredStateGraphFactory factory)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `current` (`io.casehub.desiredstate.api.DesiredStateGraph`)
- `actual` (`io.casehub.desiredstate.api.ActualState`)
- `situation` (`ActiveSituation`)
- `factory` (`io.casehub.desiredstate.api.DesiredStateGraphFactory`)
