package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.OrderingConstraint;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.TargetStatus;
import io.casehub.desiredstate.api.TransitionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionPlannerTest {

    private static final NodeType SENSOR    = NodeType.of("sensor");
    private static final NodeType GATEWAY   = NodeType.of("gateway");
    private static final NodeType PROCESSOR = NodeType.of("processor");

    private TransitionPlanner planner;
    private DefaultDesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        planner = new TransitionPlanner();
        factory = new DefaultDesiredStateGraphFactory();
    }

    private static DesiredNode node(String id, NodeType type) {
        return new DesiredNode(NodeId.of(id), spec(type), HumanGating.NONE);
    }

    private static DesiredNode node(String id, NodeType type, TargetStatus target) {
        return new DesiredNode(NodeId.of(id), spec(type), HumanGating.NONE, null, target);
    }

    private static NodeSpec spec(NodeType type) {
        return new NodeSpec() {
            @Override
            public NodeType nodeType() { return type; }
        };
    }

    private static Set<NodeId> nodeIds(List<OrderedStep> steps) {
        return steps.stream().map(s -> s.node().id()).collect(Collectors.toSet());
    }

    private static Set<NodeId> nodeIds(List<List<OrderedStep>> layers, int layerIndex) {
        return nodeIds(layers.get(layerIndex));
    }

    @Nested
    class FlatGraphFastPath {

        @Test
        void edgelessGraph_allNodesInSingleAdditionLayer() {
            var a = node("a", SENSOR);
            var b = node("b", SENSOR);
            var c = node("c", GATEWAY);
            var graph = factory.of(List.of(a, b, c), List.of());
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(1);
            assertThat(nodeIds(plan.flatAdditions()))
                    .containsExactlyInAnyOrder(NodeId.of("a"), NodeId.of("b"), NodeId.of("c"));
        }

        @Test
        void edgelessGraph_noNodesToAdd_emptyPlan() {
            var a = node("a", SENSOR);
            var graph = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.isEmpty()).isTrue();
        }

        @Test
        void edgelessGraph_mixedAddAndRemove() {
            var a = node("a", SENSOR);
            var graph = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of(NodeId.of("old"), NodeStatus.PRESENT));

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(1);
            assertThat(nodeIds(plan.flatAdditions())).containsExactly(NodeId.of("a"));
            assertThat(plan.flatRemovals()).hasSize(1);
            assertThat(plan.flatRemovals().get(0).action()).isEqualTo(StepAction.DEPROVISION);
        }

        @Test
        void edgelessGraph_suspendAndResume_singleLayer() {
            var a = node("a", SENSOR, TargetStatus.SUSPENDED);
            var b = node("b", GATEWAY, TargetStatus.ACTIVE);
            var graph = factory.of(List.of(a, b), List.of());
            var actual = new ActualState(Map.of(
                    NodeId.of("a"), NodeStatus.PRESENT,
                    NodeId.of("b"), NodeStatus.SUSPENDED));

            TransitionPlan plan = planner.plan(graph, actual, null,
                    type -> true);

            assertThat(plan.suspensions()).hasSize(1);
            assertThat(nodeIds(plan.flatSuspensions())).containsExactly(NodeId.of("a"));
            assertThat(plan.resumptions()).hasSize(1);
            assertThat(nodeIds(plan.flatResumptions())).containsExactly(NodeId.of("b"));
        }

        @Test
        void edgelessGraph_nothingToSuspend_emptyList() {
            var a = node("a", SENSOR);
            var graph = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.suspensions()).isEmpty();
            assertThat(plan.resumptions()).isEmpty();
        }
    }

    @Nested
    class OrderingConstraints {

        @Test
        void constraintEnforcesLayerOrdering() {
            var s1 = node("s1", SENSOR);
            var g1 = node("g1", GATEWAY);
            var graph = factory.of(List.of(s1, g1), List.of(),
                    Set.of(new OrderingConstraint(GATEWAY, SENSOR)));
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(2);
            assertThat(nodeIds(plan.additions(), 0)).containsExactly(NodeId.of("g1"));
            assertThat(nodeIds(plan.additions(), 1)).containsExactly(NodeId.of("s1"));
        }

        @Test
        void constraintWithMultipleNodesPerType() {
            var s1 = node("s1", SENSOR);
            var s2 = node("s2", SENSOR);
            var g1 = node("g1", GATEWAY);
            var g2 = node("g2", GATEWAY);
            var graph = factory.of(List.of(s1, s2, g1, g2), List.of(),
                    Set.of(new OrderingConstraint(GATEWAY, SENSOR)));
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(2);
            assertThat(nodeIds(plan.additions(), 0))
                    .containsExactlyInAnyOrder(NodeId.of("g1"), NodeId.of("g2"));
            assertThat(nodeIds(plan.additions(), 1))
                    .containsExactlyInAnyOrder(NodeId.of("s1"), NodeId.of("s2"));
        }

        @Test
        void transitiveConstraintChain() {
            var s1 = node("s1", SENSOR);
            var g1 = node("g1", GATEWAY);
            var p1 = node("p1", PROCESSOR);
            var graph = factory.of(List.of(s1, g1, p1), List.of(),
                    Set.of(new OrderingConstraint(PROCESSOR, GATEWAY),
                           new OrderingConstraint(GATEWAY, SENSOR)));
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(3);
            assertThat(nodeIds(plan.additions(), 0)).containsExactly(NodeId.of("p1"));
            assertThat(nodeIds(plan.additions(), 1)).containsExactly(NodeId.of("g1"));
            assertThat(nodeIds(plan.additions(), 2)).containsExactly(NodeId.of("s1"));
        }

        @Test
        void constraintOnlyAffectsNodesInToSortSet() {
            var s1 = node("s1", SENSOR);
            var g1 = node("g1", GATEWAY);
            var graph = factory.of(List.of(s1, g1), List.of(),
                    Set.of(new OrderingConstraint(GATEWAY, SENSOR)));
            var actual = new ActualState(Map.of(
                    NodeId.of("g1"), NodeStatus.PRESENT));

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(1);
            assertThat(nodeIds(plan.flatAdditions())).containsExactly(NodeId.of("s1"));
        }

        @Test
        void constraintCombinedWithExplicitEdges() {
            var s1 = node("s1", SENSOR);
            var g1 = node("g1", GATEWAY);
            var p1 = node("p1", PROCESSOR);
            var graph = factory.of(
                    List.of(s1, g1, p1),
                    List.of(new Dependency(NodeId.of("s1"), NodeId.of("g1"))),
                    Set.of(new OrderingConstraint(PROCESSOR, GATEWAY)));
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(3);
            assertThat(nodeIds(plan.additions(), 0)).containsExactly(NodeId.of("p1"));
            assertThat(nodeIds(plan.additions(), 1)).containsExactly(NodeId.of("g1"));
            assertThat(nodeIds(plan.additions(), 2)).containsExactly(NodeId.of("s1"));
        }

        @Test
        void constraintCycleDetected() {
            var s1 = node("s1", SENSOR);
            var g1 = node("g1", GATEWAY);
            var graph = factory.of(List.of(s1, g1), List.of(),
                    Set.of(new OrderingConstraint(GATEWAY, SENSOR),
                           new OrderingConstraint(SENSOR, GATEWAY)));
            var actual = new ActualState(Map.of());

            assertThatThrownBy(() -> planner.plan(graph, actual))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cycle");
        }

        @Test
        void constraintWithNoMatchingNodes_noEffect() {
            var s1 = node("s1", SENSOR);
            var s2 = node("s2", SENSOR);
            NodeType UNKNOWN_TYPE = NodeType.of("unknown-type");
            var graph = factory.of(List.of(s1, s2), List.of(),
                    Set.of(new OrderingConstraint(UNKNOWN_TYPE, SENSOR)));
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(1);
            assertThat(nodeIds(plan.flatAdditions()))
                    .containsExactlyInAnyOrder(NodeId.of("s1"), NodeId.of("s2"));
        }
    }

    @Nested
    class FastPathGuard {

        @Test
        void constraintsOnly_noEdges_usesTopoSort() {
            var s1 = node("s1", SENSOR);
            var g1 = node("g1", GATEWAY);
            var graph = factory.of(List.of(s1, g1), List.of(),
                    Set.of(new OrderingConstraint(GATEWAY, SENSOR)));
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(2);
            assertThat(nodeIds(plan.additions(), 0)).containsExactly(NodeId.of("g1"));
            assertThat(nodeIds(plan.additions(), 1)).containsExactly(NodeId.of("s1"));
        }

        @Test
        void edgesOnly_noConstraints_usesTopoSort() {
            var s1 = node("s1", SENSOR);
            var g1 = node("g1", GATEWAY);
            var graph = factory.of(List.of(s1, g1),
                    List.of(new Dependency(NodeId.of("s1"), NodeId.of("g1"))));
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(2);
            assertThat(nodeIds(plan.additions(), 0)).containsExactly(NodeId.of("g1"));
            assertThat(nodeIds(plan.additions(), 1)).containsExactly(NodeId.of("s1"));
        }

        @Test
        void noEdgesNoConstraints_singleLayer() {
            var s1 = node("s1", SENSOR);
            var g1 = node("g1", GATEWAY);
            var graph = factory.of(List.of(s1, g1), List.of());
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.additions()).hasSize(1);
            assertThat(nodeIds(plan.flatAdditions()))
                    .containsExactlyInAnyOrder(NodeId.of("s1"), NodeId.of("g1"));
        }
    }

    @Nested
    class SuspendResumeWithConstraints {

        @Test
        void suspendRespectsReverseConstraintOrder() {
            var s1 = node("s1", SENSOR, TargetStatus.SUSPENDED);
            var g1 = node("g1", GATEWAY, TargetStatus.SUSPENDED);
            var graph = factory.of(List.of(s1, g1), List.of(),
                    Set.of(new OrderingConstraint(GATEWAY, SENSOR)));
            var actual = new ActualState(Map.of(
                    NodeId.of("s1"), NodeStatus.PRESENT,
                    NodeId.of("g1"), NodeStatus.PRESENT));

            TransitionPlan plan = planner.plan(graph, actual, null, type -> true);

            assertThat(plan.suspensions()).hasSize(2);
            assertThat(nodeIds(plan.suspensions(), 0)).containsExactly(NodeId.of("s1"));
            assertThat(nodeIds(plan.suspensions(), 1)).containsExactly(NodeId.of("g1"));
        }

        @Test
        void resumeRespectsForwardConstraintOrder() {
            var g1 = node("g1", GATEWAY, TargetStatus.ACTIVE);
            var s1 = node("s1", SENSOR, TargetStatus.ACTIVE);
            var graph = factory.of(List.of(g1, s1), List.of(),
                    Set.of(new OrderingConstraint(GATEWAY, SENSOR)));
            var actual = new ActualState(Map.of(
                    NodeId.of("g1"), NodeStatus.SUSPENDED,
                    NodeId.of("s1"), NodeStatus.SUSPENDED));

            TransitionPlan plan = planner.plan(graph, actual, null, type -> true);

            assertThat(plan.resumptions()).hasSize(2);
            assertThat(nodeIds(plan.resumptions(), 0)).containsExactly(NodeId.of("g1"));
            assertThat(nodeIds(plan.resumptions(), 1)).containsExactly(NodeId.of("s1"));
        }
    }

    @Nested
    class ExemptNodes {

        @Test
        void exemptDriftedNode_excludedFromPlan() {
            var a = node("a", SENSOR);
            var graph = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of(NodeId.of("a"), NodeStatus.DRIFTED));

            TransitionPlan plan = planner.plan(graph, actual, null,
                    type -> false, Set.of(NodeId.of("a")));

            assertThat(plan.isEmpty()).isTrue();
        }

        @Test
        void nonExemptDriftedNode_includedInPlan() {
            var a = node("a", SENSOR);
            var graph = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of(NodeId.of("a"), NodeStatus.DRIFTED));

            TransitionPlan plan = planner.plan(graph, actual, null,
                    type -> false, Set.of());

            assertThat(plan.flatAdditions()).hasSize(1);
            assertThat(plan.flatAdditions().get(0).node().id()).isEqualTo(NodeId.of("a"));
        }
    }

    @Nested
    class RemovalOrdering {

        @Test
        void removals_orphanedNodes_deprovisionedFromPreviousDesired() {
            var a = node("a", SENSOR);
            var previousGraph = factory.of(List.of(a), List.of());
            var graph = factory.of(List.of(), List.of());
            var actual = new ActualState(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

            TransitionPlan plan = planner.plan(graph, actual, previousGraph);

            assertThat(plan.flatRemovals()).hasSize(1);
            assertThat(plan.flatRemovals().get(0).node().id()).isEqualTo(NodeId.of("a"));
            assertThat(plan.flatRemovals().get(0).node().type()).isEqualTo(SENSOR);
        }

        @Test
        void removals_unknownOrphan_getsSyntheticSpec() {
            var graph = factory.of(List.of(), List.of());
            var actual = new ActualState(Map.of(NodeId.of("orphan"), NodeStatus.PRESENT));

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.flatRemovals()).hasSize(1);
            assertThat(plan.flatRemovals().get(0).node().id()).isEqualTo(NodeId.of("orphan"));
        }

        @Test
        void removals_absentOrUnknownActual_notRemoved() {
            var graph = factory.of(List.of(), List.of());
            var actual = new ActualState(Map.of(
                    NodeId.of("absent"), NodeStatus.ABSENT,
                    NodeId.of("unknown"), NodeStatus.UNKNOWN));

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.flatRemovals()).isEmpty();
        }

        @Test
        void removals_suspendedOrphans_deprovision() {
            var graph = factory.of(List.of(), List.of());
            var actual = new ActualState(Map.of(NodeId.of("sus"), NodeStatus.SUSPENDED));

            TransitionPlan plan = planner.plan(graph, actual);

            assertThat(plan.flatRemovals()).hasSize(1);
            assertThat(plan.flatRemovals().get(0).action()).isEqualTo(StepAction.DEPROVISION);
        }
    }

    @Nested
    class StatefulLifecycleFallback {

        @Test
        void suspendFallsBackToDeprovision_whenNotStateful() {
            var a = node("a", SENSOR, TargetStatus.SUSPENDED);
            var graph = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

            TransitionPlan plan = planner.plan(graph, actual, null, type -> false);

            assertThat(plan.flatRemovals()).hasSize(1);
            assertThat(plan.flatRemovals().get(0).action()).isEqualTo(StepAction.DEPROVISION);
        }

        @Test
        void resumeFallsBackToProvision_whenNotStateful() {
            var a = node("a", SENSOR, TargetStatus.ACTIVE);
            var graph = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of(NodeId.of("a"), NodeStatus.SUSPENDED));

            TransitionPlan plan = planner.plan(graph, actual, null, type -> false);

            assertThat(plan.flatAdditions()).hasSize(1);
            assertThat(plan.flatAdditions().get(0).action()).isEqualTo(StepAction.PROVISION);
        }
    }

    @Nested
    class PlanGraphReferences {

        @Test
        void planCarriesBeforeAndAfterGraphs() {
            var a = node("a", SENSOR);
            var previous = factory.of(List.of(), List.of());
            var desired = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(desired, actual, previous);

            assertThat(plan.before()).isSameAs(previous);
            assertThat(plan.after()).isSameAs(desired);
        }

        @Test
        void noPreviousDesired_beforeFallsBackToCurrent() {
            var a = node("a", SENSOR);
            var desired = factory.of(List.of(a), List.of());
            var actual = new ActualState(Map.of());

            TransitionPlan plan = planner.plan(desired, actual);

            assertThat(plan.before()).isSameAs(desired);
            assertThat(plan.after()).isSameAs(desired);
        }
    }
}
