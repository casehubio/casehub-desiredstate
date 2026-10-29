package io.casehub.desiredstate.bridge.terraform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ProviderRegistryClient {

    private static final Logger log = Logger.getLogger(ProviderRegistryClient.class.getName());
    private static final String REGISTRY_BASE = "https://registry.terraform.io";
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    private final Path cacheDir;
    private final HttpClient httpClient;
    private final Set<String> trustedKeyIds;

    public ProviderRegistryClient() {
        this(defaultCacheDir(), Set.of());
    }

    public ProviderRegistryClient(Path cacheDir) {
        this(cacheDir, Set.of());
    }

    public ProviderRegistryClient(Path cacheDir, Set<String> trustedKeyIds) {
        this.cacheDir = cacheDir;
        this.trustedKeyIds = trustedKeyIds;
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public ProviderBinary resolve(ProviderConfig config) {
        if (config.binaryPath() != null && !config.binaryPath().isEmpty()) {
            return new ProviderBinary(Path.of(config.binaryPath()), config.version());
        }

        String os = PlatformDetector.os();
        String arch = PlatformDetector.arch();

        Path cached = cachePath(config.namespace(), config.type(), config.version(), os, arch);
        Path binaryDir = cached;
        if (Files.exists(binaryDir)) {
            Path binary = findBinary(binaryDir);
            if (binary != null) {
                return new ProviderBinary(binary, config.version());
            }
        }

        return download(config, os, arch);
    }

    private ProviderBinary download(ProviderConfig config, String os, String arch) {
        try {
            String url = REGISTRY_BASE + "/v1/providers/"
                + config.namespace() + "/" + config.type()
                + "/" + config.version() + "/download/" + os + "/" + arch;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Registry returned " + response.statusCode()
                    + " for " + config.namespace() + "/" + config.type()
                    + " " + config.version() + " (" + os + "/" + arch + ")");
            }

            RegistryPackageResponse meta = JSON.readValue(response.body(),
                RegistryPackageResponse.class);

            String expectedSha;
            if (meta.shasumsUrl() != null && meta.shasumsSignatureUrl() != null
                    && meta.signingKeys() != null
                    && meta.signingKeys().gpgPublicKeys() != null
                    && !meta.signingKeys().gpgPublicKeys().isEmpty()) {

                byte[] sha256sumsContent = downloadBytes(meta.shasumsUrl());
                byte[] signatureBytes = downloadBytes(meta.shasumsSignatureUrl());

                var result = GpgSignatureVerifier.verify(
                    sha256sumsContent, signatureBytes,
                    meta.signingKeys().gpgPublicKeys(), trustedKeyIds);

                if (result instanceof GpgSignatureVerifier.VerificationResult.Failed failed) {
                    throw new SecurityException(
                        "GPG signature verification failed: " + failed.reason());
                }

                expectedSha = extractChecksum(new String(sha256sumsContent), meta.filename());
            } else {
                log.log(Level.WARNING,
                    "Registry response missing GPG signing data, falling back to direct shasum");
                expectedSha = meta.shasum();
            }

            Path tempZip = Files.createTempFile("terraform-provider-", ".zip");
            try {
                downloadFile(meta.downloadUrl(), tempZip);
                verifySha256(tempZip, expectedSha);

                Path targetDir = cachePath(config.namespace(), config.type(),
                    config.version(), os, arch);
                Files.createDirectories(targetDir);
                extractZip(tempZip, targetDir);

                Path binary = findBinary(targetDir);
                if (binary == null) {
                    throw new RuntimeException("No provider binary found in downloaded archive");
                }
                binary.toFile().setExecutable(true);

                return new ProviderBinary(binary, config.version());
            } finally {
                Files.deleteIfExists(tempZip);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to download provider "
                + config.namespace() + "/" + config.type() + " " + config.version(), e);
        }
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(DOWNLOAD_TIMEOUT)
            .GET()
            .build();

        HttpResponse<InputStream> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Download failed with status " + response.statusCode());
        }

        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private byte[] downloadBytes(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(DOWNLOAD_TIMEOUT)
            .GET()
            .build();

        HttpResponse<byte[]> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Download failed with status " + response.statusCode()
                + " for " + url);
        }
        return response.body();
    }

    String extractChecksum(String sha256sumsContent, String filename) {
        for (String line : sha256sumsContent.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int separatorIdx = trimmed.indexOf("  ");
            if (separatorIdx < 0) continue;
            String checksum = trimmed.substring(0, separatorIdx);
            String file = trimmed.substring(separatorIdx + 2);
            if (file.equals(filename)) {
                return checksum;
            }
        }
        throw new SecurityException(
            "Filename " + filename + " not found in SHA256SUMS file");
    }

    void verifySha256(Path file, String expectedSha) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            String actualSha = HexFormat.of().formatHex(digest.digest());
            if (!actualSha.equalsIgnoreCase(expectedSha)) {
                throw new SecurityException(
                    "SHA256 mismatch: expected " + expectedSha + " but got " + actualSha);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("SHA256 verification failed", e);
        }
    }

    void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path target = targetDir.resolve(entry.getName());
                if (!target.normalize().startsWith(targetDir.normalize())) {
                    throw new SecurityException("Zip entry outside target dir: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    Path findBinary(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith("terraform-provider-"))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path cachePath(String namespace, String type, String version,
                           String os, String arch) {
        return cacheDir.resolve(namespace).resolve(type)
            .resolve(version).resolve(os + "_" + arch);
    }

    private static Path defaultCacheDir() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".casehub", "providers");
    }
}
