package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DeclareNode;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.FaultPolicyDef;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.annotations.Tier;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MergedFaultPolicyTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    MergeBase.class, MergeExtension.class, MSpec.class, ReviewSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record MSpec(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("m"); }
    }

    public record ReviewSpec(String detail) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("review"); }
    }

    @DesiredState(namespace = "merge-fp", name = "test")
    public interface MergeBase {
        @Node("base-node")
        default MSpec baseNode() { return new MSpec("base"); }
    }

    @DeclareNode(namespace = "merge-fp", name = "test", id = "ext-node")
    @FaultPolicyDef(
            faultTypes = {"PROVISION_FAILED"},
            tiers = {@Tier(threshold = 3, review = "createReview")}
    )
    public static class MergeExtension implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("ext"); }

        public ReviewSpec createReview(FaultEvent event, DesiredStateGraph graph) {
            return new ReviewSpec("review");
        }
    }

    @Inject
    Instance<FaultPolicy> faultPolicies;

    @Test
    void classFaultPolicyPreservedInMergedGraph() {
        long count = faultPolicies.stream().count();
        assertThat(count).isGreaterThanOrEqualTo(1);
    }
}
