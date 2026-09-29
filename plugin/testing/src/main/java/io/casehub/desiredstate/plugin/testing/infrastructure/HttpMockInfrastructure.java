package io.casehub.desiredstate.plugin.testing.infrastructure;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

public class HttpMockInfrastructure implements TestInfrastructure {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMockServer server;

    @Override
    public void start() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @Override
    public void configure(List<Map<String, Object>> expectations) {
        for (Map<String, Object> expectation : expectations) {
            server.stubFor(buildMapping(expectation));
        }
    }

    @Override
    public void resetBetweenTests(List<Map<String, Object>> setupStubs) {
        server.resetAll();
        for (Map<String, Object> stub : setupStubs) {
            server.stubFor(buildMapping(stub));
        }
    }

    @Override
    public Map<String, Object> variableBindings() {
        return Map.of("wiremock.url", "http://localhost:" + server.port());
    }

    @Override
    public void stop() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private MappingBuilder buildMapping(Map<String, Object> expectation) {
        Map<String, Object> req = (Map<String, Object>) expectation.get("request");
        Map<String, Object> resp = (Map<String, Object>) expectation.get("response");

        String method = (String) req.get("method");
        String path = (String) req.get("path");

        MappingBuilder builder = switch (method.toUpperCase()) {
            case "GET" -> get(urlPathEqualTo(path));
            case "POST" -> post(urlPathEqualTo(path));
            case "PUT" -> put(urlPathEqualTo(path));
            case "DELETE" -> delete(urlPathEqualTo(path));
            case "PATCH" -> patch(urlPathEqualTo(path));
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };

        ResponseDefinitionBuilder response = aResponse()
                .withStatus(((Number) resp.get("status")).intValue());

        Object body = resp.get("body");
        if (body != null) {
            try {
                response.withHeader("Content-Type", "application/json")
                        .withBody(JSON.writeValueAsString(body));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize response body", e);
            }
        }

        String scenario = (String) expectation.get("scenario");
        if (scenario != null) {
            var scenarioBuilder = builder.inScenario(scenario);
            String whenState = (String) expectation.getOrDefault("when-state", "Started");
            scenarioBuilder.whenScenarioStateIs(whenState);
            String setState = (String) expectation.get("set-state");
            if (setState != null) {
                scenarioBuilder.willSetStateTo(setState);
            }
        }

        builder.willReturn(response);
        return builder;
    }
}
