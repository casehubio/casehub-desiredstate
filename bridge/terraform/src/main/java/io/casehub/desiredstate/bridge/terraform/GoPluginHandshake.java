package io.casehub.desiredstate.bridge.terraform;

public record GoPluginHandshake(
    int coreProtocol,
    int appProtocol,
    String networkType,
    String address,
    String protocol
) {
    static final String MAGIC_COOKIE_KEY = "TF_PLUGIN_MAGIC_COOKIE";
    static final String MAGIC_COOKIE_VALUE = "d602bf8f470bc67ca7faa0386276bbdd4330efaf76d1a219cb4d6991ca9872b2";

    public static GoPluginHandshake parse(String line) {
        if (line == null || line.isEmpty()) {
            throw new IllegalArgumentException("Empty handshake line");
        }
        String[] parts = line.trim().split("\\|");
        if (parts.length < 5) {
            throw new IllegalArgumentException(
                "Malformed go-plugin handshake: expected 5+ pipe-separated fields, got " + parts.length);
        }
        String protocol = parts[4];
        if (!"grpc".equals(protocol)) {
            throw new IllegalArgumentException(
                "Unsupported protocol: " + protocol + " — only grpc is supported (netrpc is legacy)");
        }
        return new GoPluginHandshake(
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            parts[2],
            parts[3],
            protocol
        );
    }
}
