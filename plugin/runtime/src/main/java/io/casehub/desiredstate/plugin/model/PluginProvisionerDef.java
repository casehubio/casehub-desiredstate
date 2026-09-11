package io.casehub.desiredstate.plugin.model;

import io.casehub.yaml.step.StepDef;

import java.util.List;

public record PluginProvisionerDef(
    List<StepDef> provisionSteps,
    List<StepDef> deprovisionSteps
) {}
