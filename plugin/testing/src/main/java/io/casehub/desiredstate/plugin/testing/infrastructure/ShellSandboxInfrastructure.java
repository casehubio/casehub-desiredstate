package io.casehub.desiredstate.plugin.testing.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

public class ShellSandboxInfrastructure implements TestInfrastructure {

    private Path sandboxDir;

    @Override
    public void start() {
        try {
            sandboxDir = Files.createTempDirectory("plugin-test-");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create sandbox directory", e);
        }
    }

    @Override
    public void configure(List<Map<String, Object>> expectations) {}

    @Override
    public void resetBetweenTests(List<Map<String, Object>> setupStubs) {}

    @Override
    public Map<String, Object> variableBindings() {
        return Map.of("sandbox.dir", sandboxDir.toString());
    }

    @Override
    public void stop() {
        if (sandboxDir != null && Files.exists(sandboxDir)) {
            try {
                Files.walkFileTree(sandboxDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to clean up sandbox directory", e);
            }
        }
    }
}
