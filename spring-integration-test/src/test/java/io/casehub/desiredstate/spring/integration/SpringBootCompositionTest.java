package io.casehub.desiredstate.spring.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringBootCompositionTest {

  @Autowired private ApplicationContext context;

  @Autowired private ObjectMapper objectMapper;

  @LocalServerPort private int port;

  @Test
  void contextLoads() {}

  @Test
  void platformFallbackBeansRegistered() {
    assertThat(context.getBean(io.casehub.platform.api.identity.GroupMembershipProvider.class))
        .isNotNull();
    assertThat(context.getBean(io.casehub.platform.api.preferences.PreferenceSchemaRegistry.class))
        .isNotNull();
  }

  @Test
  void persistenceAutoConfigOnClasspath() {
    assertThat(io.casehub.desiredstate.api.FaultCountStore.class).isNotNull();
    assertThat(io.casehub.desiredstate.api.ReconciliationStateStore.class).isNotNull();
  }

  @Test
  void healthCheckReturnsUp() throws Exception {
    var client = HttpClient.newHttpClient();
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/actuator/health"))
            .GET()
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("UP");
  }

  @Test
  void jacksonBridgeActive() {
    assertThat(objectMapper).isInstanceOf(ObjectMapper.class);
    assertThat(objectMapper.getClass().getName()).startsWith("com.fasterxml.jackson");
  }
}
