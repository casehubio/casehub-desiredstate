package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.BridgeImportResult;
import io.casehub.desiredstate.bridge.BridgePlan;
import io.casehub.desiredstate.bridge.BridgeProvider;
import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.bridge.BridgeStateResult;
import io.casehub.desiredstate.bridge.ChangeAction;
import io.casehub.desiredstate.bridge.DiscoveredResource;
import io.casehub.desiredstate.bridge.DiscoveryCapable;
import io.casehub.desiredstate.bridge.ImportCapable;
import io.casehub.desiredstate.bridge.PlanCapable;
import io.casehub.desiredstate.bridge.ResourceChange;
import io.casehub.desiredstate.bridge.SuspendCapable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MockCloudProvider implements BridgeProvider, PlanCapable, ImportCapable, SuspendCapable, DiscoveryCapable {

    private static final Set<String>                      TYPES             = Set.of("cloud:vm", "cloud:network", "cloud:storage");
    private static final Set<String>                      SUSPENDABLE_TYPES = Set.of("cloud:vm");
    private final        Map<String, Map<String, Object>> resources         = new ConcurrentHashMap<>();

    @Override
    public String protocolId() {
        return "mock-cloud";
    }

    @Override
    public Set<String> supportedTypes() {
        return TYPES;
    }

    @Override
    public BridgeResult apply(BridgeRequest request) {
        var state = new HashMap<>(request.spec());
        state.put("id", request.resourceId());
        state.put("status", defaultStatus(request.typeId()));
        resources.put(key(request.typeId(), request.resourceId()), state);
        return new BridgeResult.Success(Map.copyOf(state));
    }

    @Override
    public BridgeResult destroy(BridgeRequest request) {
        resources.remove(key(request.typeId(), request.resourceId()));
        return new BridgeResult.Success(Map.of());
    }

    @Override
    public BridgeStateResult readState(BridgeReadRequest request) {
        var state = resources.get(key(request.typeId(), request.resourceId()));
        if (state == null) {
            return new BridgeStateResult.NotFound();
        }
        return new BridgeStateResult.Found(Map.copyOf(state));
    }

    @Override
    public BridgePlan plan(BridgeRequest request) {
        var          existing = resources.get(key(request.typeId(), request.resourceId()));
        ChangeAction action;
        if (existing == null) {
            action = ChangeAction.CREATE;
        } else if (specDiffers(existing, request.spec())) {
            action = ChangeAction.UPDATE;
        } else {
            return new BridgePlan(
                    UUID.randomUUID().toString(), request.tenancyId(), request.typeId(),
                    "No changes", List.of(), null);
        }
        return new BridgePlan(
                UUID.randomUUID().toString(), request.tenancyId(), request.typeId(),
                action + " " + request.resourceId(),
                List.of(new ResourceChange(request.typeId(), request.resourceId(), action)),
                request.spec());
    }

    @Override
    public BridgeResult applyPlan(BridgePlan plan) {
        if (plan.resourceChanges().isEmpty()) {
            return new BridgeResult.Success(Map.of());
        }
        var change = plan.resourceChanges().getFirst();
        if (change.action() == ChangeAction.DELETE) {
            resources.remove(key(change.typeId(), change.resourceId()));
            return new BridgeResult.Success(Map.of());
        }
        @SuppressWarnings("unchecked")
        var spec = plan.rawPlan() instanceof Map<?, ?> m ? toStringMap(m) : Map.<String, Object>of();
        var state = new HashMap<>(spec);
        state.put("id", change.resourceId());
        state.put("status", defaultStatus(change.typeId()));
        resources.put(key(change.typeId(), change.resourceId()), state);
        return new BridgeResult.Success(Map.copyOf(state));
    }

    @Override
    public BridgeImportResult importResource(String typeId, String importId, String tenancyId) {
        var state = resources.get(key(typeId, importId));
        if (state == null) {
            return new BridgeImportResult.Failed("Resource not found: " + importId);
        }
        return new BridgeImportResult.Success(Map.copyOf(state));
    }

    @Override
    public BridgeResult suspend(BridgeRequest request) {
        if (!SUSPENDABLE_TYPES.contains(request.typeId())) {
            return new BridgeResult.Failed("Suspend not supported for type: " + request.typeId(),
                                           BridgeErrorCategory.CONFIGURATION);
        }
        var state = resources.get(key(request.typeId(), request.resourceId()));
        if (state == null) {
            return new BridgeResult.Failed("Resource not found: " + request.resourceId(),
                                           BridgeErrorCategory.PERMANENT);
        }
        state.put("status", "stopped");
        return new BridgeResult.Success(Map.copyOf(state));
    }

    @Override
    public BridgeResult resume(BridgeRequest request) {
        if (!SUSPENDABLE_TYPES.contains(request.typeId())) {
            return new BridgeResult.Failed("Resume not supported for type: " + request.typeId(),
                                           BridgeErrorCategory.CONFIGURATION);
        }
        var state = resources.get(key(request.typeId(), request.resourceId()));
        if (state == null) {
            return new BridgeResult.Failed("Resource not found: " + request.resourceId(),
                                           BridgeErrorCategory.PERMANENT);
        }
        state.put("status", "running");
        return new BridgeResult.Success(Map.copyOf(state));
    }

    @Override
    public List<DiscoveredResource> discover(String typeId, String tenancyId) {
        return resources.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(typeId + "::"))
                        .map(e -> {
                            String resourceId = e.getKey().substring(e.getKey().indexOf("::") + 2);
                            return new DiscoveredResource(resourceId, typeId, Map.copyOf(e.getValue()));
                        })
                        .toList();
    }


    public void seedResource(String typeId, String resourceId, Map<String, Object> state) {
        resources.put(key(typeId, resourceId), new HashMap<>(state));
    }

    public void injectDrift(String typeId, String resourceId, String field, Object value) {
        var state = resources.get(key(typeId, resourceId));
        if (state != null) {
            state.put(field, value);
        }
    }

    private String key(String typeId, String resourceId) {
        return typeId + "::" + resourceId;
    }

    private String defaultStatus(String typeId) {
        return switch (typeId) {
            case "cloud:vm" -> "running";
            case "cloud:network" -> "active";
            case "cloud:storage" -> "available";
            default -> "unknown";
        };
    }

    private boolean specDiffers(Map<String, Object> existing, Map<String, Object> desired) {
        for (var entry : desired.entrySet()) {
            if (!Objects.equals(existing.get(entry.getKey()), entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> toStringMap(Map<?, ?> m) {
        var result = new HashMap<String, Object>();
        m.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }
}
