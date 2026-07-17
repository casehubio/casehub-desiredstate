package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.TransitionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionPlannerTest {

    private TransitionPlanner planner;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        planner = new TransitionPlanner();
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void emptyDiff_producesEmptyPlan() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of());

        TransitionPlan plan = planner.plan(desired, actual);

        assertTrue(plan.isEmpty());
        assertEquals(0, plan.removals().size());
        assertEquals(0, plan.additions().size());
    }

    @Test
    void allAbsent_producesAdditionsOnly() {
        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );
        DesiredNode nodeB = new DesiredNode(
            NodeId.of("b"), NodeType.of("test"), new TestSpec("B"), HumanGating.NONE
        );

        DesiredStateGraph desired = factory.of(List.of(nodeA, nodeB), List.of());
        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.ABSENT,
            NodeId.of("b"), NodeStatus.ABSENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.removals().size());
        assertEquals(2, plan.additions().size());
        assertTrue(plan.additions().stream().allMatch(s -> s.action() == StepAction.PROVISION));
    }

    @Test
    void presentButNotDesired_producesRemovalsOnly() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of(
            NodeId.of("orphan1"), NodeStatus.PRESENT,
            NodeId.of("orphan2"), NodeStatus.PRESENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(2, plan.removals().size());
        assertEquals(0, plan.additions().size());
        assertTrue(plan.removals().stream().allMatch(s -> s.action() == StepAction.DEPROVISION));
    }

    @Test
    void additionsOrdering_rootsFirst() {
        // A → B → C (C depends on B, B depends on A)
        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );
        DesiredNode nodeB = new DesiredNode(
            NodeId.of("b"), NodeType.of("test"), new TestSpec("B"), HumanGating.NONE
        );
        DesiredNode nodeC = new DesiredNode(
            NodeId.of("c"), NodeType.of("test"), new TestSpec("C"), HumanGating.NONE
        );

        Dependency bDependsOnA = new Dependency(NodeId.of("b"), NodeId.of("a"));
        Dependency cDependsOnB = new Dependency(NodeId.of("c"), NodeId.of("b"));

        DesiredStateGraph desired = factory.of(
            List.of(nodeA, nodeB, nodeC),
            List.of(bDependsOnA, cDependsOnB)
        );

        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.ABSENT,
            NodeId.of("b"), NodeStatus.ABSENT,
            NodeId.of("c"), NodeStatus.ABSENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(3, plan.additions().size());

        // Extract order
        List<NodeId> order = plan.additions().stream()
            .map(step -> step.node().id())
            .toList();

        // A must come before B, B must come before C
        int idxA = order.indexOf(NodeId.of("a"));
        int idxB = order.indexOf(NodeId.of("b"));
        int idxC = order.indexOf(NodeId.of("c"));

        assertTrue(idxA < idxB, "A should come before B");
        assertTrue(idxB < idxC, "B should come before C");
    }

    @Test
    void additionsOrdering_diamondDependency() {
        // Diamond: A, B depends on A, C depends on A, D depends on B and C
        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );
        DesiredNode nodeB = new DesiredNode(
            NodeId.of("b"), NodeType.of("test"), new TestSpec("B"), HumanGating.NONE
        );
        DesiredNode nodeC = new DesiredNode(
            NodeId.of("c"), NodeType.of("test"), new TestSpec("C"), HumanGating.NONE
        );
        DesiredNode nodeD = new DesiredNode(
            NodeId.of("d"), NodeType.of("test"), new TestSpec("D"), HumanGating.NONE
        );

        DesiredStateGraph desired = factory.of(
            List.of(nodeA, nodeB, nodeC, nodeD),
            List.of(
                new Dependency(NodeId.of("b"), NodeId.of("a")),
                new Dependency(NodeId.of("c"), NodeId.of("a")),
                new Dependency(NodeId.of("d"), NodeId.of("b")),
                new Dependency(NodeId.of("d"), NodeId.of("c"))
            )
        );

        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.ABSENT,
            NodeId.of("b"), NodeStatus.ABSENT,
            NodeId.of("c"), NodeStatus.ABSENT,
            NodeId.of("d"), NodeStatus.ABSENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        List<NodeId> order = plan.additions().stream()
            .map(step -> step.node().id())
            .toList();

        int idxA = order.indexOf(NodeId.of("a"));
        int idxB = order.indexOf(NodeId.of("b"));
        int idxC = order.indexOf(NodeId.of("c"));
        int idxD = order.indexOf(NodeId.of("d"));

        assertTrue(idxA < idxB, "A before B");
        assertTrue(idxA < idxC, "A before C");
        assertTrue(idxB < idxD, "B before D");
        assertTrue(idxC < idxD, "C before D");
    }

    @Test
    void mixedChanges_separatesAdditionsAndRemovals() {
        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );

        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.ABSENT,
            NodeId.of("orphan"), NodeStatus.PRESENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(1, plan.removals().size());
        assertEquals(1, plan.additions().size());

        assertEquals(NodeId.of("orphan"), plan.removals().get(0).node().id());
        assertEquals(NodeId.of("a"), plan.additions().get(0).node().id());
    }

    @Test
    void driftedInDesired_producesAddition() {
        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );

        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.DRIFTED
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.removals().size());
        assertEquals(1, plan.additions().size());
        assertEquals(NodeId.of("a"), plan.additions().get(0).node().id());
        assertEquals(StepAction.PROVISION, plan.additions().get(0).action());
    }

    @Test
    void driftedOrphan_producesRemoval() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of(
            NodeId.of("orphan"), NodeStatus.DRIFTED
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(1, plan.removals().size());
        assertEquals(0, plan.additions().size());
        assertEquals(NodeId.of("orphan"), plan.removals().get(0).node().id());
        assertEquals(StepAction.DEPROVISION, plan.removals().get(0).action());
    }

    @Test
    void driftedWithDependencies_respectsTopologicalOrder() {
        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );
        DesiredNode nodeB = new DesiredNode(
            NodeId.of("b"), NodeType.of("test"), new TestSpec("B"), HumanGating.NONE
        );
        DesiredNode nodeC = new DesiredNode(
            NodeId.of("c"), NodeType.of("test"), new TestSpec("C"), HumanGating.NONE
        );

        DesiredStateGraph desired = factory.of(
            List.of(nodeA, nodeB, nodeC),
            List.of(
                new Dependency(NodeId.of("b"), NodeId.of("a")),
                new Dependency(NodeId.of("c"), NodeId.of("b"))
            )
        );

        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.DRIFTED,
            NodeId.of("b"), NodeStatus.DRIFTED,
            NodeId.of("c"), NodeStatus.DRIFTED
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(3, plan.additions().size());

        List<NodeId> order = plan.additions().stream()
            .map(step -> step.node().id())
            .toList();

        int idxA = order.indexOf(NodeId.of("a"));
        int idxB = order.indexOf(NodeId.of("b"));
        int idxC = order.indexOf(NodeId.of("c"));

        assertTrue(idxA < idxB, "A should come before B");
        assertTrue(idxB < idxC, "B should come before C");
    }

    @Test
    void mixedStatuses_classifiesCorrectly() {
        DesiredNode present = new DesiredNode(
            NodeId.of("present"), NodeType.of("test"), new TestSpec("P"), HumanGating.NONE
        );
        DesiredNode absent = new DesiredNode(
            NodeId.of("absent"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );
        DesiredNode drifted = new DesiredNode(
            NodeId.of("drifted"), NodeType.of("test"), new TestSpec("D"), HumanGating.NONE
        );
        DesiredNode unknown = new DesiredNode(
            NodeId.of("unknown"), NodeType.of("test"), new TestSpec("U"), HumanGating.NONE
        );

        DesiredStateGraph desired = factory.of(
            List.of(present, absent, drifted, unknown), List.of()
        );

        ActualState actual = new ActualState(Map.of(
            NodeId.of("present"), NodeStatus.PRESENT,
            NodeId.of("absent"), NodeStatus.ABSENT,
            NodeId.of("drifted"), NodeStatus.DRIFTED,
            NodeId.of("unknown"), NodeStatus.UNKNOWN,
            NodeId.of("present-orphan"), NodeStatus.PRESENT,
            NodeId.of("drifted-orphan"), NodeStatus.DRIFTED,
            NodeId.of("absent-orphan"), NodeStatus.ABSENT,
            NodeId.of("unknown-orphan"), NodeStatus.UNKNOWN
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        List<NodeId> addedIds = plan.additions().stream()
            .map(step -> step.node().id())
            .toList();
        List<NodeId> removedIds = plan.removals().stream()
            .map(step -> step.node().id())
            .toList();

        // In desired: absent, drifted, unknown → provision. present → no action.
        assertEquals(3, addedIds.size());
        assertTrue(addedIds.contains(NodeId.of("absent")));
        assertTrue(addedIds.contains(NodeId.of("drifted")));
        assertTrue(addedIds.contains(NodeId.of("unknown")));
        assertFalse(addedIds.contains(NodeId.of("present")));

        // Orphans: present-orphan, drifted-orphan → deprovision. absent-orphan, unknown-orphan → no action.
        assertEquals(2, removedIds.size());
        assertTrue(removedIds.contains(NodeId.of("present-orphan")));
        assertTrue(removedIds.contains(NodeId.of("drifted-orphan")));
        assertFalse(removedIds.contains(NodeId.of("absent-orphan")));
        assertFalse(removedIds.contains(NodeId.of("unknown-orphan")));
    }

    @Test
    void presentInDesired_noAction() {
        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );

        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.PRESENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertTrue(plan.isEmpty());
    }

    @Test
    void unknownOrphan_noAction() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of(
            NodeId.of("orphan"), NodeStatus.UNKNOWN
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.removals().size());
        assertEquals(0, plan.additions().size());
    }

    @Test
    void absentOrphan_noAction() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of(
            NodeId.of("orphan"), NodeStatus.ABSENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.removals().size());
        assertEquals(0, plan.additions().size());
    }

    @Test
    void driftedWithPresentDependency_provisionsDriftedOnly() {
        // B depends on A. A is PRESENT (fine), B is DRIFTED (needs re-provision).
        // Only B should be in additions, with zero in-degree (A is outside toAdd).
        DesiredNode nodeA = new DesiredNode(
            NodeId.of("a"), NodeType.of("test"), new TestSpec("A"), HumanGating.NONE
        );
        DesiredNode nodeB = new DesiredNode(
            NodeId.of("b"), NodeType.of("test"), new TestSpec("B"), HumanGating.NONE
        );

        DesiredStateGraph desired = factory.of(
            List.of(nodeA, nodeB),
            List.of(new Dependency(NodeId.of("b"), NodeId.of("a")))
        );

        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.PRESENT,
            NodeId.of("b"), NodeStatus.DRIFTED
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.removals().size());
        assertEquals(1, plan.additions().size());
        assertEquals(NodeId.of("b"), plan.additions().get(0).node().id());
    }

    // Helper test spec
    record TestSpec(String value) implements NodeSpec {}
}
