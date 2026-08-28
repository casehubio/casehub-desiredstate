package io.casehub.desiredstate.yaml.model;

import java.util.List;
import java.util.Map;

public record YamlModuleFile(
        YamlModuleHeader module,
        Map<String, YamlNode> nodes,
        Map<String, YamlRule> rules,
        Map<String, YamlInvariant> invariants,
        List<YamlImport> imports) {

    public YamlModuleFile {
        if (nodes == null) {nodes = Map.of();}
        if (rules == null) {rules = Map.of();}
        if (invariants == null) {invariants = Map.of();}
        if (imports == null) {imports = List.of();}
    }

    public boolean hasNestedImports() {
        return !imports.isEmpty();
    }

    public YamlModule toModule() {
        return new YamlModule(module.name(), module.parameters(),
                nodes, rules, invariants);
    }

    public record YamlModuleHeader(String name,
            Map<String, YamlModuleParameter> parameters) {
        public YamlModuleHeader {
            if (parameters == null) {parameters = Map.of();}
        }
    }
}
