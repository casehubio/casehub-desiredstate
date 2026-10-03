package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanNodeHandler;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.StepOutcome;

public class NoOpHumanNodeHandler implements HumanNodeHandler {
    @Override
    public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
        return new StepOutcome.Skipped("requires human — no HumanNodeHandler configured");
    }

    @Override
    public StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
        return new StepOutcome.Skipped("requires human — no HumanNodeHandler configured");
    }

}
