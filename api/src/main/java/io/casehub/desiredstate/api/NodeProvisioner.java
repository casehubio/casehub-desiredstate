package io.casehub.desiredstate.api;

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
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}

