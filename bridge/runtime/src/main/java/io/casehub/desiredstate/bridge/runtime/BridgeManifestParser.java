package io.casehub.desiredstate.bridge.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.*;

public final class BridgeManifestParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private BridgeManifestParser() {}

    public static BridgeDescriptor parse(InputStream is) {
        try {
            var manifest = YAML.readValue(is, BridgeManifest.class);
            return toDescriptor(manifest.bridge());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse bridge manifest", e);
        }
    }

    private static BridgeDescriptor toDescriptor(BridgeManifest.BridgeRoot root) {
        Set<NodeType> handledTypes = new LinkedHashSet<>();
        Map<String, BridgeTypeDescriptor> typeDescriptors = new LinkedHashMap<>();

        for (var typeDef : root.types()) {
            var nodeType = NodeType.of(typeDef.id());
            handledTypes.add(nodeType);
            typeDescriptors.put(typeDef.id(), toTypeDescriptor(nodeType, typeDef));
        }

        return new BridgeDescriptor(root.protocol(), handledTypes, typeDescriptors, toLifecycleConfig(root.lifecycle()), root.credentialRef(), root.trustedKeyIds() != null ? root.trustedKeyIds() : java.util.List.of());
    }

    private static BridgeTypeDescriptor toTypeDescriptor(NodeType nodeType, BridgeManifest.TypeDef def) {
        Map<String, NodeStatus> stateMapping = new LinkedHashMap<>();
        NodeStatus defaultState = null;
        NodeStatus notFoundState = null;

        if (def.stateMapping() != null) {
            for (var entry : def.stateMapping().entrySet()) {
                switch (entry.getKey()) {
                    case "_default" -> defaultState = NodeStatus.valueOf(entry.getValue());
                    case "_notFound" -> notFoundState = NodeStatus.valueOf(entry.getValue());
                    default -> stateMapping.put(entry.getKey(), NodeStatus.valueOf(entry.getValue()));
                }
            }
        }

        Map<String, BridgeErrorCategory> errorMapping = new LinkedHashMap<>();
        BridgeErrorCategory defaultErrorCategory = null;

        if (def.errorMapping() != null) {
            for (var entry : def.errorMapping().entrySet()) {
                var category = BridgeErrorCategory.valueOf(entry.getValue().category().toUpperCase());
                if ("_default".equals(entry.getKey())) {
                    defaultErrorCategory = category;
                } else {
                    errorMapping.put(entry.getKey(), category);
                }
            }
        }

        return new BridgeTypeDescriptor(nodeType, stateMapping, defaultState, notFoundState, errorMapping, defaultErrorCategory);
    }

    private static BridgeLifecycleConfig toLifecycleConfig(BridgeManifest.LifecycleDef def) {
        if (def == null) return null;

        LifecycleMode mode = switch (def.mode()) {
            case "managed-process" -> LifecycleMode.MANAGED_PROCESS;
            case "external-service" -> LifecycleMode.EXTERNAL_SERVICE;
            default -> throw new IllegalArgumentException("Unknown lifecycle mode: " + def.mode());
        };

        HealthCheckConfig healthCheck = null;
        if (def.healthCheck() != null) {
            healthCheck = new HealthCheckConfig(
                HealthCheckType.valueOf(def.healthCheck().type().toUpperCase()),
                parseDuration(def.healthCheck().interval()),
                parseDuration(def.healthCheck().timeout()));
        }

        RestartPolicy restartPolicy = null;
        if (def.restart() != null) {
            restartPolicy = new RestartPolicy(
                parseRestartPolicyType(def.restart().policy()),
                def.restart().maxRetries(),
                BackoffStrategy.valueOf(def.restart().backoff().toUpperCase()));
        }

        return new BridgeLifecycleConfig(mode, def.binary(), def.args(), healthCheck, restartPolicy, def.endpoint(), toPoolConfig(def.pool()));
    }

    private static PoolConfig toPoolConfig(BridgeManifest.PoolDef pool) {
        if (pool == null) return null;
        int maxProcesses = pool.maxProcesses() > 0 ? pool.maxProcesses() : PoolConfig.DEFAULT.maxProcesses();
        Duration idleTimeout = pool.idleTimeout() != null ? parseDuration(pool.idleTimeout()) : PoolConfig.DEFAULT.idleTimeout();
        Duration checkoutTimeout = pool.checkoutTimeout() != null ? parseDuration(pool.checkoutTimeout()) : PoolConfig.DEFAULT.checkoutTimeout();
        return new PoolConfig(maxProcesses, idleTimeout, checkoutTimeout);
    }

    private static RestartPolicyType parseRestartPolicyType(String policy) {
        return switch (policy) {
            case "on-failure" -> RestartPolicyType.ON_FAILURE;
            case "always" -> RestartPolicyType.ALWAYS;
            case "never" -> RestartPolicyType.NEVER;
            default -> throw new IllegalArgumentException("Unknown restart policy: " + policy);
        };
    }

    private static Duration parseDuration(String value) {
        if (value == null) return null;
        if (value.endsWith("s")) return Duration.ofSeconds(Long.parseLong(value.replace("s", "")));
        if (value.endsWith("m")) return Duration.ofMinutes(Long.parseLong(value.replace("m", "")));
        return Duration.parse("PT" + value);
    }
}
