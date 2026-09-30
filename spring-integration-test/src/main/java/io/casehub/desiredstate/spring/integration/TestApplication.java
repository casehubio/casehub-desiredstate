package io.casehub.desiredstate.spring.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication(
    excludeName =
        "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration")
@EntityScan(
    basePackages = {"io.casehub.desiredstate.persistence.jpa", "io.casehub.platform"})
public class TestApplication {

  public static void main(String[] args) {
    SpringApplication.run(TestApplication.class, args);
  }
}
