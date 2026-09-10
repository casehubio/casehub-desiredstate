package io.casehub.desiredstate.plugin.runtime;

public class StepExecutionException extends RuntimeException {

    private final String pluginType;
    private final int stepIndex;
    private final String primitiveName;

    public StepExecutionException(String message) {
        this(message, null, -1, null);
    }

    public StepExecutionException(String message, String pluginType, int stepIndex,
                                  String primitiveName) {
        super(message);
        this.pluginType = pluginType;
        this.stepIndex = stepIndex;
        this.primitiveName = primitiveName;
    }

    public StepExecutionException(String message, Throwable cause, String pluginType,
                                  int stepIndex, String primitiveName) {
        super(message, cause);
        this.pluginType = pluginType;
        this.stepIndex = stepIndex;
        this.primitiveName = primitiveName;
    }

    public String pluginType() { return pluginType; }
    public int stepIndex() { return stepIndex; }
    public String primitiveName() { return primitiveName; }
}
