# io.casehub.desiredstate.api.NodeProvisionerRouter

**Package:** `io.casehub.desiredstate.api`

**Kind:** `interface`

Router for NodeProvisioner operations.

Dispatches provision/deprovision requests to the appropriate NodeProvisioner
instance based on node type. Aggregates resync intervals and handled types
across all registered provisioners.

Consumed by SimpleTransitionExecutor and DesiredStateDispatch.

## Methods

### `public abstract java.util.Set<io.casehub.desiredstate.api.NodeType> allHandledTypes()`

Get all node types handled by registered provisioners.

#### Returns

set of handled node types

### `public abstract io.casehub.desiredstate.api.DeprovisionResult deprovision(io.casehub.desiredstate.api.DesiredNode node, io.casehub.desiredstate.api.DeprovisionContext context)`

Deprovision a desired node.

#### Parameters

- `node` (`io.casehub.desiredstate.api.DesiredNode`) — the node to deprovision
- `context` (`io.casehub.desiredstate.api.DeprovisionContext`) — deprovision context (tenancy, graph, optional approval)

#### Returns

deprovision result (success, failure, or pending approval)

### `public abstract io.casehub.desiredstate.api.ProvisionResult provision(io.casehub.desiredstate.api.DesiredNode node, io.casehub.desiredstate.api.ProvisionContext context)`

Provision a desired node.

#### Parameters

- `node` (`io.casehub.desiredstate.api.DesiredNode`) — the node to provision
- `context` (`io.casehub.desiredstate.api.ProvisionContext`) — provision context (tenancy, graph, optional approval)

#### Returns

provision result (success, failure, or pending approval)

### `public abstract java.time.Duration resyncIntervalFor(io.casehub.desiredstate.api.NodeType type)`

Get the resync interval for a node type.

#### Parameters

- `type` (`io.casehub.desiredstate.api.NodeType`) — the node type

#### Returns

the provisioner's declared resync interval, or a default of 5 minutes if the type is not handled
