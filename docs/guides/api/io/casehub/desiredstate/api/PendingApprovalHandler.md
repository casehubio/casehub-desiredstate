# io.casehub.desiredstate.api.PendingApprovalHandler

**Package:** `io.casehub.desiredstate.api`

**Kind:** `interface`

Handles approval lifecycle for nodes whose provisioner returns PendingApproval.
Wraps the provisioner — called before (check) and after (recordPending) provisioner.provision().

<p>Contrast with `HumanNodeHandler` which replaces the provisioner entirely.
PendingApprovalHandler is for automated nodes that need human approval before the machine provisions.

## Methods

### `public abstract void acknowledgeRejection(io.casehub.desiredstate.api.DesiredNode node, io.casehub.desiredstate.api.StepAction action, java.lang.String tenancyId)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.DesiredNode`)
- `action` (`io.casehub.desiredstate.api.StepAction`)
- `tenancyId` (`java.lang.String`)

### `public abstract io.casehub.desiredstate.api.ApprovalCheckResult check(io.casehub.desiredstate.api.DesiredNode node, io.casehub.desiredstate.api.StepAction action, java.lang.String tenancyId)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.DesiredNode`)
- `action` (`io.casehub.desiredstate.api.StepAction`)
- `tenancyId` (`java.lang.String`)

### `public abstract io.casehub.desiredstate.api.StepOutcome recordPending(io.casehub.desiredstate.api.DesiredNode node, io.casehub.desiredstate.api.StepAction action, java.lang.String tenancyId, java.lang.String planReference)`

#### Parameters

- `node` (`io.casehub.desiredstate.api.DesiredNode`)
- `action` (`io.casehub.desiredstate.api.StepAction`)
- `tenancyId` (`java.lang.String`)
- `planReference` (`java.lang.String`)
