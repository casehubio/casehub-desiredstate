package io.casehub.desiredstate.api;

import java.time.Duration;
import java.util.Set;

/**
 * Router for NodeProvisioner operations.
 *
 * Dispatches provision/deprovision requests to the appropriate NodeProvisioner
 * instance based on node type. Aggregates resync intervals and handled types
 * across all registered provisioners.
 *
 * Consumed by SimpleTransitionExecutor and DesiredStateDispatch.
 */
public interface NodeProvisionerRouter {
    /**
     * Provision a desired node.
     *
     * @param node the node to provision
     * @param context provision context (tenancy, graph, optional approval)
     * @return provision result (success, failure, or pending approval)
     */
    ProvisionResult provision(DesiredNode node, ProvisionContext context);

    /**
     * Deprovision a desired node.
     *
     * @param node the node to deprovision
     * @param context deprovision context (tenancy, graph, optional approval)
     * @return deprovision result (success, failure, or pending approval)
     */
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);

    /**
     * Get the resync interval for a node type.
     *
     * @param type the node type
     * @return the provisioner's declared resync interval, or a default of 5 minutes if the type is not handled
     */
    Duration resyncIntervalFor(NodeType type);

    /**
     * Get all node types handled by registered provisioners.
     *
     * @return set of handled node types
     */
    Set<NodeType> allHandledTypes();
}
