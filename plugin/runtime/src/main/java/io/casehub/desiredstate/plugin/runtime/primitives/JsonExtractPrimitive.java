package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.desiredstate.plugin.api.PluginInterpolator;
import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.api.StepPrimitive;
import io.casehub.desiredstate.plugin.api.StepResult;
import io.casehub.desiredstate.plugin.runtime.StepExecutionException;

import java.util.Map;

public class JsonExtractPrimitive implements StepPrimitive {

    private final PluginInterpolator interpolator = new PluginInterpolator();

    @Override
    public String name() {
        return "json-extract";
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepParameters params, StepContext context) {
        String inputRef = params.getString("input");
        String path = params.getString("path");
        if (inputRef == null || path == null) {
            throw new StepExecutionException(
                "json-extract: 'input' and 'path' parameters are required");
        }

        String resolvedInput = interpolator.interpolate(inputRef, context);
        String resolvedPath = interpolator.interpolate(path, context);

        Object source = context.resolve(resolvedInput);
        if (source == null) {
            return StepResult.of(Map.of("value", "null"));
        }

        Object value = traversePath(source, resolvedPath);
        if (value == null) {
            return StepResult.of(Map.of("value", "null"));
        }

        if (value instanceof Map<?, ?> m) {
            return StepResult.of((Map<String, Object>) m);
        }
        return StepResult.of(Map.of("value", value));
    }

    private Object traversePath(Object root, String dotPath) {
        String[] segments = dotPath.split("\\.");
        Object current = root;
        for (String segment : segments) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }
}
