package io.casehub.desiredstate.annotations.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphPatternMatcherTest {

    @Test
    void exactMatch() {
        assertTrue(GraphPatternMatcher.matches(
                new String[]{"pipeline:medallion"}, "pipeline:medallion"));
        assertFalse(GraphPatternMatcher.matches(
                new String[]{"pipeline:medallion"}, "pipeline:batch"));
    }

    @Test
    void namespaceWildcard() {
        assertTrue(GraphPatternMatcher.matches(
                new String[]{"pipeline:*"}, "pipeline:medallion"));
        assertTrue(GraphPatternMatcher.matches(
                new String[]{"pipeline:*"}, "pipeline:batch"));
        assertFalse(GraphPatternMatcher.matches(
                new String[]{"pipeline:*"}, "analytics:report"));
    }

    @Test
    void globalWildcard() {
        assertTrue(GraphPatternMatcher.matches(
                new String[]{"*:*"}, "pipeline:medallion"));
        assertTrue(GraphPatternMatcher.matches(
                new String[]{"*:*"}, "analytics:report"));
    }

    @Test
    void excludePattern() {
        assertTrue(GraphPatternMatcher.matches(
                new String[]{"*:*", "!debug:*"}, "pipeline:medallion"));
        assertFalse(GraphPatternMatcher.matches(
                new String[]{"*:*", "!debug:*"}, "debug:trace"));
    }

    @Test
    void reIncludeAfterExclude() {
        var patterns = new String[]{"*:*", "!internal:*", "internal:monitoring"};
        assertTrue(GraphPatternMatcher.matches(patterns, "pipeline:medallion"));
        assertFalse(GraphPatternMatcher.matches(patterns, "internal:debug"));
        assertTrue(GraphPatternMatcher.matches(patterns, "internal:monitoring"));
    }

    @Test
    void lastMatchWins() {
        var patterns = new String[]{"pipeline:*", "!pipeline:debug", "pipeline:debug"};
        assertTrue(GraphPatternMatcher.matches(patterns, "pipeline:debug"));
    }

    @Test
    void emptyPatternsMatchNothing() {
        assertFalse(GraphPatternMatcher.matches(new String[]{}, "pipeline:medallion"));
    }

    @Test
    void multipleIncludes() {
        var patterns = new String[]{"pipeline:*", "analytics:*"};
        assertTrue(GraphPatternMatcher.matches(patterns, "pipeline:medallion"));
        assertTrue(GraphPatternMatcher.matches(patterns, "analytics:report"));
        assertFalse(GraphPatternMatcher.matches(patterns, "debug:trace"));
    }
}
