package io.casehub.desiredstate.plugin.testing.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMockInfrastructureTest {

    private HttpMockInfrastructure infra;

    @BeforeEach
    void setUp() {
        infra = new HttpMockInfrastructure();
        infra.start();
    }

    @AfterEach
    void tearDown() {
        if (infra != null) infra.stop();
    }

    @Test
    void variableBindingsContainWiremockUrl() {
        var bindings = infra.variableBindings();
        assertThat(bindings).containsKey("wiremock.url");
        assertThat((String) bindings.get("wiremock.url")).startsWith("http://localhost:");
    }

    @Test
    void configureCreatesStub() throws Exception {
        infra.configure(List.of(Map.of(
            "request", Map.of("method", "GET", "path", "/api/test"),
            "response", Map.of("status", 200, "body", Map.of("ok", true))
        )));

        var client = HttpClient.newHttpClient();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(infra.variableBindings().get("wiremock.url") + "/api/test"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"ok\"");
    }

    @Test
    void resetPreservesSetupStubs() throws Exception {
        var setupStub = Map.<String, Object>of(
            "request", Map.of("method", "GET", "path", "/setup"),
            "response", Map.of("status", 200, "body", Map.of("setup", true))
        );
        infra.configure(List.of(setupStub));

        infra.resetBetweenTests(List.of(setupStub));

        var client = HttpClient.newHttpClient();
        var setupResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(infra.variableBindings().get("wiremock.url") + "/setup"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(setupResp.statusCode()).isEqualTo(200);
    }

    @Test
    void scenarioSupport() throws Exception {
        infra.configure(List.of(
            Map.of(
                "scenario", "create-flow",
                "when-state", "Started",
                "set-state", "Created",
                "request", Map.of("method", "POST", "path", "/resources"),
                "response", Map.of("status", 201, "body", Map.of("id", "r1"))
            ),
            Map.of(
                "scenario", "create-flow",
                "when-state", "Created",
                "request", Map.of("method", "GET", "path", "/resources/r1"),
                "response", Map.of("status", 200, "body", Map.of("status", "active"))
            )
        ));

        var client = HttpClient.newHttpClient();
        var base = (String) infra.variableBindings().get("wiremock.url");

        var createResp = client.send(
            HttpRequest.newBuilder().uri(URI.create(base + "/resources"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(createResp.statusCode()).isEqualTo(201);

        var getResp = client.send(
            HttpRequest.newBuilder().uri(URI.create(base + "/resources/r1"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(getResp.statusCode()).isEqualTo(200);
        assertThat(getResp.body()).contains("active");
    }
}
