package io.casehub.desiredstate.plugin.model;

import io.casehub.yaml.step.StepDef;

import java.util.List;

public record PluginModel(
    PluginHeader header,
    PluginSpecSchema spec,
    List<StepDef> actualStateSteps,
    PluginProvisionerDef provisioner,
    List<PluginFaultPolicyDef> faultPolicies,
    PluginCbrDef cbr,
    PluginRasDef ras
) {}
