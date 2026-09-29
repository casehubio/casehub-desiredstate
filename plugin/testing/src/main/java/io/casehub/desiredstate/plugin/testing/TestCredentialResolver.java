package io.casehub.desiredstate.plugin.testing;

import io.casehub.platform.api.credentials.CredentialResolver;

import java.util.HashMap;
import java.util.Map;

public class TestCredentialResolver implements CredentialResolver {

    private final Map<String, Map<String, String>> credentialsByRef;

    @SuppressWarnings("unchecked")
    public TestCredentialResolver(Map<String, String> authCredentialRefs,
                                   Map<String, Object> authVariables) {
        this.credentialsByRef = new HashMap<>();
        for (Map.Entry<String, String> entry : authCredentialRefs.entrySet()) {
            String stanzaName = entry.getKey();
            String credentialRef = entry.getValue();
            Object stanzaValues = authVariables.get(stanzaName);
            if (stanzaValues instanceof Map<?, ?> m) {
                Map<String, String> stringMap = new HashMap<>();
                m.forEach((k, v) -> stringMap.put(String.valueOf(k), String.valueOf(v)));
                credentialsByRef.put(credentialRef, Map.copyOf(stringMap));
            }
        }
    }

    @Override
    public Map<String, String> resolve(String credentialRef) {
        return credentialsByRef.getOrDefault(credentialRef, Map.of());
    }
}
