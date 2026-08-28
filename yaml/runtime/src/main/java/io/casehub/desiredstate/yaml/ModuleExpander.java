package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.yaml.model.YamlImport;
import io.casehub.desiredstate.yaml.model.YamlModule;
import io.casehub.desiredstate.yaml.model.YamlModuleParameter;
import io.casehub.desiredstate.yaml.model.YamlNode;

import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModuleExpander {

    public record ExpandedGraph(
            Map<String, YamlNode> expandedNodes,
            Map<String, Map<String, String>> moduleScopes,
            Map<String, YamlRule> promotedRules,
            Map<String, YamlInvariant> promotedInvariants) {}

    private ModuleExpander() {}

    public static ExpandedGraph expand(
            List<YamlImport> imports,
            Map<String, YamlModule> availableModules,
            Map<String, YamlNode> existingNodes) {

        Map<String, YamlNode> allNodes = new LinkedHashMap<>(existingNodes);
        Map<String, Map<String, String>> moduleScopes = new LinkedHashMap<>();
        Map<String, YamlRule> promotedRules = new LinkedHashMap<>();
        Map<String, YamlInvariant> promotedInvariants = new LinkedHashMap<>();

        for (YamlImport imp : imports) {
            YamlModule module = availableModules.get(imp.module());
            String alias = imp.as();

            Map<String, String> paramScope = new LinkedHashMap<>();
            for (Map.Entry<String, YamlModuleParameter> paramDef :
                    module.parameters().entrySet()) {
                String paramName = paramDef.getKey();
                String value = imp.parameters().get(paramName);
                if (value == null && paramDef.getValue().defaultValue() != null) {
                    value = paramDef.getValue().defaultValue();
                }
                if (value != null) {
                    paramScope.put(paramName, value);
                }
            }
            moduleScopes.put(alias, paramScope);

            for (Map.Entry<String, YamlNode> nodeEntry : module.nodes().entrySet()) {
                String nodeId = nodeEntry.getKey();
                YamlNode node = nodeEntry.getValue();
                String aliasedId = alias + "." + nodeId;

                List<Object> rewrittenDeps = new ArrayList<>();
                for (Object dep : node.dependsOn()) {
                    String depId = YamlNode.dependencyNodeId(dep);
                    boolean isOptional = YamlNode.isDependencyOptional(dep);

                    if (module.nodes().containsKey(depId)) {
                        String aliasedDepId = alias + "." + depId;
                        rewrittenDeps.add(isOptional
                                ? Map.of("node", aliasedDepId, "optional", true)
                                : aliasedDepId);
                    } else {
                        rewrittenDeps.add(isOptional
                                ? Map.of("node", depId, "optional", true)
                                : dep);
                    }
                }

                String when = node.when();
                if (imp.when() != null) {
                    when = imp.when();
                }

                allNodes.put(aliasedId, new YamlNode(
                        node.type(), node.spec(), rewrittenDeps,
                        node.humanGating(), when, node.forEach()));
            }

            for (Map.Entry<String, YamlRule> ruleEntry : module.rules().entrySet()) {
                promotedRules.put(alias + "." + ruleEntry.getKey(), ruleEntry.getValue());
            }
            for (Map.Entry<String, YamlInvariant> invEntry : module.invariants().entrySet()) {
                promotedInvariants.put(alias + "." + invEntry.getKey(), invEntry.getValue());
            }
        }

        return new ExpandedGraph(allNodes, moduleScopes, promotedRules, promotedInvariants);
    }
}
