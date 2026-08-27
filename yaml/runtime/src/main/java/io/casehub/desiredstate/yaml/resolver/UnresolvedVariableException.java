package io.casehub.desiredstate.yaml.resolver;

public class UnresolvedVariableException extends RuntimeException {

    private final String variableName;
    private final String nodeContext;

    public UnresolvedVariableException(String variableName, String nodeContext, String detail) {
        super("Unresolved variable '" + variableName + "' in node '" + nodeContext + "'. " + detail);
        this.variableName = variableName;
        this.nodeContext = nodeContext;
    }

    public String variableName() { return variableName; }

    public String nodeContext() { return nodeContext; }
}
