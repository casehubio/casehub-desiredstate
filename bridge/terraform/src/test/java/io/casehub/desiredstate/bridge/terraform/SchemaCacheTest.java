package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.BridgeTypeDescriptor;
import io.casehub.desiredstate.plugin.api.PluginFieldDef;
import io.casehub.desiredstate.plugin.api.PluginSpecSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCacheTest {

    @TempDir Path tempDir;

    @Test
    void storeAndLoadRoundTrip() {
        var cache = new SchemaCache(tempDir);
        var types = List.of(sampleType("aws_instance"));

        cache.store("hashicorp", "aws", "5.60.0", types);
        var loaded = cache.load("hashicorp", "aws", "5.60.0");

        assertThat(loaded).isPresent();
        assertThat(loaded.get()).hasSize(1);
        assertThat(loaded.get().get(0).nodeType().value()).isEqualTo("tf:hashicorp/aws:aws_instance");
    }

    @Test
    void loadReturnEmptyForMissingVersion() {
        var cache = new SchemaCache(tempDir);
        assertThat(cache.load("hashicorp", "aws", "5.60.0")).isEmpty();
    }

    @Test
    void differentVersionsAreSeparate() {
        var cache = new SchemaCache(tempDir);
        cache.store("hashicorp", "aws", "5.60.0", List.of(sampleType("aws_instance")));
        cache.store("hashicorp", "aws", "5.61.0", List.of(sampleType("aws_instance"), sampleType("aws_s3_bucket")));

        var v1 = cache.load("hashicorp", "aws", "5.60.0");
        var v2 = cache.load("hashicorp", "aws", "5.61.0");
        assertThat(v1.get()).hasSize(1);
        assertThat(v2.get()).hasSize(2);
    }

    @Test
    void specSchemaFieldsPreserved() {
        var cache = new SchemaCache(tempDir);
        var field = new PluginFieldDef("STRING", true, null, null, null, null, null, null, null, null, null, null, null, false, false);
        var specSchema = new PluginSpecSchema(Map.of("name", field));
        var nodeType = NodeType.of("tf:hashicorp/test:test_res");
        var typeDesc = new BridgeTypeDescriptor(nodeType, Map.of(), NodeStatus.DRIFTED, NodeStatus.ABSENT, Map.of(), BridgeErrorCategory.PERMANENT);
        var type = new DiscoveredType(nodeType, specSchema, typeDesc);

        cache.store("hashicorp", "test", "1.0.0", List.of(type));
        var loaded = cache.load("hashicorp", "test", "1.0.0").get();

        var loadedField = loaded.get(0).specSchema().fields().get("name");
        assertThat(loadedField.type()).isEqualTo("STRING");
        assertThat(loadedField.required()).isTrue();
    }


    @Test
    void sensitiveAndDeprecatedFieldsPreserved() {
        var cache      = new SchemaCache(tempDir);
        var field      = new PluginFieldDef("STRING", true, null, null, null, null, null, null, null, null, null, null, null, true, true);
        var specSchema = new PluginSpecSchema(Map.of("password", field));
        var nodeType   = NodeType.of("tf:hashicorp/test:test_res");
        var typeDesc   = new BridgeTypeDescriptor(nodeType, Map.of(), NodeStatus.DRIFTED, NodeStatus.ABSENT, Map.of(), BridgeErrorCategory.PERMANENT);
        var type       = new DiscoveredType(nodeType, specSchema, typeDesc);

        cache.store("hashicorp", "test", "2.0.0", List.of(type));
        var loaded = cache.load("hashicorp", "test", "2.0.0").get();

        var loadedField = loaded.get(0).specSchema().fields().get("password");
        assertThat(loadedField.sensitive()).isTrue();
        assertThat(loadedField.deprecated()).isTrue();
    }

    private static DiscoveredType sampleType(String resourceName) {
        var nodeType = NodeType.of("tf:hashicorp/aws:" + resourceName);
        var specSchema = new PluginSpecSchema(Map.of(
            "name", new PluginFieldDef("STRING", true, null, null, null, null, null, null, null, null, null, null, null, false, false)
        ));
        var typeDesc = new BridgeTypeDescriptor(nodeType, Map.of(), NodeStatus.DRIFTED, NodeStatus.ABSENT, Map.of(), BridgeErrorCategory.PERMANENT);
        return new DiscoveredType(nodeType, specSchema, typeDesc);
    }
}
