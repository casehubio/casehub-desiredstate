package io.casehub.desiredstate.api;

public interface HumanNodeHandler {
    StepOutcome onProvision(DesiredNode node, ProvisionContext context);

    default StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
        return new StepOutcome.Skipped("no HumanNodeHandler configured for deprovision");
    }
}
