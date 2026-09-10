package io.casehub.desiredstate.plugin.model;

public class PluginParseException extends RuntimeException {

    public PluginParseException(String message) {
        super(message);
    }

    public PluginParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
