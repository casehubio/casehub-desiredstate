package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.FaultPolicyDef;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.annotations.Tier;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class FaultPolicyWiringTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    GraphWithFaultPolicy.class, TestSpec.class, ReviewSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record TestSpec(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() {
            return NodeType.of("test-type");
        }
    }

    public record ReviewSpec(NodeId faultedNode, String detail) implements NodeSpec {
        @Override
        public NodeType nodeType() {
            return NodeType.of("review");
        }
    }

    @DesiredState(namespace = "test", name = "faulted")
    @FaultPolicyDef(
            faultTypes = {"PROVISION_FAILED"},
            nodeTypes = {"test-type"},
            tiers = @Tier(threshold = 3, review = "createReview")
    )
    public interface GraphWithFaultPolicy {

        @Node("node-a")
        default TestSpec nodeA() {
            return new TestSpec("a-data");
        }

        default ReviewSpec createReview(FaultEvent event, DesiredStateGraph graph) {
            return new ReviewSpec(event.node(), event.detail());
        }
    }

    @Inject
    Instance<FaultPolicy> policies;

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler compiler;

    @Test
    void generatesFaultPolicyBeanFromAnnotation() {
        assertThat(policies.stream().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void faultPolicyProducesMutationsOnFault() {
        var factory = new DefaultDesiredStateGraphFactory();
        var graph = ((io.casehub.desiredstate.api.CompilationResult.SingleGraph)
                compiler.compile(null, factory)).graph();

        var faultEvent = new FaultEvent(NodeId.of("node-a"),
                FaultType.PROVISION_FAILED, "test fault");

        FaultPolicy policy = policies.stream().findFirst().orElseThrow();
        // First 2 faults: below threshold, no mutations
        for (int i = 0; i < 2; i++) {
            var mutations = policy.onFault("tenant-1", faultEvent, graph, null);
            assertThat(mutations).isEmpty();
        }

        // 3rd fault: hits threshold, should produce review node
        var mutations = policy.onFault("tenant-1", faultEvent, graph, null);
        assertThat(mutations).isNotEmpty();
    }
}
