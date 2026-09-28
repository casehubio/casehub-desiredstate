package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.yaml.core.foreach.ForEachAdapter;
import io.casehub.yaml.core.foreach.ForEachDirective;
import io.casehub.yaml.core.resolver.VariableResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class YamlNodeForEachAdapter implements ForEachAdapter<YamlNode> {

    private final Map<String, Map<String, String>> moduleScopes;
    private final Map<String, VariableResolver> resolversByStampedId = new LinkedHashMap<>();

    public YamlNodeForEachAdapter() {
        this(Map.of());
    }

    public YamlNodeForEachAdapter(Map<String, Map<String, String>> moduleScopes) {
        this.moduleScopes = moduleScopes;
    }

    @Override
    public YamlNode stamp(YamlNode template, String stampedId, VariableResolver scopedResolver) {
        VariableResolver nodeResolver = withModuleScope(scopedResolver, stampedId);
        resolversByStampedId.put(stampedId, nodeResolver);
        Map<String, Object> resolvedSpec = nodeResolver.resolveMap(template.spec(), stampedId);
        List<Object> resolvedDeps = template.dependsOn().stream()
                .map(dep -> resolveDep(dep, nodeResolver, stampedId))
                .toList();
        return new YamlNode(template.type(), resolvedSpec, resolvedDeps,
                template.humanGating(), null, null,
                template.provision(), template.deprovision());
    }

    @Override
    public ForEachDirective getForEach(YamlNode element) {return ForEachDirective.parse(element.forEach());}

    @Override
    public String getCondition(YamlNode element) {
        return element.when();
    }

    @Override
    public List<Reference> getReferences(YamlNode element) {
        return element.dependsOn().stream()
                .map(dep -> new Reference(
                        YamlNode.dependencyNodeId(dep),
                        YamlNode.isDependencyOptional(dep)))
                .toList();
    }

    @Override
    public YamlNode withReferences(YamlNode element, List<Reference> rewritten) {
        List<Object> newDeps = rewritten.stream()
                .<Object>map(ref -> ref.optional()
                        ? Map.of("node", ref.targetId(), "optional", true)
                        : ref.targetId())
                .toList();
        return new YamlNode(element.type(), element.spec(), newDeps,
                element.humanGating(), element.when(), element.forEach(),
                element.provision(), element.deprovision());
    }

    public VariableResolver resolverFor(String stampedId) {
        return resolversByStampedId.get(stampedId);
    }

    private VariableResolver withModuleScope(VariableResolver base, String nodeId) {
        if (moduleScopes.isEmpty()) return base;
        int dot = nodeId.indexOf('.');
        if (dot < 0) return base;
        String prefix = nodeId.substring(0, dot);
        Map<String, String> scope = moduleScopes.get(prefix);
        return scope != null ? base.withChainedScope("var", scope::get) : base;
    }

    private static Object resolveDep(Object dep, VariableResolver resolver, String context) {
        String rawId = YamlNode.dependencyNodeId(dep);
        if (!rawId.contains("${")) return dep;
        String resolvedId = resolver.resolveString(rawId, context);
        boolean optional = YamlNode.isDependencyOptional(dep);
        return optional ? Map.of("node", resolvedId, "optional", true) : resolvedId;
    }

}
