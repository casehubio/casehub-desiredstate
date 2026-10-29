package io.casehub.desiredstate.bridge.terraform;

import java.util.Arrays;
import java.util.Base64;

public record GoPluginHandshake(
    int coreProtocol,
    int appProtocol,
    String networkType,
    String address,
    String protocol,
    byte[] serverCertDer
) {
    static final String MAGIC_COOKIE_KEY = "TF_PLUGIN_MAGIC_COOKIE";
    static final String MAGIC_COOKIE_VALUE = "d602bf8f470bc67ca7faa0386276bbdd4330efaf76d1a219cb4d6991ca9872b2";

    private static final int MIN_CERT_LENGTH = 50;

    public GoPluginHandshake(int coreProtocol, int appProtocol,
                              String networkType, String address, String protocol) {
        this(coreProtocol, appProtocol, networkType, address, protocol, null);
    }

    public boolean hasTls() {
        return serverCertDer != null && serverCertDer.length > 0;
    }

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

        byte[] serverCert = null;
        if (parts.length > 5 && parts[5].length() > MIN_CERT_LENGTH) {
            serverCert = decodeServerCert(parts[5]);
        }

        return new GoPluginHandshake(
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            parts[2],
            parts[3],
            protocol,
            serverCert
        );
    }

    private static byte[] decodeServerCert(String encoded) {
        try {
            int pad = (4 - encoded.length() % 4) % 4;
            String padded = encoded + "=".repeat(pad);
            return Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GoPluginHandshake that)) return false;
        return coreProtocol == that.coreProtocol
            && appProtocol == that.appProtocol
            && networkType.equals(that.networkType)
            && address.equals(that.address)
            && protocol.equals(that.protocol)
            && Arrays.equals(serverCertDer, that.serverCertDer);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(coreProtocol);
        result = 31 * result + Integer.hashCode(appProtocol);
        result = 31 * result + networkType.hashCode();
        result = 31 * result + address.hashCode();
        result = 31 * result + protocol.hashCode();
        result = 31 * result + Arrays.hashCode(serverCertDer);
        return result;
    }
}
