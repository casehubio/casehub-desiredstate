package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.NodeId;

import java.util.List;
import java.util.stream.Collectors;

public class GraphRuleCycleException extends RuntimeException {
    private final List<String> ruleNames;
    private final List<NodeId> cyclePath;

    public GraphRuleCycleException(List<String> ruleNames, List<NodeId> cyclePath) {
        super("Graph rules introduced a cycle: "
              + cyclePath.stream().map(NodeId::value).collect(Collectors.joining(" → "))
              + ". Rules: " + String.join(", ", ruleNames));
        this.ruleNames = ruleNames;
        this.cyclePath = cyclePath;
    }

    public List<String> getRuleNames() { return ruleNames; }
    public List<NodeId> getCyclePath() { return cyclePath; }
}
