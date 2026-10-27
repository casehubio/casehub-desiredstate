package io.casehub.desiredstate.bridge.terraform;

public final class PlatformDetector {

    private PlatformDetector() {}

    public static String os() {
        String name = System.getProperty("os.name").toLowerCase();
        if (name.contains("mac") || name.contains("darwin")) return "darwin";
        if (name.contains("linux")) return "linux";
        if (name.contains("windows")) return "windows";
        throw new UnsupportedOperationException("Unsupported OS: " + name);
    }

    public static String arch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) return "amd64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        throw new UnsupportedOperationException("Unsupported architecture: " + arch);
    }
}
