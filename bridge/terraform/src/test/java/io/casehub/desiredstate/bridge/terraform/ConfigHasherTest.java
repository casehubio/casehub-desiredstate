package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigHasherTest {

    @Test
    void sameCredentialsProduceSameHash() {
        var creds1 = Map.of("access_key", "AKIA123", "secret_key", "secret");
        var creds2 = Map.of("secret_key", "secret", "access_key", "AKIA123");
        assertThat(ConfigHasher.hash(creds1)).isEqualTo(ConfigHasher.hash(creds2));
    }

    @Test
    void differentCredentialsProduceDifferentHash() {
        var creds1 = Map.of("access_key", "AKIA123");
        var creds2 = Map.of("access_key", "AKIA456");
        assertThat(ConfigHasher.hash(creds1)).isNotEqualTo(ConfigHasher.hash(creds2));
    }

    @Test
    void emptyMapReturnsSentinel() {
        assertThat(ConfigHasher.hash(Map.of())).isEqualTo(ConfigHasher.UNCONFIGURED);
    }

    @Test
    void nullMapReturnsSentinel() {
        assertThat(ConfigHasher.hash(null)).isEqualTo(ConfigHasher.UNCONFIGURED);
    }

    @Test
    void hashIsDeterministic() {
        var creds = Map.of("key", "value");
        assertThat(ConfigHasher.hash(creds)).isEqualTo(ConfigHasher.hash(creds));
    }

    @Test
    void hashIsHexString() {
        var creds = Map.of("key", "value");
        assertThat(ConfigHasher.hash(creds)).matches("[a-f0-9]{64}");
    }
}
