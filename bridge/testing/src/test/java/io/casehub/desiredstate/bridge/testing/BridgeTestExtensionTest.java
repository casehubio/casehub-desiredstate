package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static org.assertj.core.api.Assertions.assertThat;

class BridgeTestExtensionTest {

    @RegisterExtension
    BridgeTestExtension bridge = BridgeTestExtension.forTypes("test-proto", "test_resource");

    @Test
    void mockProviderIsCreated() {
        assertThat(bridge.mockProvider()).isNotNull();
        assertThat(bridge.mockProvider().protocolId()).isEqualTo("test-proto");
        assertThat(bridge.mockProvider().supportedTypes()).containsExactly("test_resource");
    }

    @Test
    void descriptorHasCorrectTypes() {
        assertThat(bridge.descriptor().handledTypes())
            .containsExactly(NodeType.of("test_resource"));
    }

    @Test
    void descriptorHasCorrectProtocolId() {
        assertThat(bridge.descriptor().protocolId()).isEqualTo("test-proto");
    }

    @Test
    void builderCustomizesStateMapping() {
        var custom = BridgeTestExtension.builder("custom-proto", "custom_res")
            .stateMapping("running", NodeStatus.PRESENT)
            .stateMapping("terminated", NodeStatus.ABSENT)
            .defaultState(NodeStatus.UNKNOWN)
            .notFoundState(NodeStatus.ABSENT)
            .build();
        custom.beforeEach(null);

        assertThat(custom.mockProvider().protocolId()).isEqualTo("custom-proto");
        assertThat(custom.descriptor().handledTypes())
            .containsExactly(NodeType.of("custom_res"));

        var typeDesc = custom.descriptor().typeDescriptors().get("custom_res");
        assertThat(typeDesc.defaultState()).isEqualTo(NodeStatus.UNKNOWN);
        assertThat(typeDesc.notFoundState()).isEqualTo(NodeStatus.ABSENT);
        assertThat(typeDesc.stateMapping()).containsEntry("running", NodeStatus.PRESENT);
        assertThat(typeDesc.stateMapping()).containsEntry("terminated", NodeStatus.ABSENT);
    }
}
