package io.casehub.desiredstate.bridge.terraform;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RegistryPackageResponse(
    @JsonProperty("download_url") String downloadUrl,
    String filename,
    String shasum,
    @JsonProperty("shasums_url") String shasumsUrl,
    @JsonProperty("shasums_signature_url") String shasumsSignatureUrl,
    @JsonProperty("signing_keys") SigningKeys signingKeys
) {
    public record SigningKeys(
        @JsonProperty("gpg_public_keys") List<GpgPublicKey> gpgPublicKeys
    ) {}

    public record GpgPublicKey(
        @JsonProperty("key_id") String keyId,
        @JsonProperty("ascii_armor") String asciiArmor,
        String source,
        @JsonProperty("source_url") String sourceUrl
    ) {}
}
