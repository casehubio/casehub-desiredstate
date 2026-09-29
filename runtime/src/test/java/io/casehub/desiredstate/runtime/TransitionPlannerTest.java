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
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertEquals(0, plan.flatRemovals().size());
        assertEquals(0, plan.flatAdditions().size());
    }

    @Test
    void allAbsent_producesAdditionsOnly() {
        DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);
        DesiredNode nodeB = new DesiredNode(NodeId.of("b"), new TestSpec("B"), HumanGating.NONE);

        DesiredStateGraph desired = factory.of(List.of(nodeA, nodeB), List.of());
        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.ABSENT,
            NodeId.of("b"), NodeStatus.ABSENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.flatRemovals().size());
        assertEquals(2, plan.flatAdditions().size());
        assertTrue(plan.flatAdditions().stream().allMatch(s -> s.action() == StepAction.PROVISION));
    }

    @Test
    void presentButNotDesired_producesRemovalsOnly() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of(
            NodeId.of("orphan1"), NodeStatus.PRESENT,
            NodeId.of("orphan2"), NodeStatus.PRESENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(2, plan.flatRemovals().size());
        assertEquals(0, plan.flatAdditions().size());
        assertTrue(plan.flatRemovals().stream().allMatch(s -> s.action() == StepAction.DEPROVISION));
    }

    @Test
    void additionsOrdering_rootsFirst() {
        // A → B → C (C depends on B, B depends on A)
        DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);
        DesiredNode nodeB = new DesiredNode(NodeId.of("b"), new TestSpec("B"), HumanGating.NONE);
        DesiredNode nodeC = new DesiredNode(NodeId.of("c"), new TestSpec("C"), HumanGating.NONE);

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

        assertEquals(3, plan.flatAdditions().size());

        // Extract order
        List<NodeId> order = plan.flatAdditions().stream()
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
        DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);
        DesiredNode nodeB = new DesiredNode(NodeId.of("b"), new TestSpec("B"), HumanGating.NONE);
        DesiredNode nodeC = new DesiredNode(NodeId.of("c"), new TestSpec("C"), HumanGating.NONE);
        DesiredNode nodeD = new DesiredNode(NodeId.of("d"), new TestSpec("D"), HumanGating.NONE);

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

        List<NodeId> order = plan.flatAdditions().stream()
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
        DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);

        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.ABSENT,
            NodeId.of("orphan"), NodeStatus.PRESENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(1, plan.flatRemovals().size());
        assertEquals(1, plan.flatAdditions().size());

        assertEquals(NodeId.of("orphan"), plan.flatRemovals().get(0).node().id());
        assertEquals(NodeId.of("a"), plan.flatAdditions().get(0).node().id());
    }

    @Test
    void driftedInDesired_producesAddition() {
        DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);

        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.DRIFTED
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.flatRemovals().size());
        assertEquals(1, plan.flatAdditions().size());
        assertEquals(NodeId.of("a"), plan.flatAdditions().get(0).node().id());
        assertEquals(StepAction.PROVISION, plan.flatAdditions().get(0).action());
    }

    @Test
    void driftedOrphan_producesRemoval() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of(
            NodeId.of("orphan"), NodeStatus.DRIFTED
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(1, plan.flatRemovals().size());
        assertEquals(0, plan.flatAdditions().size());
        assertEquals(NodeId.of("orphan"), plan.flatRemovals().get(0).node().id());
        assertEquals(StepAction.DEPROVISION, plan.flatRemovals().get(0).action());
    }

    @Test
    void driftedWithDependencies_respectsTopologicalOrder() {
        DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);
        DesiredNode nodeB = new DesiredNode(NodeId.of("b"), new TestSpec("B"), HumanGating.NONE);
        DesiredNode nodeC = new DesiredNode(NodeId.of("c"), new TestSpec("C"), HumanGating.NONE);

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

        assertEquals(3, plan.flatAdditions().size());

        List<NodeId> order = plan.flatAdditions().stream()
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
        DesiredNode present = new DesiredNode(NodeId.of("present"), new TestSpec("P"), HumanGating.NONE);
        DesiredNode absent = new DesiredNode(NodeId.of("absent"), new TestSpec("A"), HumanGating.NONE);
        DesiredNode drifted = new DesiredNode(NodeId.of("drifted"), new TestSpec("D"), HumanGating.NONE);
        DesiredNode unknown = new DesiredNode(NodeId.of("unknown"), new TestSpec("U"), HumanGating.NONE);

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

        List<NodeId> addedIds = plan.flatAdditions().stream()
            .map(step -> step.node().id())
            .toList();
        List<NodeId> removedIds = plan.flatRemovals().stream()
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
        DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);

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

        assertEquals(0, plan.flatRemovals().size());
        assertEquals(0, plan.flatAdditions().size());
    }

    @Test
    void absentOrphan_noAction() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of(
            NodeId.of("orphan"), NodeStatus.ABSENT
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.flatRemovals().size());
        assertEquals(0, plan.flatAdditions().size());
    }

    @Test
    void driftedWithPresentDependency_provisionsDriftedOnly() {
        // B depends on A. A is PRESENT (fine), B is DRIFTED (needs re-provision).
        // Only B should be in additions, with zero in-degree (A is outside toAdd).
        DesiredNode nodeA = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);
        DesiredNode nodeB = new DesiredNode(NodeId.of("b"), new TestSpec("B"), HumanGating.NONE);

        DesiredStateGraph desired = factory.of(
            List.of(nodeA, nodeB),
            List.of(new Dependency(NodeId.of("b"), NodeId.of("a")))
        );

        ActualState actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.PRESENT,
            NodeId.of("b"), NodeStatus.DRIFTED
        ));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(0, plan.flatRemovals().size());
        assertEquals(1, plan.flatAdditions().size());
        assertEquals(NodeId.of("b"), plan.flatAdditions().get(0).node().id());
    }


    @Test
    void orphanRemoval_usesPreviousDesiredSpec() {
        NodeSpec    orphanSpec = new TestSpec("orphan-data");
        DesiredNode orphanNode = new DesiredNode(NodeId.of("orphan"), orphanSpec, HumanGating.NONE);
        DesiredNode nodeA      = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);

        DesiredStateGraph previousDesired = factory.of(List.of(nodeA, orphanNode), List.of());
        DesiredStateGraph desired         = factory.of(List.of(nodeA), List.of());
        ActualState actual = new ActualState(Map.of(
                NodeId.of("a"), NodeStatus.PRESENT,
                NodeId.of("orphan"), NodeStatus.PRESENT
                                                   ));

        TransitionPlan plan = planner.plan(desired, actual, previousDesired);

        assertEquals(1, plan.flatRemovals().size());
        assertEquals(NodeId.of("orphan"), plan.flatRemovals().get(0).node().id());
        assertSame(orphanSpec, plan.flatRemovals().get(0).node().spec());
    }

    @Test
    void orphanRemoval_fallsBackToUnknownSpec_whenNoPreviousDesired() {
        DesiredStateGraph desired = factory.empty();
        ActualState actual = new ActualState(Map.of(
                NodeId.of("orphan"), NodeStatus.PRESENT
                                                   ));

        TransitionPlan plan = planner.plan(desired, actual, null);

        assertEquals(1, plan.flatRemovals().size());
        assertEquals(NodeType.of("unknown"), plan.flatRemovals().get(0).node().type());
    }

    @Test
    void orphanRemoval_fallsBackToUnknownSpec_whenOrphanNotInPreviousDesired() {
        DesiredNode       nodeA           = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);
        DesiredStateGraph previousDesired = factory.of(List.of(nodeA), List.of());
        DesiredStateGraph desired         = factory.of(List.of(nodeA), List.of());
        ActualState actual = new ActualState(Map.of(
                NodeId.of("a"), NodeStatus.PRESENT,
                NodeId.of("never-desired"), NodeStatus.PRESENT
                                                   ));

        TransitionPlan plan = planner.plan(desired, actual, previousDesired);

        assertEquals(1, plan.flatRemovals().size());
        assertEquals(NodeId.of("never-desired"), plan.flatRemovals().get(0).node().id());
        assertEquals(NodeType.of("unknown"), plan.flatRemovals().get(0).node().type());
    }

    @Test
    void orphanRemoval_preservesHumanGatingFromPreviousDesired() {
        DesiredNode       gatedNode       = new DesiredNode(NodeId.of("gated"), new TestSpec("G"), HumanGating.DEPROVISION_ONLY);
        DesiredStateGraph previousDesired = factory.of(List.of(gatedNode), List.of());
        DesiredStateGraph desired         = factory.empty();
        ActualState actual = new ActualState(Map.of(
                NodeId.of("gated"), NodeStatus.PRESENT
                                                   ));

        TransitionPlan plan = planner.plan(desired, actual, previousDesired);

        assertEquals(1, plan.flatRemovals().size());
        assertEquals(HumanGating.DEPROVISION_ONLY, plan.flatRemovals().get(0).node().humanGating());
    }

    @Test
    void plan_beforeGraphIsPreviousDesired_afterGraphIsDesired() {
        DesiredNode orphanNode = new DesiredNode(NodeId.of("orphan"), new TestSpec("O"), HumanGating.NONE);
        DesiredNode nodeA      = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);

        DesiredStateGraph previousDesired = factory.of(List.of(nodeA, orphanNode), List.of());
        DesiredStateGraph desired         = factory.of(List.of(nodeA), List.of());
        ActualState actual = new ActualState(Map.of(
                NodeId.of("a"), NodeStatus.PRESENT,
                NodeId.of("orphan"), NodeStatus.PRESENT
                                                   ));

        TransitionPlan plan = planner.plan(desired, actual, previousDesired);

        assertSame(previousDesired, plan.before());
        assertSame(desired, plan.after());
    }


    @Test
    void layeredAdditions_groupsNodesByTopologicalDepth() {
        // A -> B -> C  (A is root, C is leaf)
        DesiredNode nodeA = new DesiredNode(NodeId.of("A"), new TestSpec("A"), HumanGating.NONE);
        DesiredNode nodeB = new DesiredNode(NodeId.of("B"), new TestSpec("B"), HumanGating.NONE);
        DesiredNode nodeC = new DesiredNode(NodeId.of("C"), new TestSpec("C"), HumanGating.NONE);

        DesiredStateGraph graph = factory.of(
                List.of(nodeA, nodeB, nodeC),
                List.of(new Dependency(NodeId.of("B"), NodeId.of("A")),
                        new Dependency(NodeId.of("C"), NodeId.of("B"))));
        ActualState actual = new ActualState(Map.of());

        TransitionPlan plan = planner.plan(graph, actual);

        assertEquals(3, plan.additions().size(), "3 layers");
        assertEquals(1, plan.additions().get(0).size(), "Layer 0: root");
        assertEquals(NodeId.of("A"), plan.additions().get(0).get(0).node().id());
        assertEquals(1, plan.additions().get(1).size(), "Layer 1: middle");
        assertEquals(NodeId.of("B"), plan.additions().get(1).get(0).node().id());
        assertEquals(1, plan.additions().get(2).size(), "Layer 2: leaf");
        assertEquals(NodeId.of("C"), plan.additions().get(2).get(0).node().id());
        assertEquals(3, plan.flatAdditions().size(), "flat view: 3 nodes total");
    }

    @Test
    void layeredAdditions_independentNodesInSameLayer() {
        // A and B are independent roots, C depends on both
        DesiredNode nodeA = new DesiredNode(NodeId.of("A"), new TestSpec("A"), HumanGating.NONE);
        DesiredNode nodeB = new DesiredNode(NodeId.of("B"), new TestSpec("B"), HumanGating.NONE);
        DesiredNode nodeC = new DesiredNode(NodeId.of("C"), new TestSpec("C"), HumanGating.NONE);

        DesiredStateGraph graph = factory.of(
                List.of(nodeA, nodeB, nodeC),
                List.of(new Dependency(NodeId.of("C"), NodeId.of("A")),
                        new Dependency(NodeId.of("C"), NodeId.of("B"))));
        ActualState actual = new ActualState(Map.of());

        TransitionPlan plan = planner.plan(graph, actual);

        assertEquals(2, plan.additions().size(), "2 layers");
        assertEquals(2, plan.additions().get(0).size(), "Layer 0: A and B");
        assertEquals(1, plan.additions().get(1).size(), "Layer 1: C");
        assertEquals(NodeId.of("C"), plan.additions().get(1).get(0).node().id());
    }

    // Helper test spec
    record TestSpec(String value) implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }

// --- Suspend/Resume decision matrix tests ---

    @Test
    void presentWithSuspendedTarget_producesSuspension() {
        DesiredNode       node    = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), HumanGating.NONE, null, io.casehub.desiredstate.api.TargetStatus.SUSPENDED);
        DesiredStateGraph desired = factory.of(List.of(node), List.of());
        ActualState       actual  = new ActualState(Map.of(NodeId.of("n1"), NodeStatus.PRESENT));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> true);

        assertEquals(1, plan.flatSuspensions().size());
        assertEquals(StepAction.SUSPEND, plan.flatSuspensions().get(0).action());
        assertTrue(plan.flatAdditions().isEmpty());
        assertTrue(plan.flatRemovals().isEmpty());
    }

    @Test
    void suspendedWithActiveTarget_producesResumption() {
        DesiredNode       node    = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), HumanGating.NONE);
        DesiredStateGraph desired = factory.of(List.of(node), List.of());
        ActualState       actual  = new ActualState(Map.of(NodeId.of("n1"), NodeStatus.SUSPENDED));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> true);

        assertEquals(1, plan.flatResumptions().size());
        assertEquals(StepAction.RESUME, plan.flatResumptions().get(0).action());
        assertTrue(plan.flatAdditions().isEmpty());
        assertTrue(plan.flatSuspensions().isEmpty());
    }

    @Test
    void absentWithSuspendedTarget_producesResumptionAttempt() {
        DesiredNode       node    = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), HumanGating.NONE, null, io.casehub.desiredstate.api.TargetStatus.SUSPENDED);
        DesiredStateGraph desired = factory.of(List.of(node), List.of());
        ActualState       actual  = new ActualState(Map.of(NodeId.of("n1"), NodeStatus.ABSENT));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> true);

        assertEquals(1, plan.flatResumptions().size());
        assertEquals(StepAction.RESUME, plan.flatResumptions().get(0).action());
    }

    @Test
    void suspendedWithSuspendedTarget_producesNoOp() {
        DesiredNode       node    = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), HumanGating.NONE, null, io.casehub.desiredstate.api.TargetStatus.SUSPENDED);
        DesiredStateGraph desired = factory.of(List.of(node), List.of());
        ActualState       actual  = new ActualState(Map.of(NodeId.of("n1"), NodeStatus.SUSPENDED));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> true);

        assertTrue(plan.isEmpty());
    }

    @Test
    void driftedWithSuspendedTarget_producesSuspension() {
        DesiredNode       node    = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), HumanGating.NONE, null, io.casehub.desiredstate.api.TargetStatus.SUSPENDED);
        DesiredStateGraph desired = factory.of(List.of(node), List.of());
        ActualState       actual  = new ActualState(Map.of(NodeId.of("n1"), NodeStatus.DRIFTED));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> true);

        assertEquals(1, plan.flatSuspensions().size());
    }

    @Test
    void suspendedNotInGraph_producesDeprovision() {
        DesiredStateGraph desired = factory.empty();
        ActualState       actual  = new ActualState(Map.of(NodeId.of("orphan"), NodeStatus.SUSPENDED));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(1, plan.flatRemovals().size());
        assertEquals(StepAction.DEPROVISION, plan.flatRemovals().get(0).action());
    }

    @Test
    void suspendFallsBackToDeprovisionWhenNotSupported() {
        DesiredNode       node    = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), HumanGating.NONE, null, io.casehub.desiredstate.api.TargetStatus.SUSPENDED);
        DesiredStateGraph desired = factory.of(List.of(node), List.of());
        ActualState       actual  = new ActualState(Map.of(NodeId.of("n1"), NodeStatus.PRESENT));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> false);

        assertTrue(plan.flatSuspensions().isEmpty());
        assertEquals(1, plan.flatRemovals().size());
        assertEquals(StepAction.DEPROVISION, plan.flatRemovals().get(0).action());
    }

    @Test
    void resumeFallsBackToProvisionWhenNotSupported() {
        DesiredNode       node    = new DesiredNode(NodeId.of("n1"), new TestSpec("v"), HumanGating.NONE);
        DesiredStateGraph desired = factory.of(List.of(node), List.of());
        ActualState       actual  = new ActualState(Map.of(NodeId.of("n1"), NodeStatus.SUSPENDED));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> false);

        assertTrue(plan.flatResumptions().isEmpty());
        assertEquals(1, plan.flatAdditions().size());
        assertEquals(StepAction.PROVISION, plan.flatAdditions().get(0).action());
    }

    @Test
    void suspensionsOrderedLeavesFirst() {
        DesiredNode       root    = new DesiredNode(NodeId.of("root"), new TestSpec("R"), HumanGating.NONE, null, io.casehub.desiredstate.api.TargetStatus.SUSPENDED);
        DesiredNode       leaf    = new DesiredNode(NodeId.of("leaf"), new TestSpec("L"), HumanGating.NONE, null, io.casehub.desiredstate.api.TargetStatus.SUSPENDED);
        DesiredStateGraph desired = factory.of(List.of(root, leaf), List.of(new Dependency(NodeId.of("leaf"), NodeId.of("root"))));
        ActualState actual = new ActualState(Map.of(
                NodeId.of("root"), NodeStatus.PRESENT,
                NodeId.of("leaf"), NodeStatus.PRESENT));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> true);

        assertEquals(2, plan.flatSuspensions().size());
        assertEquals(NodeId.of("leaf"), plan.flatSuspensions().get(0).node().id());
        assertEquals(NodeId.of("root"), plan.flatSuspensions().get(1).node().id());
    }

    @Test
    void resumptionsOrderedRootsFirst() {
        DesiredNode       root    = new DesiredNode(NodeId.of("root"), new TestSpec("R"), HumanGating.NONE);
        DesiredNode       leaf    = new DesiredNode(NodeId.of("leaf"), new TestSpec("L"), HumanGating.NONE);
        DesiredStateGraph desired = factory.of(List.of(root, leaf), List.of(new Dependency(NodeId.of("leaf"), NodeId.of("root"))));
        ActualState actual = new ActualState(Map.of(
                NodeId.of("root"), NodeStatus.SUSPENDED,
                NodeId.of("leaf"), NodeStatus.SUSPENDED));

        TransitionPlan plan = planner.plan(desired, actual, null, type -> true);

        assertEquals(2, plan.flatResumptions().size());
        assertEquals(NodeId.of("root"), plan.flatResumptions().get(0).node().id());
        assertEquals(NodeId.of("leaf"), plan.flatResumptions().get(1).node().id());
    }

    @Test
    void existingBehaviorUnchangedWithDefaultPredicate() {
        DesiredNode       nodeA   = new DesiredNode(NodeId.of("a"), new TestSpec("A"), HumanGating.NONE);
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        ActualState       actual  = new ActualState(Map.of(NodeId.of("a"), NodeStatus.ABSENT));

        TransitionPlan plan = planner.plan(desired, actual);

        assertEquals(1, plan.flatAdditions().size());
        assertTrue(plan.flatSuspensions().isEmpty());
        assertTrue(plan.flatResumptions().isEmpty());
    }
}
