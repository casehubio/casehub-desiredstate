package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.FaultPolicyDef;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.annotations.Tier;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ValidationErrorTest {

    public record TestSpec(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() {
            return NodeType.of("test");
        }
    }

    // --- Duplicate node IDs ---

    @DesiredState(namespace = "err", name = "dup")
    public interface DuplicateNodeIds {
        @Node("same-id")
        default TestSpec first() { return new TestSpec("a"); }

        @Node("same-id")
        default TestSpec second() { return new TestSpec("b"); }
    }

    @Test
    void rejectsDuplicateNodeIds() {
        try {
            var ext = new QuarkusUnitTest()
                    .withApplicationRoot(root -> root.addClasses(
                            DuplicateNodeIds.class, TestSpec.class))
                    .overrideConfigKey("quarkus.arc.exclude-types",
                            "io.casehub.desiredstate.runtime.**")
                    .assertException(t -> assertThat(t.getMessage())
                            .contains("Duplicate @Node id 'same-id'"));
            ext.beforeAll(null);
            ext.afterAll(null);
        } catch (Exception e) {
            // lifecycle methods may throw — the assertException handles it
        }
    }

    // --- Unknown DependsOn reference ---

    @DesiredState(namespace = "err", name = "unknownRef")
    public interface UnknownDependsOnRef {
        @Node("node-a")
        default TestSpec nodeA() { return new TestSpec("a"); }

        @Node("node-b")
        @DependsOn("nonexistent")
        default TestSpec nodeB() { return new TestSpec("b"); }
    }

    @Test
    void rejectsUnknownDependsOnRef() {
        try {
            var ext = new QuarkusUnitTest()
                    .withApplicationRoot(root -> root.addClasses(
                            UnknownDependsOnRef.class, TestSpec.class))
                    .overrideConfigKey("quarkus.arc.exclude-types",
                            "io.casehub.desiredstate.runtime.**")
                    .assertException(t -> assertThat(t.getMessage())
                            .contains("references 'nonexistent'"));
            ext.beforeAll(null);
            ext.afterAll(null);
        } catch (Exception e) {
            // expected
        }
    }

    // --- Circular dependency ---

    @DesiredState(namespace = "err", name = "circular")
    public interface CircularDeps {
        @Node("a")
        @DependsOn("b")
        default TestSpec nodeA() { return new TestSpec("a"); }

        @Node("b")
        @DependsOn("a")
        default TestSpec nodeB() { return new TestSpec("b"); }
    }

    @Test
    void rejectsCircularDependency() {
        try {
            var ext = new QuarkusUnitTest()
                    .withApplicationRoot(root -> root.addClasses(
                            CircularDeps.class, TestSpec.class))
                    .overrideConfigKey("quarkus.arc.exclude-types",
                            "io.casehub.desiredstate.runtime.**")
                    .assertException(t -> assertThat(t.getMessage())
                            .contains("Circular dependency detected"));
            ext.beforeAll(null);
            ext.afterAll(null);
        } catch (Exception e) {
            // expected
        }
    }

    // --- FaultPolicyDef on non-@Node method ---

    @DesiredState(namespace = "err", name = "fpOnPlain")
    public interface FaultPolicyOnNonNode {
        @Node("node-a")
        default TestSpec nodeA() { return new TestSpec("a"); }

        @FaultPolicyDef(faultTypes = {"PROVISION_FAILED"},
                tiers = @Tier(threshold = 3, review = "createReview"))
        default TestSpec plainMethod() { return new TestSpec("b"); }

        default TestSpec createReview(FaultEvent e, DesiredStateGraph g) {
            return new TestSpec("review");
        }
    }

    @Test
    void rejectsFaultPolicyOnNonNodeMethod() {
        try {
            var ext = new QuarkusUnitTest()
                    .withApplicationRoot(root -> root.addClasses(
                            FaultPolicyOnNonNode.class, TestSpec.class))
                    .overrideConfigKey("quarkus.arc.exclude-types",
                            "io.casehub.desiredstate.runtime.**")
                    .assertException(t -> assertThat(t.getMessage())
                            .contains("not annotated with @Node"));
            ext.beforeAll(null);
            ext.afterAll(null);
        } catch (Exception e) {
            // expected
        }
    }

    // --- Missing review method ---

    @DesiredState(namespace = "err", name = "missingReview")
    @FaultPolicyDef(faultTypes = {"PROVISION_FAILED"},
            nodeTypes = {"test"},
            tiers = @Tier(threshold = 3, review = "missingMethod"))
    public interface MissingReviewMethod {
        @Node("node-a")
        default TestSpec nodeA() { return new TestSpec("a"); }
    }

    @Test
    void rejectsMissingReviewMethod() {
        try {
            var ext = new QuarkusUnitTest()
                    .withApplicationRoot(root -> root.addClasses(
                            MissingReviewMethod.class, TestSpec.class))
                    .overrideConfigKey("quarkus.arc.exclude-types",
                            "io.casehub.desiredstate.runtime.**")
                    .assertException(t -> assertThat(t.getMessage())
                            .contains("@Tier review 'missingMethod' not found"));
            ext.beforeAll(null);
            ext.afterAll(null);
        } catch (Exception e) {
            // expected
        }
    }
}
