package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.yaml.plugin.api.Action;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.ServiceRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;

public class WriterAction implements Action {

    private final String actionName;
    private final Writer writer;

    public WriterAction(String actionName, Writer writer) {
        this.actionName = actionName;
        this.writer = writer;
    }

    @Override
    public Result execute(Map<String, Object> parameters, ServiceRegistry services) {
        try {
            writer.write("[" + actionName + "] " + parameters + System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Result.of(Map.of());
    }

    public String actionName() {
        return actionName;
    }
}
