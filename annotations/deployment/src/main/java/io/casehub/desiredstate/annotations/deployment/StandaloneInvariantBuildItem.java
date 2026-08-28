package io.casehub.desiredstate.annotations.deployment;

import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.quarkus.builder.item.MultiBuildItem;

import java.util.List;

public final class StandaloneInvariantBuildItem extends MultiBuildItem {
    private final String[] graphPatterns;
    private final List<GraphInvariantDescriptor> invariants;

    public StandaloneInvariantBuildItem(String[] graphPatterns,
                                        List<GraphInvariantDescriptor> invariants) {
        this.graphPatterns = graphPatterns;
        this.invariants = invariants;
    }

    public String[] graphPatterns() { return graphPatterns; }
    public List<GraphInvariantDescriptor> invariants() { return invariants; }
}
