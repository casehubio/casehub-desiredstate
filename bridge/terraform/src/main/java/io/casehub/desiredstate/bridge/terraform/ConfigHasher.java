package io.casehub.desiredstate.bridge.terraform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

final class ConfigHasher {

    static final String UNCONFIGURED = "unconfigured";

    private ConfigHasher() {}

    static String hash(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return UNCONFIGURED;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(credentials).forEach((k, v) -> {
                digest.update(k.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(v.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
