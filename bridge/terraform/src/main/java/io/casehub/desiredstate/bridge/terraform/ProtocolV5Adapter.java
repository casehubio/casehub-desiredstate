package io.casehub.desiredstate.bridge.terraform;

import com.google.protobuf.ByteString;
import io.casehub.desiredstate.bridge.terraform.proto.v5.*;
import io.grpc.ManagedChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ProtocolV5Adapter implements TerraformProtocol {

    private final ProviderGrpc.ProviderBlockingStub stub;
    private final ManagedChannel channel;

    ProtocolV5Adapter(ManagedChannel channel) {
        this.channel = channel;
        this.stub = ProviderGrpc.newBlockingStub(channel);
    }

    @Override
    public ProviderSchema getSchema() {
        var response = stub.getSchema(GetProviderSchema.Request.newBuilder().build());
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
            .setJson(ByteString.copyFrom(CtyEncoder.encodeJson(config)))
            .build();
        var response = stub.configure(Configure.Request.newBuilder()
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
            .setPriorState(encodeJsonDynamic(priorState));
        if (proposedState != null) {
            builder.setProposedNewState(encodeJsonDynamic(proposedState));
            builder.setConfig(encodeJsonDynamic(proposedState));
        }
        var response = stub.planResourceChange(builder.build());
        return new PlanResponse(
            decodeJsonDynamic(response.getPlannedState()),
            response.getPlannedPrivate().toByteArray(),
            !response.getRequiresReplaceList().isEmpty(),
            translateDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public ApplyResponse apply(String resourceType, Map<String, Object> priorState,
                                byte[] plannedPrivate) {
        var builder = ApplyResourceChange.Request.newBuilder()
            .setTypeName(resourceType)
            .setPriorState(encodeJsonDynamic(priorState));
        if (plannedPrivate != null && plannedPrivate.length > 0) {
            builder.setPlannedPrivate(ByteString.copyFrom(plannedPrivate));
        }
        var response = stub.applyResourceChange(builder.build());
        return new ApplyResponse(
            decodeJsonDynamic(response.getNewState()),
            response.getPrivate().toByteArray(),
            translateDiagnostics(response.getDiagnosticsList()));
    }

    @Override
    public ReadResponse read(String resourceType, Map<String, Object> currentState) {
        var response = stub.readResource(ReadResource.Request.newBuilder()
            .setTypeName(resourceType)
            .setCurrentState(encodeJsonDynamic(currentState))
            .build());
        return new ReadResponse(
            decodeJsonDynamic(response.getNewState()),
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
                    attr.getComputed(), attr.getSensitive(), attr.getDeprecated(), attr.getDescription()));
            }
        }
        return new ResourceSchema(attrs);
    }

    private String decodeCtyType(byte[] typeBytes) {
        if (typeBytes == null || typeBytes.length == 0) return "string";
        try {
            Object decoded = CtyDecoder.decodeJsonValue(new String(typeBytes));
            if (decoded instanceof String s) return s;
            return "string";
        } catch (Exception e) {
            return "string";
        }
    }

    private DynamicValue encodeJsonDynamic(Map<String, Object> value) {
        byte[] json = CtyEncoder.encodeJson(value);
        return DynamicValue.newBuilder()
            .setJson(ByteString.copyFrom(json.length > 0 ? json : "{}".getBytes()))
            .build();
    }

    private Map<String, Object> decodeJsonDynamic(DynamicValue dv) {
        if (dv == null || dv.getJson().isEmpty()) return Map.of();
        return CtyDecoder.decodeJson(dv.getJson().toByteArray());
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
