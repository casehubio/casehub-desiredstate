package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.model.PluginCbrDef;
import io.casehub.desiredstate.plugin.model.PluginFaultPolicyDef;
import io.casehub.desiredstate.plugin.model.PluginRasDef;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.yaml.step.StepDef;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record PluginDescriptor(
    String type,
    int version,
    Duration resyncInterval,
    Map<String, String> authCredentialRefs,
    PluginSpecSchema specSchema,
    List<StepDef> actualStateSteps,
    List<StepDef> provisionSteps,
    List<StepDef> deprovisionSteps,
    List<PluginFaultPolicyDef> faultPolicies,
    PluginCbrDef cbr,
    PluginRasDef ras
) {}
