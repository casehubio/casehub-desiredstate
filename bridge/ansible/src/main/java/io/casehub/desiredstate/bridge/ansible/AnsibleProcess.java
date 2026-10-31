package io.casehub.desiredstate.bridge.ansible;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AnsibleProcess {

    private static final Set<Path> ACTIVE_TEMP_DIRS = Collections.synchronizedSet(new HashSet<>());

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(AnsibleProcess::cleanupAllTempDirs));
    }

    private AnsibleProcess() {}

    public static Path createTempDir() throws IOException {
        Path dir = Files.createTempDirectory("ansible-bridge-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        ACTIVE_TEMP_DIRS.add(dir);
        return dir;
    }

    public static void cleanupTempDir(Path dir) {
        if (dir == null) return;
        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        } catch (IOException ignored) {}
        ACTIVE_TEMP_DIRS.remove(dir);
    }

    public static AnsibleProcessResult execute(Path workDir, List<String> command,
                                                Map<String, String> env, int timeoutSecs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.environment().putAll(env);
            pb.environment().put("ANSIBLE_STDOUT_CALLBACK", "json");
            pb.environment().put("ANSIBLE_NOCOLOR", "true");
            pb.redirectErrorStream(false);

            Process process = pb.start();
            AtomicReference<byte[]> stderrBytes = new AtomicReference<>(new byte[0]);
            Thread drainer = Thread.ofVirtual().start(() -> {
                try { stderrBytes.set(process.getErrorStream().readAllBytes()); } catch (IOException ignored) {}
            });
            String stdout = new String(process.getInputStream().readAllBytes());
            try { drainer.join(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            String stderr = new String(stderrBytes.get());

            boolean finished = process.waitFor(timeoutSecs, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new AnsibleProcessResult(-1, "", "Execution timed out after " + timeoutSecs + "s");
            }

            return new AnsibleProcessResult(process.exitValue(), stdout, stderr);
        } catch (IOException | InterruptedException e) {
            return new AnsibleProcessResult(-1, "", "Process execution failed: " + e.getMessage());
        }
    }

    private static void cleanupAllTempDirs() {
        for (Path dir : new ArrayList<>(ACTIVE_TEMP_DIRS)) {
            cleanupTempDir(dir);
        }
    }
}
