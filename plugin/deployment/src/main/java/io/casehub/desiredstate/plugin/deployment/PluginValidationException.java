package io.casehub.desiredstate.plugin.deployment;

public class PluginValidationException
        extends io.casehub.desiredstate.plugin.runtime.PluginValidationException {

    public PluginValidationException(String pluginType, String message) {
        super(pluginType, message);
    }
}
