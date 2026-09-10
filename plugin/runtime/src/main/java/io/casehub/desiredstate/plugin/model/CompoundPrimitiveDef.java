package io.casehub.desiredstate.plugin.model;

import java.util.List;
import java.util.Map;

public record CompoundPrimitiveDef(
    String name,
    Map<String, PluginFieldDef> parameters,
    List<PluginStepDef> steps,
    String resultBinding
) {}
