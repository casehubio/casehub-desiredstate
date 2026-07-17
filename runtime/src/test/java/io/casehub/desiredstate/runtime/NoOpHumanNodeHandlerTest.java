package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.StepOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class NoOpHumanNodeHandlerTest {

    record TestSpec(String value) implements NodeSpec {}

    @Test
    void returnsSkippedWithConfigurationMessage() {
        NoOpHumanNodeHandler handler = new NoOpHumanNodeHandler();
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v"), HumanGating.ALL
        );
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(node), List.of());
        ProvisionContext context = new ProvisionContext("tenant1", graph);

        StepOutcome outcome = handler.onProvision(node, context);

        assertInstanceOf(StepOutcome.Skipped.class, outcome);
        assertEquals("requires human — no HumanNodeHandler configured",
            ((StepOutcome.Skipped) outcome).reason());
    }

    @Test
    void deprovision_returnsSkippedWithConfigurationMessage() {
        NoOpHumanNodeHandler handler = new NoOpHumanNodeHandler();
        DesiredNode node = new DesiredNode(
                NodeId.of("n1"), NodeType.of("test"), new TestSpec("v"), HumanGating.ALL
        );
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
                                          .of(List.of(node), List.of());
        DeprovisionContext context = new DeprovisionContext("tenant1", graph);

        StepOutcome outcome = handler.onDeprovision(node, context);

        assertInstanceOf(StepOutcome.Skipped.class, outcome);
        assertEquals("requires human — no HumanNodeHandler configured",
                     ((StepOutcome.Skipped) outcome).reason());
    }

}
