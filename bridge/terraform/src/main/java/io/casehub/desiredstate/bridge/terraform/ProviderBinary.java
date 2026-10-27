package io.casehub.desiredstate.bridge.terraform;

import java.nio.file.Path;

public record ProviderBinary(Path binaryPath, String version) {}
