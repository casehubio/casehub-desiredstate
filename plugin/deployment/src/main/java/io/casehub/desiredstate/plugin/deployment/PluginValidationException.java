package io.casehub.desiredstate.plugin.deployment;

public class PluginValidationException extends RuntimeException {

    private final String pluginType;

    public PluginValidationException(String pluginType, String message) {
        super("Plugin '" + pluginType + "': " + message);
        this.pluginType = pluginType;
    }

    public String pluginType() { return pluginType; }
}
