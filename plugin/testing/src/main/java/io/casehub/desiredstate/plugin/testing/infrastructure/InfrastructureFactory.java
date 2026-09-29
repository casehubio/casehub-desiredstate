package io.casehub.desiredstate.plugin.testing.infrastructure;

public final class InfrastructureFactory {

    private InfrastructureFactory() {}

    public static TestInfrastructure create(String type) {
        return switch (type) {
            case "http-mock" -> new HttpMockInfrastructure();
            case "shell-sandbox" -> new ShellSandboxInfrastructure();
            default -> throw new IllegalArgumentException(
                "Unknown infrastructure type: '" + type
                    + "'. Supported: http-mock, shell-sandbox");
        };
    }
}
