package io.casehub.desiredstate.bridge.terraform;

import com.google.protobuf.ByteString;
import io.casehub.desiredstate.bridge.terraform.proto.v6.*;
import io.grpc.ManagedChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ProtocolV6Adapter implements TerraformProtocol {

    private final ProviderGrpc.ProviderBlockingStub stub;
    private final ManagedChannel channel;

    ProtocolV6Adapter(ManagedChannel channel) {
        this.channel = channel;
        this.stub = ProviderGrpc.newBlockingStub(channel);
    }

    @Override
    public ProviderSchema getSchema() {
        var response = stub.getProviderSchema(GetProviderSchema.Request.newBuilder().build());
        Map<String, ResourceSchema> schemas = new LinkedHashMap<>();
        for (var entry : response.getResourceSchemasMap().entrySet()) {
            schemas.put(entry.getKey(), translateSchema(entry.getValue()));
        }
        ResourceSchema providerSchema = response.hasProvider()
            ? translateSchema(response.getProvider())
            : new ResourceSchema(Map.of());
        return new ProviderSchema(schemas, providerSchema);
    }

    @Override
    public void configure(Map<String, Object> config) {
        var dv = DynamicValue.newBuilder()
            .setMsgpack(ByteString.copyFrom(CtyEncoder.encodeMsgpack(config)))
            .build();
        var response = stub.configureProvider(ConfigureProvider.Request.newBuilder()
            .setTerraformVersion("1.0.0")
            .setConfig(dv)
            .build());
        checkDiagnostics(translateDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public PlanResponse plan(String resourceType, Map<String, Object> priorState,
                              Map<String, Object> proposedState) {
        var builder = PlanResourceChange.Request.newBuilder()
            .setTypeName(resourceType)
            .setPriorState(encodeMsgpackDynamic(priorState));
        if (proposedState != null) {
            builder.setProposedNewState(encodeMsgpackDynamic(proposedState));
            builder.setConfig(encodeMsgpackDynamic(proposedState));
        }
        var response = stub.planResourceChange(builder.build());
        return new PlanResponse(
            decodeMsgpackDynamic(response.getPlannedState()),
            response.getPlannedPrivate().toByteArray(),
            !response.getRequiresReplaceList().isEmpty(),
            translateDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public ApplyResponse apply(String resourceType, Map<String, Object> priorState,
                                byte[] plannedPrivate) {
        var builder = ApplyResourceChange.Request.newBuilder()
            .setTypeName(resourceType)
            .setPriorState(encodeMsgpackDynamic(priorState));
        if (plannedPrivate != null && plannedPrivate.length > 0) {
            builder.setPlannedPrivate(ByteString.copyFrom(plannedPrivate));
        }
        var response = stub.applyResourceChange(builder.build());
        return new ApplyResponse(
            decodeMsgpackDynamic(response.getNewState()),
            response.getPrivate().toByteArray(),
            translateDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public ReadResponse read(String resourceType, Map<String, Object> currentState) {
        var response = stub.readResource(ReadResource.Request.newBuilder()
            .setTypeName(resourceType)
            .setCurrentState(encodeMsgpackDynamic(currentState))
            .build());
        return new ReadResponse(
            decodeMsgpackDynamic(response.getNewState()),
            response.getPrivate().toByteArray(),
            translateDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public void close() {}

    private ResourceSchema translateSchema(Schema schema) {
        Map<String, AttributeSchema> attrs = new LinkedHashMap<>();
        if (schema.hasBlock()) {
            for (var attr : schema.getBlock().getAttributesList()) {
                String type = decodeCtyType(attr.getType().toByteArray());
                attrs.put(attr.getName(), new AttributeSchema(
                    attr.getName(), type, attr.getRequired(), attr.getOptional(),
                    attr.getComputed(), attr.getSensitive(), attr.getDescription()));
            }
        }
        return new ResourceSchema(attrs);
    }

    private String decodeCtyType(byte[] typeBytes) {
        if (typeBytes == null || typeBytes.length == 0) return "string";
        try {
            Object decoded = CtyDecoder.decodeMsgpack(typeBytes);
            if (decoded instanceof Map) return "object";
            return "string";
        } catch (Exception e) {
            try {
                Object decoded = CtyDecoder.decodeJsonValue(new String(typeBytes));
                if (decoded instanceof String s) return s;
            } catch (Exception ignored) {}
            return "string";
        }
    }

    private DynamicValue encodeMsgpackDynamic(Map<String, Object> value) {
        byte[] msgpack = CtyEncoder.encodeMsgpack(value);
        if (msgpack.length == 0) {
            msgpack = CtyEncoder.encodeMsgpack(Map.of());
        }
        return DynamicValue.newBuilder()
            .setMsgpack(ByteString.copyFrom(msgpack))
            .build();
    }

    private Map<String, Object> decodeMsgpackDynamic(DynamicValue dv) {
        if (dv == null || dv.getMsgpack().isEmpty()) return Map.of();
        return CtyDecoder.decodeMsgpack(dv.getMsgpack().toByteArray());
    }

    private List<TfDiagnostic> translateDiagnostics(List<Diagnostic> diagnostics) {
        List<TfDiagnostic> result = new ArrayList<>();
        for (var d : diagnostics) {
            TfDiagnostic.Severity severity = switch (d.getSeverity()) {
                case ERROR -> TfDiagnostic.Severity.ERROR;
                default -> TfDiagnostic.Severity.WARNING;
            };
            result.add(new TfDiagnostic(severity, d.getSummary(), d.getDetail()));
        }
        return result;
    }

    private void checkDiagnostics(List<TfDiagnostic> diagnostics) {
        for (var d : diagnostics) {
            if (d.severity() == TfDiagnostic.Severity.ERROR) {
                throw new RuntimeException("Terraform error: " + d.summary() + ": " + d.detail());
            }
        }
    }
}
