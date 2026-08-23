package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.Customize;
import io.casehub.desiredstate.annotations.DeclareNode;
import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CrossModelValidationTest {

    // --- Duplicate node ID across models ---

    @RegisterExtension
    static final QuarkusUnitTest duplicateId = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    DupInterface.class, DupClass.class, DupSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("Duplicate node id 'shared-id'"));

    public record DupSpec(String d) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("dup"); }
    }

    @DesiredState(namespace = "dup", name = "test")
    public interface DupInterface {
        @Node("shared-id")
        default DupSpec dupNode() { return new DupSpec("iface"); }
    }

    @DeclareNode(namespace = "dup", name = "test", id = "shared-id")
    public static class DupClass implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("dup"); }
    }

    // --- Cross-model string ref: unresolved ---

    @RegisterExtension
    static final QuarkusUnitTest crossRefUnresolved = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    RefInterface.class, RefClass.class, RefSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("references 'nonexistent'")
                    .contains("not declared as @Node or @DeclareNode"));

    public record RefSpec(String d) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("ref"); }
    }

    @DesiredState(namespace = "ref", name = "test")
    public interface RefInterface {
        @Node("ref-node")
        default RefSpec refNode() { return new RefSpec("r"); }
    }

    @DeclareNode(namespace = "ref", name = "test", id = "ref-class")
    @DependsOn("nonexistent")
    public static class RefClass implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("ref"); }
    }

    // --- Cross-model cycle ---

    @RegisterExtension
    static final QuarkusUnitTest crossCycle = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    CycleInterface.class, CycleClass.class, CycleSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("Circular dependency detected"));

    public record CycleSpec(String d) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("cyc"); }
    }

    @DesiredState(namespace = "cyc", name = "test")
    public interface CycleInterface {
        @Node("cycle-a")
        @DependsOn("cycle-b")
        default CycleSpec cycleA() { return new CycleSpec("a"); }
    }

    @DeclareNode(namespace = "cyc", name = "test", id = "cycle-b")
    @DependsOn("cycle-a")
    public static class CycleClass implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("cyc"); }
    }

    // --- Duplicate @DesiredState interfaces with same graph key ---

    @RegisterExtension
    static final QuarkusUnitTest duplicateDesiredState = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    DupDsA.class, DupDsB.class, DupDsSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("Multiple @DesiredState interfaces with graph key"));

    public record DupDsSpec(String d) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("dds"); }
    }

    @DesiredState(namespace = "dupds", name = "test")
    public interface DupDsA {
        @Node("ds-a")
        default DupDsSpec dsA() { return new DupDsSpec("a"); }
    }

    @DesiredState(namespace = "dupds", name = "test")
    public interface DupDsB {
        @Node("ds-b")
        default DupDsSpec dsB() { return new DupDsSpec("b"); }
    }

    // --- @Customize on @DeclareNode ---

    @RegisterExtension
    static final QuarkusUnitTest customizeOnDeclare = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(CustomizeOnClass.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("@Customize on @DeclareNode class")
                    .contains("@Customize requires a @DesiredState interface"));

    @DeclareNode(namespace = "cust", name = "test", id = "bad-cust")
    public static class CustomizeOnClass implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("c"); }

        @Customize
        public static void badCustomizer() {}
    }

    // --- @DependsOn(nodes) target lacks @DeclareNode ---

    @RegisterExtension
    static final QuarkusUnitTest nodesTargetNoDeclareNode = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    TargetNoDeclare.class, SourceWithBadNodes.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("has no @DeclareNode annotation"));

    public static class TargetNoDeclare implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("t"); }
    }

    @DeclareNode(namespace = "nodes-v", name = "test", id = "bad-source")
    @DependsOn(nodes = TargetNoDeclare.class)
    public static class SourceWithBadNodes implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("s"); }
    }

    @Test
    void validationTestsAreInExtensions() {
    }
}
