package io.casehub.desiredstate.bridge.terraform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryPackageResponseTest {

    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void deserializeFullResponse() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/gpg/registry-response.json")) {
            var response = JSON.readValue(is, RegistryPackageResponse.class);

            assertThat(response.downloadUrl()).isEqualTo(
                "https://releases.hashicorp.com/terraform-provider-random/3.6.0/terraform-provider-random_3.6.0_darwin_arm64.zip");
            assertThat(response.filename()).isEqualTo("terraform-provider-random_3.6.0_darwin_arm64.zip");
            assertThat(response.shasum()).isEqualTo("5f9c7aa76b7c34d722fc9123208e26b22d60440cb47150dd04733b9b94f4541a");
            assertThat(response.shasumsUrl()).endsWith("SHA256SUMS");
            assertThat(response.shasumsSignatureUrl()).endsWith("SHA256SUMS.sig");
        }
    }

    @Test
    void deserializeSigningKeys() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/gpg/registry-response.json")) {
            var response = JSON.readValue(is, RegistryPackageResponse.class);

            assertThat(response.signingKeys()).isNotNull();
            assertThat(response.signingKeys().gpgPublicKeys()).hasSize(1);

            var key = response.signingKeys().gpgPublicKeys().get(0);
            assertThat(key.keyId()).isEqualTo("51852D87348FFC4C");
            assertThat(key.asciiArmor()).startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----");
            assertThat(key.source()).isEqualTo("HashiCorp");
        }
    }

    @Test
    void deserializeMinimalResponse() throws Exception {
        String json = """
            {
              "download_url": "https://example.com/provider.zip",
              "shasum": "abc123",
              "filename": "provider.zip"
            }
            """;
        var response = JSON.readValue(json, RegistryPackageResponse.class);

        assertThat(response.downloadUrl()).isEqualTo("https://example.com/provider.zip");
        assertThat(response.shasum()).isEqualTo("abc123");
        assertThat(response.shasumsUrl()).isNull();
        assertThat(response.shasumsSignatureUrl()).isNull();
        assertThat(response.signingKeys()).isNull();
    }
}
