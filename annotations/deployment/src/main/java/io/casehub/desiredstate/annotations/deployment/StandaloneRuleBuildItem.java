package io.casehub.desiredstate.annotations.deployment;

import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.quarkus.builder.item.MultiBuildItem;

import java.util.List;

public final class StandaloneRuleBuildItem extends MultiBuildItem {
    private final String[] graphPatterns;
    private final List<GraphRuleDescriptor> rules;

    public StandaloneRuleBuildItem(String[] graphPatterns, List<GraphRuleDescriptor> rules) {
        this.graphPatterns = graphPatterns;
        this.rules = rules;
    }

    public String[] graphPatterns() { return graphPatterns; }
    public List<GraphRuleDescriptor> rules() { return rules; }
}
