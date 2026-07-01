package io.casehub.desiredstate.api;

import java.time.Duration;
import java.util.Set;

/**
 * SPI for provisioning and deprovisioning nodes in the desired-state graph.
 *
 * <p><b>Re-entry protocol for PendingApproval:</b>
 * <ul>
 *   <li>{@code provision()} may return {@code PendingApproval(nodeId, planReference)}
 *       to request human approval before proceeding.</li>
 *   <li>If approval is granted, {@code provision()} will be called again with
 *       {@code context.approval()} non-null, carrying the {@link PlanApproval}
 *       (planReference, approvedBy, approvedAt).</li>
 *   <li>Provisioners should check {@code context.hasApproval()} and behave accordingly:
 *       proceed with the approved plan, or return a new {@code PendingApproval} if
 *       the plan is stale.</li>
 *   <li>The {@code planReference} returned in {@code PendingApproval} is opaque to the
 *       runtime — it is round-tripped back to the provisioner unchanged.</li>
 * </ul>
 *
 * <p>Same protocol applies to {@code deprovision()} via
 * {@link DeprovisionContext#approval()}.
 */
public interface NodeProvisioner {
    /**
     * Declares the node types this provisioner handles. The runtime routes
     * provision/deprovision calls by NodeType via {@link NodeProvisionerRouter}.
     *
     * @return non-empty set of handled types; overlapping types across provisioners
     *         cause construction-time failure
     */
    Set<NodeType> handledTypes();

    /**
     * Declares the resync interval for periodic reconciliation of handled types.
     * Must be >= 1 second; validated at router construction time.
     *
     * @return resync interval (default: 5 minutes)
     */
    default Duration resyncInterval() { return Duration.ofMinutes(5); }

    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}

