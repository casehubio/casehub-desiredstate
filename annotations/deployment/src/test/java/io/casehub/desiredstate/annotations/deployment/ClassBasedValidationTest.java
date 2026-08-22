package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DeclareNode;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.GoalMethod;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ClassBasedValidationTest {

    @RegisterExtension
    static final QuarkusUnitTest notNodeSpec = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(NotNodeSpecClass.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("does not implement NodeSpec"));

    @DeclareNode(namespace = "v", name = "t", id = "bad")
    public static class NotNodeSpecClass {}

    @RegisterExtension
    static final QuarkusUnitTest onInterface = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(BadInterface.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("use @DesiredState for interfaces"));

    @DeclareNode(namespace = "v", name = "t", id = "iface")
    public interface BadInterface extends NodeSpec {}

    @RegisterExtension
    static final QuarkusUnitTest onAbstract = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(AbstractNode.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("must be concrete"));

    @DeclareNode(namespace = "v", name = "t", id = "abs")
    public abstract static class AbstractNode implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("t"); }
    }

    @RegisterExtension
    static final QuarkusUnitTest goalMethodOnClass = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(GoalOnClass.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("@GoalMethod requires a @DesiredState interface"));

    @DeclareNode(namespace = "v", name = "t", id = "gm")
    public static class GoalOnClass implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("t"); }

        @GoalMethod
        public DesiredStateGraph goal(String goals, DesiredStateGraph base) {
            return base;
        }
    }

    @RegisterExtension
    static final QuarkusUnitTest nodeAnnotationOnClass = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(NodeOnClass.class, DummySpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t.getMessage())
                    .contains("@Node is for @DesiredState interfaces"));

    public record DummySpec() implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("d"); }
    }

    @DeclareNode(namespace = "v", name = "t", id = "nc")
    public static class NodeOnClass implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("t"); }

        @Node("wrong")
        public DummySpec wrongNode() {
            return new DummySpec();
        }
    }

    @Test
    void validationTestsAreInExtensions() {
    }
}
