package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.api.NodeType;

public final class CloudNodeTypes {
    public static final NodeType VM = NodeType.of("cloud:vm");
    public static final NodeType NETWORK = NodeType.of("cloud:network");
    public static final NodeType STORAGE = NodeType.of("cloud:storage");
    public static final NodeType APP_DEPLOYMENT = NodeType.of("app:deployment");

    private CloudNodeTypes() {}
}
