package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.annotations.runtime.PatternKind;
import io.casehub.desiredstate.annotations.runtime.PatternParameterDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class YamlInvariantConverter {

    private YamlInvariantConverter() {}

    public static ResolvedInvariant.DeclarativeInvariant toDeclarativeInvariant(
            String name, YamlInvariant yamlInvariant) {

        List<PatternParameterDescriptor> patterns = new ArrayList<>();
        List<String> bindingNamesList = new ArrayList<>();

        for (Map.Entry<String, YamlPattern> entry : yamlInvariant.match().entrySet()) {
            YamlPattern p = entry.getValue();
            int min = p.minCount() != null ? p.minCount() : PatternParameterDescriptor.UNSPECIFIED;
            int max = p.maxCount() != null ? p.maxCount() : PatternParameterDescriptor.UNSPECIFIED;
            patterns.add(new PatternParameterDescriptor(
                    PatternKind.MATCH, p.type(),
                    p.of() != null ? p.of() : "",
                    p.direction(), min, max));
            bindingNamesList.add(entry.getKey());
        }

        addPatterns(yamlInvariant.directDep(), PatternKind.DIRECT_DEP, patterns, bindingNamesList);
        addPatterns(yamlInvariant.reaches(), PatternKind.REACHES, patterns, bindingNamesList);
        addPatterns(yamlInvariant.notExists(), PatternKind.NOT_EXISTS, patterns, bindingNamesList);

        return new ResolvedInvariant.DeclarativeInvariant(
                name, patterns, bindingNamesList.toArray(String[]::new),
                yamlInvariant.message());
    }

    private static void addPatterns(Map<String, YamlPattern> section, PatternKind kind,
            List<PatternParameterDescriptor> patterns, List<String> bindingNames) {
        for (Map.Entry<String, YamlPattern> entry : section.entrySet()) {
            YamlPattern p = entry.getValue();
            int min = p.minCount() != null ? p.minCount() : PatternParameterDescriptor.UNSPECIFIED;
            int max = p.maxCount() != null ? p.maxCount() : PatternParameterDescriptor.UNSPECIFIED;
            patterns.add(new PatternParameterDescriptor(
                    kind, p.type(),
                    p.of() != null ? p.of() : "",
                    p.direction(), min, max));
            bindingNames.add(entry.getKey());
        }
    }
}
