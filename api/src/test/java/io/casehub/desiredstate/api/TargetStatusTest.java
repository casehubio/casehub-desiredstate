package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetStatusTest {
    @Test
    void values() {
        assertEquals(2, TargetStatus.values().length);
        assertNotNull(TargetStatus.ACTIVE);
        assertNotNull(TargetStatus.SUSPENDED);
    }
}
