package io.casehub.desiredstate.plugin.model;

import io.casehub.yaml.step.catalog.ResolvedStep;

import java.util.List;

public record PluginModel(
        PluginHeader header,
        PluginSpecSchema spec,
        List<ResolvedStep> actualStateSteps,
        PluginProvisionerDef provisioner,
        List<PluginFaultPolicyDef> faultPolicies,
        PluginCbrDef cbr,
        PluginRasDef ras
) {}
