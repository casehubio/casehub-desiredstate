package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.casehub.desiredstate.api.NodeLifecycleState.*;
import static org.assertj.core.api.Assertions.assertThat;

class NodeLifecycleDefinitionTest {

    @Test
    void validDefinition_noErrors() {
        var def = new NodeLifecycleDefinition(NodeType.of("test"),
            Set.of(
                new NodeLifecycleDefinition.Transition(ABSENT, PROVISIONING),
                new NodeLifecycleDefinition.Transition(PROVISIONING, PRESENT),
                new NodeLifecycleDefinition.Transition(PROVISIONING, DRIFTED),
                new NodeLifecycleDefinition.Transition(PRESENT, DEPROVISIONING),
                new NodeLifecycleDefinition.Transition(DRIFTED, DEPROVISIONING),
                new NodeLifecycleDefinition.Transition(DEPROVISIONING, ABSENT)),
            Map.of(), Map.of());

        assertThat(def.validate()).isEmpty();
    }

    @Test
    void transientStateWithNoExit_producesError() {
        var def = new NodeLifecycleDefinition(NodeType.of("test"),
            Set.of(
                new NodeLifecycleDefinition.Transition(ABSENT, PROVISIONING)),
            Map.of(), Map.of());

        assertThat(def.validate())
            .anyMatch(e -> e.contains("PROVISIONING") && e.contains("no exit"));
    }

    @Test
    void missingAbsentAsFrom_producesError() {
        var def = new NodeLifecycleDefinition(NodeType.of("test"),
            Set.of(
                new NodeLifecycleDefinition.Transition(PROVISIONING, PRESENT)),
            Map.of(), Map.of());

        assertThat(def.validate())
            .anyMatch(e -> e.contains("ABSENT") && e.contains("'from'"));
    }

    @Test
    void orphanOnEnterState_producesError() {
        var def = new NodeLifecycleDefinition(NodeType.of("test"),
            Set.of(
                new NodeLifecycleDefinition.Transition(ABSENT, PROVISIONING),
                new NodeLifecycleDefinition.Transition(PROVISIONING, PRESENT)),
            Map.of(SUSPENDED, List.of(new TransitionAction.EmitEvent("suspended"))),
            Map.of());

        assertThat(def.validate())
            .anyMatch(e -> e.contains("orphan") && e.contains("SUSPENDED"));
    }

    @Test
    void supportsSuspendResume_trueWhenSuspendTransitionsExist() {
        var def = new NodeLifecycleDefinition(NodeType.of("test"),
            Set.of(
                new NodeLifecycleDefinition.Transition(ABSENT, PROVISIONING),
                new NodeLifecycleDefinition.Transition(PROVISIONING, PRESENT),
                new NodeLifecycleDefinition.Transition(PRESENT, SUSPENDING),
                new NodeLifecycleDefinition.Transition(SUSPENDING, SUSPENDED)),
            Map.of(), Map.of());

        assertThat(def.supportsSuspendResume()).isTrue();
    }

    @Test
    void supportsSuspendResume_falseWhenNoSuspendTransitions() {
        var def = new NodeLifecycleDefinition(NodeType.of("test"),
            Set.of(
                new NodeLifecycleDefinition.Transition(ABSENT, PROVISIONING),
                new NodeLifecycleDefinition.Transition(PROVISIONING, PRESENT),
                new NodeLifecycleDefinition.Transition(PRESENT, DEPROVISIONING),
                new NodeLifecycleDefinition.Transition(DEPROVISIONING, ABSENT)),
            Map.of(), Map.of());

        assertThat(def.supportsSuspendResume()).isFalse();
    }

    @Test
    void fromNodeStatus_mapsCorrectly() {
        assertThat(NodeLifecycleState.fromNodeStatus(NodeStatus.PRESENT)).isEqualTo(PRESENT);
        assertThat(NodeLifecycleState.fromNodeStatus(NodeStatus.ABSENT)).isEqualTo(ABSENT);
        assertThat(NodeLifecycleState.fromNodeStatus(NodeStatus.DRIFTED)).isEqualTo(DRIFTED);
        assertThat(NodeLifecycleState.fromNodeStatus(NodeStatus.SUSPENDED)).isEqualTo(SUSPENDED);
        assertThat(NodeLifecycleState.fromNodeStatus(NodeStatus.UNKNOWN)).isNull();
    }

    @Test
    void isTransient_correctForAllStates() {
        assertThat(PROVISIONING.isTransient()).isTrue();
        assertThat(DEPROVISIONING.isTransient()).isTrue();
        assertThat(SUSPENDING.isTransient()).isTrue();
        assertThat(RESUMING.isTransient()).isTrue();
        assertThat(PRESENT.isTransient()).isFalse();
        assertThat(ABSENT.isTransient()).isFalse();
        assertThat(DRIFTED.isTransient()).isFalse();
        assertThat(SUSPENDED.isTransient()).isFalse();
    }
}
