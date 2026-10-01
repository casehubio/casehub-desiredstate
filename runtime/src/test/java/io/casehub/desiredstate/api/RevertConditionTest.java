package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RevertConditionTest {

    @Test
    void onDuration_modeIsDuration() {
        var condition = new RevertCondition.OnDuration(Duration.ofMinutes(30));
        assertEquals(RevertMode.DURATION, condition.mode());
        assertEquals(Duration.ofMinutes(30), condition.duration());
    }

    @Test
    void onDuration_rejectsNull() {
        assertThrows(NullPointerException.class,
            () -> new RevertCondition.OnDuration(null));
    }

    @Test
    void onSchedule_modeIsSchedule() {
        var condition = new RevertCondition.OnSchedule("0 9 * * 1-5");
        assertEquals(RevertMode.SCHEDULE, condition.mode());
        assertEquals("0 9 * * 1-5", condition.cronExpression());
    }

    @Test
    void onStatusChange_modeIsStatusChange() {
        var condition = new RevertCondition.OnStatusChange(
            Set.of(NodeStatus.PRESENT));
        assertEquals(RevertMode.STATUS_CHANGE, condition.mode());
        assertTrue(condition.triggerStatuses().contains(NodeStatus.PRESENT));
    }

    @Test
    void onStatusChange_defensiveCopy() {
        var mutable = new HashSet<>(Set.of(NodeStatus.PRESENT));
        var condition = new RevertCondition.OnStatusChange(mutable);
        mutable.add(NodeStatus.ABSENT);
        assertEquals(1, condition.triggerStatuses().size());
    }

    @Test
    void never_modeIsNever() {
        var condition = new RevertCondition.Never();
        assertEquals(RevertMode.NEVER, condition.mode());
    }
}
