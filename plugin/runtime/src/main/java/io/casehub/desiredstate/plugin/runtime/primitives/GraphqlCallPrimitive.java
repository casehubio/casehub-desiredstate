package io.casehub.desiredstate.plugin.runtime.primitives;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.plugin.api.Action;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.ServiceRegistry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class GraphqlCallPrimitive implements Action {

    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public GraphqlCallPrimitive(ObjectMapper mapper, HttpClient httpClient) {
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    public GraphqlCallPrimitive(ObjectMapper mapper) {
        this(mapper, HttpClient.newHttpClient());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result execute(Map<String, Object> parameters, ServiceRegistry services) {
        String url = (String) parameters.get("url");
        if (url == null || url.isBlank()) {
            return Result.failed("graphql-call requires 'url' parameter");
        }

        String query = (String) parameters.get("query");
        if (query == null || query.isBlank()) {
            return Result.failed("graphql-call requires 'query' parameter");
        }

        Map<String, Object> variables = (Map<String, Object>) parameters.get("variables");
        Map<String, String> headers = (Map<String, String>) parameters.get("headers");
        Number timeoutSeconds = (Number) parameters.get("timeout");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (variables != null && !variables.isEmpty()) {
            body.put("variables", variables);
        }

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return Result.failed("graphql-call: failed to serialize request — " + e.getMessage());
        }

        var reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (headers != null) {
            headers.forEach(reqBuilder::header);
        }
        if (timeoutSeconds != null) {
            reqBuilder.timeout(Duration.ofSeconds(timeoutSeconds.longValue()));
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", response.statusCode());
            output.put("body", response.body());
            return Result.of(output);
        } catch (IOException e) {
            return Result.failed("graphql-call: request failed — " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("graphql-call: request interrupted");
        }
    }
}
