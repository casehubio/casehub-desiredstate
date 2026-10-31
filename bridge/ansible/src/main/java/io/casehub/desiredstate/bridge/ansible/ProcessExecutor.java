package io.casehub.desiredstate.bridge.ansible;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ProcessExecutor {
    AnsibleProcessResult execute(Path workDir, List<String> command,
                                  Map<String, String> env, int timeoutSecs);
}
