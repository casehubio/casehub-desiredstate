package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderSchemaTest {

    @Test
    void providerSchemaHoldsProviderLevelAttributes() {
        var providerAttrs = Map.of(
            "region", new AttributeSchema("region", "string", false, true, false, false, false, "AWS region"),
            "access_key", new AttributeSchema("access_key", "string", false, true, false, true, false, "Access key")
        );
        var providerSchema = new ResourceSchema(providerAttrs);
        var schema = new ProviderSchema(Map.of(), providerSchema);

        assertThat(schema.providerSchema()).isNotNull();
        assertThat(schema.providerSchema().attributes()).containsKey("region");
        assertThat(schema.providerSchema().attributes()).containsKey("access_key");
        assertThat(schema.providerSchema().attributes().get("access_key").sensitive()).isTrue();
    }

    @Test
    void providerSchemaDefaultsToEmptyWhenSingleArgConstructor() {
        var schema = new ProviderSchema(Map.of());

        assertThat(schema.providerSchema()).isNotNull();
        assertThat(schema.providerSchema().attributes()).isEmpty();
    }
}
