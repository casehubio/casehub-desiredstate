package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRegistryClientTest {

    @TempDir Path tempDir;

    @Test
    void binaryPathOverrideSkipsRegistry() throws Exception {
        Path fakeBinary = tempDir.resolve("terraform-provider-test");
        Files.writeString(fakeBinary, "#!/bin/bash\necho test");
        fakeBinary.toFile().setExecutable(true);

        var client = new ProviderRegistryClient(tempDir);
        var config = new ProviderConfig("local", "test", "1.0.0",
            fakeBinary.toString());

        var binary = client.resolve(config);
        assertThat(binary.binaryPath()).isEqualTo(fakeBinary);
        assertThat(binary.version()).isEqualTo("1.0.0");
    }

    @Test
    void cachedBinaryReturnedWithoutDownload() throws Exception {
        Path cacheDir = tempDir.resolve("cache");
        var client = new ProviderRegistryClient(cacheDir);

        String os = PlatformDetector.os();
        String arch = PlatformDetector.arch();
        Path binaryDir = cacheDir.resolve("hashicorp/test/1.0.0/" + os + "_" + arch);
        Files.createDirectories(binaryDir);
        Path binary = binaryDir.resolve("terraform-provider-test_v1.0.0");
        Files.writeString(binary, "#!/bin/bash\necho cached");
        binary.toFile().setExecutable(true);

        var config = new ProviderConfig("hashicorp", "test", "1.0.0", null);
        var result = client.resolve(config);
        assertThat(result.binaryPath()).isEqualTo(binary);
    }

    @Test
    void sha256VerificationPasses() throws Exception {
        byte[] content = "test content".getBytes();
        Path file = tempDir.resolve("test.zip");
        Files.write(file, content);

        String sha = sha256(content);

        var client = new ProviderRegistryClient(tempDir);
        client.verifySha256(file, sha);
    }

    @Test
    void sha256VerificationFailsOnMismatch() throws Exception {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, "test content".getBytes());

        var client = new ProviderRegistryClient(tempDir);
        assertThatThrownBy(() -> client.verifySha256(file, "badsha256"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("SHA256 mismatch");
    }

    @Test
    void zipExtractionPreventsTraversal() throws Exception {
        Path zipFile = tempDir.resolve("evil.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("../../../etc/passwd"));
            zos.write("evil".getBytes());
            zos.closeEntry();
        }

        Path targetDir = tempDir.resolve("extract");
        Files.createDirectories(targetDir);

        var client = new ProviderRegistryClient(tempDir);
        assertThatThrownBy(() -> client.extractZip(zipFile, targetDir))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("outside target dir");
    }

    @Test
    void zipExtractionFindsProviderBinary() throws Exception {
        Path zipFile = tempDir.resolve("provider.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("terraform-provider-test_v1.0.0"));
            zos.write("#!/bin/bash\necho hello".getBytes());
            zos.closeEntry();
        }

        Path targetDir = tempDir.resolve("extract");
        Files.createDirectories(targetDir);

        var client = new ProviderRegistryClient(tempDir);
        client.extractZip(zipFile, targetDir);

        Path binary = client.findBinary(targetDir);
        assertThat(binary).isNotNull();
        assertThat(binary.getFileName().toString()).startsWith("terraform-provider-");
    }

    @Test
    void extractChecksumFromSha256sumsFile() {
        var client = new ProviderRegistryClient(tempDir);

        String sha256sums = """
            abc123def456  terraform-provider-random_3.6.0_linux_amd64.zip
            789012345678  terraform-provider-random_3.6.0_darwin_arm64.zip
            """;

        String checksum = client.extractChecksum(sha256sums,
            "terraform-provider-random_3.6.0_darwin_arm64.zip");
        assertThat(checksum).isEqualTo("789012345678");
    }

    @Test
    void extractChecksumThrowsWhenFilenameNotFound() {
        var client = new ProviderRegistryClient(tempDir);

        String sha256sums = "abc123  other-file.zip\n";

        assertThatThrownBy(() -> client.extractChecksum(sha256sums, "missing.zip"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("missing.zip");
    }

    private String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }
}
