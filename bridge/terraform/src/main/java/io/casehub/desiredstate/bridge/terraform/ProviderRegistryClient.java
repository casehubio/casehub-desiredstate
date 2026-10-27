package io.casehub.desiredstate.bridge.terraform;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ProviderRegistryClient {

    private static final String REGISTRY_BASE = "https://registry.terraform.io";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    private final Path cacheDir;
    private final HttpClient httpClient;

    public ProviderRegistryClient() {
        this(defaultCacheDir());
    }

    public ProviderRegistryClient(Path cacheDir) {
        this.cacheDir = cacheDir;
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

            JsonNode meta = JSON.readTree(response.body());
            String downloadUrl = meta.get("download_url").asText();
            String expectedSha = meta.get("shasum").asText();

            Path tempZip = Files.createTempFile("terraform-provider-", ".zip");
            try {
                downloadFile(downloadUrl, tempZip);
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

    void verifySha256(Path file, String expectedSha) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(fileBytes);
            String actualSha = HexFormat.of().formatHex(hash);
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
