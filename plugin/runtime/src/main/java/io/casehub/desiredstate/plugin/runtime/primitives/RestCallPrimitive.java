package io.casehub.desiredstate.plugin.runtime.primitives;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.plugin.api.PluginInterpolator;
import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.api.StepPrimitive;
import io.casehub.desiredstate.plugin.api.StepResult;
import io.casehub.desiredstate.plugin.runtime.StepExecutionException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class RestCallPrimitive implements StepPrimitive {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final PluginInterpolator interpolator = new PluginInterpolator();

    public RestCallPrimitive() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build());
    }

    public RestCallPrimitive(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "rest-call";
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(StepParameters params, StepContext context) {
        String method = interpolator.interpolate(params.getString("method"), context);
        String url = interpolator.interpolate(params.getString("url"), context);

        if (method == null || url == null) {
            throw new StepExecutionException(
                "rest-call: 'method' and 'url' parameters are required");
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30));

        String authName = params.getString("auth");
        if (authName != null) {
            Map<String, String> creds = context.auth(authName);
            String token = creds.get("token");
            if (token != null) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }
        }

        Map<String, Object> headers = params.getMap("headers");
        if (headers != null) {
            Map<String, Object> interpolatedHeaders =
                interpolator.interpolateMap(headers, context);
            interpolatedHeaders.forEach((k, v) ->
                requestBuilder.header(k, v.toString()));
        }

        Object bodyParam = params.get("body");
        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();
        if (bodyParam != null) {
            try {
                Map<String, Object> bodyMap = bodyParam instanceof Map<?, ?>
                    ? interpolator.interpolateMap((Map<String, Object>) bodyParam, context)
                    : Map.of("value", interpolator.interpolate(bodyParam.toString(), context));
                String jsonBody = JSON.writeValueAsString(bodyMap);
                bodyPublisher = HttpRequest.BodyPublishers.ofString(jsonBody);
            } catch (JsonProcessingException e) {
                throw new StepExecutionException(
                    "rest-call: failed to serialize body", e, null, -1, "rest-call");
            }
        }

        requestBuilder.method(method, bodyPublisher);

        try {
            HttpResponse<String> response =
                httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            Map<String, Object> result = new HashMap<>();
            result.put("status", response.statusCode());
            result.put("headers", response.headers().map());

            String responseBody = response.body();
            if (responseBody != null && !responseBody.isBlank()) {
                try {
                    result.put("body", JSON.readValue(responseBody, Map.class));
                } catch (JsonProcessingException e) {
                    result.put("body", responseBody);
                }
            }

            return StepResult.of(result);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StepExecutionException(
                "rest-call: HTTP request failed: " + e.getMessage(),
                e, null, -1, "rest-call");
        }
    }
}
