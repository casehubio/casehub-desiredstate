package io.casehub.desiredstate.plugin.model;

import java.util.List;

public record PluginProvisionerDef(
    List<PluginStepDef> provisionSteps,
    List<PluginStepDef> deprovisionSteps
) {}
