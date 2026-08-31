package io.casehub.desiredstate.yaml.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.yaml.model.YamlModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class JarModuleDiscoveryTest {

    private static final String PREFIX = "META-INF/desiredstate/modules/";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    void discoversModulesInsideJar(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("test-modules.jar");
        String moduleYaml = """
                module:
                  name: jar-module
                nodes:
                  monitor:
                    type: monitor
                    spec:
                      name: default
                """;

        createJar(jarPath, Map.of(PREFIX + "jar-module.yaml", moduleYaml));

        URL jarUrl = new URL("jar:" + jarPath.toUri() + "!/" + PREFIX);
        Map<String, YamlModule> modules = new HashMap<>();
        YamlDesiredStateProcessor.discoverJarModules(jarUrl, PREFIX, YAML_MAPPER, modules, new HashSet<>());

        assertThat(modules).hasSize(1);
        assertThat(modules).containsKey("jar-module");
        assertThat(modules.get("jar-module").nodes()).containsKey("monitor");
    }

    @Test
    void skipsNonYamlFilesInJar(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("test-modules.jar");
        String moduleYaml = """
                module:
                  name: real-module
                nodes: {}
                """;

        createJar(jarPath, Map.of(
                PREFIX + "real-module.yaml", moduleYaml,
                PREFIX + "readme.txt", "not a module"));

        URL jarUrl = new URL("jar:" + jarPath.toUri() + "!/" + PREFIX);
        Map<String, YamlModule> modules = new HashMap<>();
        YamlDesiredStateProcessor.discoverJarModules(jarUrl, PREFIX, YAML_MAPPER, modules, new HashSet<>());

        assertThat(modules).hasSize(1);
        assertThat(modules).containsKey("real-module");
    }

    @Test
    void deduplicatesAcrossFileAndJar(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("test-modules.jar");
        String moduleYaml = """
                module:
                  name: dup-module
                nodes: {}
                """;

        createJar(jarPath, Map.of(PREFIX + "dup-module.yaml", moduleYaml));

        URL jarUrl = new URL("jar:" + jarPath.toUri() + "!/" + PREFIX);
        HashSet<String> seen = new HashSet<>();
        seen.add("dup-module.yaml");
        Map<String, YamlModule> modules = new HashMap<>();
        YamlDesiredStateProcessor.discoverJarModules(jarUrl, PREFIX, YAML_MAPPER, modules, seen);

        assertThat(modules).isEmpty();
    }

    private static void createJar(Path jarPath, Map<String, String> entries) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
    }
}
