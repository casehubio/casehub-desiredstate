package io.casehub.desiredstate.plugin.testing.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShellSandboxInfrastructureTest {

    private ShellSandboxInfrastructure infra;

    @BeforeEach
    void setUp() {
        infra = new ShellSandboxInfrastructure();
        infra.start();
    }

    @AfterEach
    void tearDown() {
        if (infra != null) infra.stop();
    }

    @Test
    void variableBindingsContainSandboxDir() {
        var bindings = infra.variableBindings();
        assertThat(bindings).containsKey("sandbox.dir");
        assertThat(Path.of((String) bindings.get("sandbox.dir"))).exists().isDirectory();
    }

    @Test
    void resetPreservesFiles() throws IOException {
        var sandboxDir = Path.of((String) infra.variableBindings().get("sandbox.dir"));
        var testFile = sandboxDir.resolve("test.txt");
        Files.writeString(testFile, "hello");

        infra.resetBetweenTests(List.of());

        assertThat(testFile).exists().hasContent("hello");
    }

    @Test
    void stopDeletesSandboxDir() {
        var sandboxDir = Path.of((String) infra.variableBindings().get("sandbox.dir"));
        assertThat(sandboxDir).exists();

        infra.stop();

        assertThat(sandboxDir).doesNotExist();
        infra = null;
    }
}
