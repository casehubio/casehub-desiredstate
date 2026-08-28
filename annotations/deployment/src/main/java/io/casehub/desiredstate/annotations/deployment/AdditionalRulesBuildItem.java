package io.casehub.desiredstate.annotations.deployment;

import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.quarkus.builder.item.MultiBuildItem;

import java.util.List;

public final class AdditionalRulesBuildItem extends MultiBuildItem {
    private final String namespace;
    private final String name;
    private final List<GraphRuleDescriptor> rules;
    private final List<GraphInvariantDescriptor> invariants;

    public AdditionalRulesBuildItem(String namespace, String name,
                                    List<GraphRuleDescriptor> rules,
                                    List<GraphInvariantDescriptor> invariants) {
        this.namespace = namespace;
        this.name = name;
        this.rules = rules;
        this.invariants = invariants;
    }

    public String namespace() { return namespace; }
    public String name() { return name; }
    public List<GraphRuleDescriptor> rules() { return rules; }
    public List<GraphInvariantDescriptor> invariants() { return invariants; }
    public String qualifiedName() { return namespace + ":" + name; }
}
