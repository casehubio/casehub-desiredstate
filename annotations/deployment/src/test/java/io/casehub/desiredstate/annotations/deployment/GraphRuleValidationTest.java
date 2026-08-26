package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.GraphRule;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

class GraphRuleValidationTest {

    public record Spec() implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("t"); }
    }

    @DesiredState(namespace = "v", name = "nonstatic")
    public interface NonStaticRule {
        @Node("a")
        default Spec a() { return new Spec(); }

        @GraphRule
        default List<GraphMutation> badRule(DesiredStateGraph graph) { return List.of(); }
    }

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(NonStaticRule.class, Spec.class))
            .overrideConfigKey("quarkus.arc.exclude-types", "io.casehub.desiredstate.runtime.**")
            .assertException(t -> assertThat(t).hasMessageContaining("must be a static method"));

    @Test
    void nonStaticInterfaceRuleFails() {
    }
}
