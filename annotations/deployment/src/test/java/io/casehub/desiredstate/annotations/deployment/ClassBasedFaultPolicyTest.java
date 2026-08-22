package io.casehub.desiredstate.annotations.deployment;

import io.casehub.desiredstate.annotations.DeclareNode;
import io.casehub.desiredstate.annotations.FaultPolicyDef;
import io.casehub.desiredstate.annotations.Tier;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class ClassBasedFaultPolicyTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    FaultedNode.class, ReviewSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record ReviewSpec(NodeId faultedNode, String detail) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("review"); }
    }

    @DeclareNode(namespace = "fp", name = "test", id = "faulted-node")
    @FaultPolicyDef(
            faultTypes = {"PROVISION_FAILED"},
            tiers = {@Tier(threshold = 3, review = "createReview")}
    )
    public static class FaultedNode implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("faulted"); }

        public ReviewSpec createReview(FaultEvent event, DesiredStateGraph graph) {
            return new ReviewSpec(event.node(), event.detail());
        }
    }

    @Inject
    Instance<FaultPolicy> faultPolicies;

    @Test
    void faultPolicyBeanRegistered() {
        assertThat(faultPolicies.stream().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void faultPolicyIsThresholdType() {
        FaultPolicy policy    = faultPolicies.stream().findFirst().orElseThrow();
        Object      unwrapped = io.quarkus.arc.ClientProxy.unwrap(policy);
        assertThat(unwrapped).isInstanceOf(ThresholdFaultPolicy.class);
    }
}
