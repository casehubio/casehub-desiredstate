package io.casehub.desiredstate.bridge.terraform;

import io.casehub.platform.api.credentials.CredentialResolver;

import java.util.HashMap;
import java.util.Map;

class EnvCredentialResolver implements CredentialResolver {

    @Override
    public Map<String, String> resolve(String credentialRef) {
        if (credentialRef == null) return Map.of();
        return switch (credentialRef) {
            case "aws" -> resolveAws();
            case "gcp" -> resolveGcp();
            default -> Map.of();
        };
    }

    private Map<String, String> resolveAws() {
        Map<String, String> creds = new HashMap<>();
        putIfSet(creds, "access_key", System.getenv("AWS_ACCESS_KEY_ID"));
        putIfSet(creds, "secret_key", System.getenv("AWS_SECRET_ACCESS_KEY"));
        putIfSet(creds, "region", System.getenv("AWS_DEFAULT_REGION"));
        return Map.copyOf(creds);
    }

    private Map<String, String> resolveGcp() {
        Map<String, String> creds = new HashMap<>();
        putIfSet(creds, "credentials", System.getenv("GOOGLE_CREDENTIALS"));
        putIfSet(creds, "project", System.getenv("GOOGLE_PROJECT"));
        return Map.copyOf(creds);
    }

    private void putIfSet(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
