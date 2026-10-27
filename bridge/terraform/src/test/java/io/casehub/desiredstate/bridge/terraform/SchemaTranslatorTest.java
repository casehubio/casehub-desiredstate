package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaTranslatorTest {

    @Test
    void translateResourceTypeToNamespacedNodeType() {
        var schema = new ProviderSchema(Map.of(
            "aws_instance", new ResourceSchema(Map.of(
                "ami", attr("ami", "string", true, false, false),
                "instance_type", attr("instance_type", "string", true, false, false),
                "id", attr("id", "string", false, false, true)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "aws", schema);
        assertThat(types).hasSize(1);
        assertThat(types.get(0).nodeType()).isEqualTo(NodeType.of("tf:hashicorp/aws:aws_instance"));
    }

    @Test
    void translateRequiredStringAttribute() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "name", attr("name", "string", true, false, false)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        var field = types.get(0).specSchema().fields().get("name");
        assertThat(field.type()).isEqualTo("STRING");
        assertThat(field.required()).isTrue();
    }

    @Test
    void translateOptionalStringAttribute() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "desc", attr("desc", "string", false, true, false)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        var field = types.get(0).specSchema().fields().get("desc");
        assertThat(field.type()).isEqualTo("STRING");
        assertThat(field.required()).isFalse();
    }

    @Test
    void excludeComputedOnlyAttributes() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "ami", attr("ami", "string", true, false, false),
                "id", attr("id", "string", false, false, true),
                "arn", attr("arn", "string", false, false, true)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        var fields = types.get(0).specSchema().fields();
        assertThat(fields).containsKey("ami");
        assertThat(fields).doesNotContainKey("id");
        assertThat(fields).doesNotContainKey("arn");
    }

    @Test
    void keepOptionalComputedAttributes() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "tags", attr("tags", "map", false, true, true)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        assertThat(types.get(0).specSchema().fields()).containsKey("tags");
    }

    @Test
    void translateNumberAttribute() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "count", attr("count", "number", false, true, false)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        var field = types.get(0).specSchema().fields().get("count");
        assertThat(field.type()).isEqualTo("NUMBER");
        assertThat(field.required()).isFalse();
    }

    @Test
    void translateBoolAttribute() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "enabled", attr("enabled", "bool", false, true, false)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        assertThat(types.get(0).specSchema().fields().get("enabled").type()).isEqualTo("BOOLEAN");
    }

    @Test
    void translateListAttribute() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "items", attr("items", "list", false, true, false)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        assertThat(types.get(0).specSchema().fields().get("items").type()).isEqualTo("LIST");
    }

    @Test
    void translateMapAttribute() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "tags", attr("tags", "map", false, true, false)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        assertThat(types.get(0).specSchema().fields().get("tags").type()).isEqualTo("MAP");
    }

    @Test
    void translateSetAttribute() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "sg_ids", attr("sg_ids", "set", false, true, false)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        assertThat(types.get(0).specSchema().fields().get("sg_ids").type()).isEqualTo("LIST");
    }

    @Test
    void unknownTypeDefaultsToString() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of(
                "complex", attr("complex", "object", false, true, false)
            ))
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        assertThat(types.get(0).specSchema().fields().get("complex").type()).isEqualTo("STRING");
    }

    @Test
    void bridgeTypeDescriptorHasCorrectDefaults() {
        var schema = new ProviderSchema(Map.of(
            "res", new ResourceSchema(Map.of())
        ));

        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        var td = types.get(0).typeDescriptor();
        assertThat(td.defaultState()).isEqualTo(NodeStatus.DRIFTED);
        assertThat(td.notFoundState()).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void multipleResourceTypesTranslated() {
        var schema = new ProviderSchema(Map.of(
            "aws_instance", new ResourceSchema(Map.of()),
            "aws_s3_bucket", new ResourceSchema(Map.of())
        ));

        var types = SchemaTranslator.translate("hashicorp", "aws", schema);
        assertThat(types).hasSize(2);
        var typeNames = types.stream().map(dt -> dt.nodeType().value()).toList();
        assertThat(typeNames).contains("tf:hashicorp/aws:aws_instance", "tf:hashicorp/aws:aws_s3_bucket");
    }

    @Test
    void emptySchemaProducesEmptyList() {
        var schema = new ProviderSchema(Map.of());
        var types = SchemaTranslator.translate("hashicorp", "test", schema);
        assertThat(types).isEmpty();
    }

    private static AttributeSchema attr(String name, String type,
                                         boolean required, boolean optional, boolean computed) {
        return new AttributeSchema(name, type, required, optional, computed, false, "");
    }
}
