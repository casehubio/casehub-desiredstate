package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.BridgeTypeDescriptor;
import io.casehub.desiredstate.plugin.api.PluginFieldDef;
import io.casehub.desiredstate.plugin.api.PluginSpecSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SchemaTranslator {

    private SchemaTranslator() {}

    public static List<DiscoveredType> translate(String namespace, String providerType,
                                                  ProviderSchema schema) {
        List<DiscoveredType> types = new ArrayList<>();
        for (var entry : schema.resourceSchemas().entrySet()) {
            String resourceName = entry.getKey();
            ResourceSchema rs = entry.getValue();

            NodeType nodeType = NodeType.of("tf:" + namespace + "/" + providerType + ":" + resourceName);
            PluginSpecSchema specSchema = translateSchema(rs);
            BridgeTypeDescriptor typeDesc = new BridgeTypeDescriptor(
                nodeType,
                Map.of("present", NodeStatus.PRESENT, "drifted", NodeStatus.DRIFTED),
                NodeStatus.DRIFTED,
                NodeStatus.ABSENT,
                Map.of(),
                BridgeErrorCategory.PERMANENT
            );
            types.add(new DiscoveredType(nodeType, specSchema, typeDesc));
        }
        return types;
    }

    private static PluginSpecSchema translateSchema(ResourceSchema rs) {
        Map<String, PluginFieldDef> fields = new LinkedHashMap<>();
        for (var attr : rs.attributes().values()) {
            if (attr.computed() && !attr.required() && !attr.optional()) {
                continue;
            }
            fields.put(attr.name(), translateAttribute(attr));
        }
        return new PluginSpecSchema(fields);
    }

    private static PluginFieldDef translateAttribute(AttributeSchema attr) {
        String fieldType = switch (attr.type()) {
            case "string" -> "STRING";
            case "number" -> "NUMBER";
            case "bool" -> "BOOLEAN";
            case "list" -> "LIST";
            case "set" -> "LIST";
            case "map" -> "MAP";
            default -> "STRING";
        };
        return new PluginFieldDef(
            fieldType, attr.required(), null, null,
            null, null, null, null, null, null, null, null, null,
            attr.sensitive(), attr.deprecated()
        );
    }
}
