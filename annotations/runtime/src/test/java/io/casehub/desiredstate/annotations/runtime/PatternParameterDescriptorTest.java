package io.casehub.desiredstate.annotations.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PatternParameterDescriptorTest {

    @Test
    void fourArgConstructorSetsUnspecifiedCardinality() {
        var ppd = new PatternParameterDescriptor(
                PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES);
        assertEquals(PatternParameterDescriptor.UNSPECIFIED, ppd.minCount());
        assertEquals(PatternParameterDescriptor.UNSPECIFIED, ppd.maxCount());
        assertFalse(ppd.hasCardinalityConstraint());
    }

    @Test
    void effectiveMinCountDefaultsForMatch() {
        var ppd = new PatternParameterDescriptor(
                PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES);
        assertEquals(0, ppd.effectiveMinCount());
        assertEquals(Integer.MAX_VALUE, ppd.effectiveMaxCount());
    }

    @Test
    void effectiveMinCountDefaultsForDirectDep() {
        var ppd = new PatternParameterDescriptor(
                PatternKind.DIRECT_DEP, "target", "lb", Direction.DEPENDENTS);
        assertEquals(1, ppd.effectiveMinCount());
        assertEquals(Integer.MAX_VALUE, ppd.effectiveMaxCount());
    }

    @Test
    void effectiveMinCountDefaultsForReaches() {
        var ppd = new PatternParameterDescriptor(
                PatternKind.REACHES, "endpoint", "router", Direction.DEPENDENTS);
        assertEquals(1, ppd.effectiveMinCount());
    }

    @Test
    void explicitCardinalityOverridesDefaults() {
        var ppd = new PatternParameterDescriptor(
                PatternKind.MATCH, "instance", "", Direction.DEPENDENCIES, 3, 10);
        assertEquals(3, ppd.effectiveMinCount());
        assertEquals(10, ppd.effectiveMaxCount());
        assertTrue(ppd.hasCardinalityConstraint());
    }

    @Test
    void hasCardinalityConstraintTrueWhenOnlyMinSet() {
        var ppd = new PatternParameterDescriptor(
                PatternKind.MATCH, "instance", "", Direction.DEPENDENCIES,
                3, PatternParameterDescriptor.UNSPECIFIED);
        assertTrue(ppd.hasCardinalityConstraint());
    }

    @Test
    void hasCardinalityConstraintTrueWhenOnlyMaxSet() {
        var ppd = new PatternParameterDescriptor(
                PatternKind.REACHES, "endpoint", "router", Direction.DEPENDENTS,
                PatternParameterDescriptor.UNSPECIFIED, 5);
        assertTrue(ppd.hasCardinalityConstraint());
    }
}
