package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.model.PluginCbrDef;
import io.casehub.desiredstate.plugin.model.PluginFaultPolicyDef;
import io.casehub.desiredstate.plugin.model.PluginRasDef;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;

import io.casehub.yaml.step.catalog.ResolvedStep;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record PluginDescriptor(
        String type,
        String version,
        Duration resyncInterval,
        Map<String, String> authCredentialRefs,
        PluginSpecSchema specSchema,
        List<ResolvedStep> actualStateSteps,
        List<ResolvedStep> provisionSteps,
        List<ResolvedStep> deprovisionSteps,
        List<ResolvedStep> suspendSteps,
        List<ResolvedStep> resumeSteps,
        List<PluginFaultPolicyDef> faultPolicies,
        PluginCbrDef cbr,
        PluginRasDef ras
) {}
