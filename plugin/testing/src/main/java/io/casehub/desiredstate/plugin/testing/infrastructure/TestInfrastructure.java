package io.casehub.desiredstate.plugin.testing.infrastructure;

import java.util.List;
import java.util.Map;

public interface TestInfrastructure {
    void start();
    void configure(List<Map<String, Object>> expectations);
    void resetBetweenTests(List<Map<String, Object>> setupStubs);
    Map<String, Object> variableBindings();
    void stop();
}
