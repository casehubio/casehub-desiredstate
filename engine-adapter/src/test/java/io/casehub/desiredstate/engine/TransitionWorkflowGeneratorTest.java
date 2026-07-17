package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StepAction;
import io.serverlessworkflow.api.types.CallFunction;
import io.serverlessworkflow.api.types.TaskItem;
import io.serverlessworkflow.api.types.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransitionWorkflowGeneratorTest {

    private TransitionWorkflowGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new TransitionWorkflowGenerator();
    }

    @Test
    void emptyStepsProduceEmptyWorkflow() {
        Workflow workflow = generator.generate(List.of(), "ns", "empty", "1.0.0", "exec-1");

        assertThat(workflow.getDocument()).isNotNull();
        assertThat(workflow.getDocument().getNamespace()).isEqualTo("ns");
        assertThat(workflow.getDocument().getName()).isEqualTo("empty");
        assertThat(workflow.getDocument().getVersion()).isEqualTo("1.0.0");
        assertThat(workflow.getDo()).isEmpty();
    }

    @Test
    void singleProvisionStepProducesOneTask() {
        OrderedStep step = new OrderedStep(
            node("web-server", "vm"),
            StepAction.PROVISION
        );

        Workflow workflow = generator.generate(List.of(step), "io.casehub.desiredstate", "grow-phase", "1.0.0", "exec-abc");

        assertThat(workflow.getDo()).hasSize(1);
        TaskItem taskItem = workflow.getDo().get(0);
        assertThat(taskItem.getName()).isEqualTo("step-0-provision-web-server");

        CallFunction callFunction = taskItem.getTask().getCallTask().getCallFunction();
        assertThat(callFunction.getCall()).isEqualTo("desiredstate:dispatch");

        Map<String, Object> args = callFunction.getWith().getAdditionalProperties();
        assertThat(args).containsEntry("executionId", "exec-abc");
        assertThat(args).containsEntry("nodeId", "web-server");
        assertThat(args).containsEntry("nodeType", "vm");
        assertThat(args).containsEntry("action", "PROVISION");
    }

    @Test
    void multipleStepsProduceSequentialTasks() {
        List<OrderedStep> steps = List.of(
            new OrderedStep(node("db", "database"), StepAction.PROVISION),
            new OrderedStep(node("app", "service"), StepAction.PROVISION),
            new OrderedStep(node("lb", "loadbalancer"), StepAction.PROVISION)
        );

        Workflow workflow = generator.generate(steps, "ns", "grow", "2.0.0", "exec-test");

        assertThat(workflow.getDo()).hasSize(3);
        assertThat(workflow.getDo().get(0).getName()).isEqualTo("step-0-provision-db");
        assertThat(workflow.getDo().get(1).getName()).isEqualTo("step-1-provision-app");
        assertThat(workflow.getDo().get(2).getName()).isEqualTo("step-2-provision-lb");
    }

    @Test
    void deprovisionStepsUseCorrectAction() {
        OrderedStep step = new OrderedStep(
            node("old-server", "vm"),
            StepAction.DEPROVISION
        );

        Workflow workflow = generator.generate(List.of(step), "ns", "prune", "1.0.0", "exec-test");

        TaskItem taskItem = workflow.getDo().get(0);
        assertThat(taskItem.getName()).contains("deprovision");

        Map<String, Object> args = taskItem.getTask().getCallTask()
            .getCallFunction().getWith().getAdditionalProperties();
        assertThat(args).containsEntry("action", "DEPROVISION");
    }

    @Test
    void documentMetadataIsPopulated() {
        Workflow workflow = generator.generate(
            List.of(new OrderedStep(node("n1", "t1"), StepAction.PROVISION)),
            "io.casehub.test",
            "test-workflow",
            "3.1.0",
            "exec-test"
        );

        assertThat(workflow.getDocument().getDsl()).isEqualTo(TransitionWorkflowGenerator.DSL_VERSION);
        assertThat(workflow.getDocument().getTitle()).isEqualTo("test-workflow transition workflow");
        assertThat(workflow.getDocument().getSummary()).contains("1 steps");
    }

    private static DesiredNode node(String id, String type) {
        return new DesiredNode(NodeId.of(id), NodeType.of(type), new TestNodeSpec(), HumanGating.NONE);
    }

    /** Minimal NodeSpec for tests. */
    private record TestNodeSpec() implements NodeSpec {}
}
