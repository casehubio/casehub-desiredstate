package io.casehub.desiredstate.yaml.deployment;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanNodeTypesTest {

    @NodeTypeId("nodespec-type")
    public record NodeSpecImpl() implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("nodespec-type"); }
    }

    @NodeTypeId("non-nodespec-type")
    public record NonNodeSpec(String resourceType) {
    }

    @NodeTypeId("nodespec-type")
    public record DuplicateType() implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("nodespec-type"); }
    }

    @Test
    void discoversNodeSpecImplementors() throws IOException {
        Index index = buildIndex(NodeSpecImpl.class);
        Map<String, String> result = YamlDesiredStateProcessor.scanNodeTypes(index);
        assertThat(result).containsEntry("nodespec-type", NodeSpecImpl.class.getName());
    }

    @Test
    void discoversNonNodeSpecAnnotatedClasses() throws IOException {
        Index index = buildIndex(NonNodeSpec.class);
        Map<String, String> result = YamlDesiredStateProcessor.scanNodeTypes(index);
        assertThat(result).containsEntry("non-nodespec-type", NonNodeSpec.class.getName());
    }

    @Test
    void discoversBothNodeSpecAndNonNodeSpec() throws IOException {
        Index index = buildIndex(NodeSpecImpl.class, NonNodeSpec.class);
        Map<String, String> result = YamlDesiredStateProcessor.scanNodeTypes(index);
        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("nodespec-type", NodeSpecImpl.class.getName());
        assertThat(result).containsEntry("non-nodespec-type", NonNodeSpec.class.getName());
    }

    @Test
    void throwsOnDuplicateTypeId() throws IOException {
        Index index = buildIndex(NodeSpecImpl.class, DuplicateType.class);
        assertThatThrownBy(() -> YamlDesiredStateProcessor.scanNodeTypes(index))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("nodespec-type")
                .hasMessageContaining("claimed by both");
    }

    private static Index buildIndex(Class<?>... classes) throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> cls : classes) {
            indexer.indexClass(cls);
        }
        indexer.indexClass(NodeTypeId.class);
        indexer.indexClass(NodeSpec.class);
        return indexer.complete();
    }
}
