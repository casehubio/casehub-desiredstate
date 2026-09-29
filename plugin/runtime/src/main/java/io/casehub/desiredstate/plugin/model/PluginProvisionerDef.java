package io.casehub.desiredstate.plugin.model;

import io.casehub.yaml.step.catalog.ResolvedStep;

import java.util.List;

public record PluginProvisionerDef(
        List<ResolvedStep> provisionSteps,
        List<ResolvedStep> deprovisionSteps
) {}
