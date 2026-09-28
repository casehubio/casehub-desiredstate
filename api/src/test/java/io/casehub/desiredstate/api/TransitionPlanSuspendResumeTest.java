package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionPlanSuspendResumeTest {
    private static final NodeSpec SPEC = new NodeSpec() {
        @Override
        public NodeType nodeType() {return NodeType.of("test");}
    };

    @Test
    void fourArgConstructorDefaultsToEmptyLists() {
        TransitionPlan plan = new TransitionPlan(List.of(), List.of(), STUB, STUB);
        assertTrue(plan.suspensions().isEmpty());
        assertTrue(plan.resumptions().isEmpty());
        assertTrue(plan.isEmpty());
    }

    @Test
    void sixArgConstructorAcceptsSuspensionsAndResumptions() {
        TransitionPlan plan = new TransitionPlan(
                List.of(), List.of(), List.of(), List.of(), STUB, STUB);
        assertTrue(plan.isEmpty());
    }

    @Test
    void isEmptyConsidersSuspensionsAndResumptions() {
        DesiredNode node = new DesiredNode(NodeId.of("n1"), SPEC, HumanGating.NONE);
        OrderedStep step = new OrderedStep(node, StepAction.SUSPEND);
        TransitionPlan plan = new TransitionPlan(
                List.of(), List.of(step), List.of(), List.of(), STUB, STUB);
        assertFalse(plan.isEmpty());
    }

    @Test
    void listsAreImmutable() {
        DesiredNode node        = new DesiredNode(NodeId.of("n1"), SPEC, HumanGating.NONE);
        var         suspensions = new java.util.ArrayList<>(List.of(new OrderedStep(node, StepAction.SUSPEND)));
        TransitionPlan plan = new TransitionPlan(
                List.of(), suspensions, List.of(), List.of(), STUB, STUB);
        assertThrows(UnsupportedOperationException.class, () -> plan.suspensions().add(null));
    }

    private static final DesiredStateGraph STUB = new DesiredStateGraph() {
        @Override
        public java.util.Map<NodeId, DesiredNode> nodes()                   {return java.util.Map.of();}

        @Override
        public java.util.Set<Dependency> dependencies()                     {return java.util.Set.of();}

        @Override
        public java.util.Set<NodeId> dependenciesOf(NodeId n)               {return java.util.Set.of();}

        @Override
        public java.util.Set<NodeId> dependentsOf(NodeId n)                 {return java.util.Set.of();}

        @Override
        public java.util.Set<NodeId> roots()                                {return java.util.Set.of();}

        @Override
        public java.util.Set<NodeId> leaves()                               {return java.util.Set.of();}

        @Override
        public int version()                                                {return 0;}

        @Override
        public boolean isEmpty()                                            {return true;}

        @Override
        public DesiredStateGraph withNode(DesiredNode n)                    {return this;}

        @Override
        public DesiredStateGraph withoutNode(NodeId id)                     {return this;}

        @Override
        public DesiredStateGraph withDependency(Dependency d)               {return this;}

        @Override
        public DesiredStateGraph withoutDependency(Dependency d)            {return this;}

        @Override
        public DesiredStateGraph withMutation(GraphMutation<DesiredNode> m) {return this;}

        @Override
        public DesiredStateGraph overlay(DesiredStateGraph o)               {return this;}

        @Override
        public DesiredStateGraph connect(DesiredStateGraph o)               {return this;}
    };
}
