package io.casehub.desiredstate.annotations.runtime;

import io.quarkus.runtime.annotations.RecordableConstructor;

public record PatternParameterDescriptor(
        PatternKind kind,
        String nodeType,
        String of,
        Direction direction,
        int minCount,
        int maxCount) {

    public static final int UNSPECIFIED = -1;

    @RecordableConstructor
    public PatternParameterDescriptor {}

    public PatternParameterDescriptor(PatternKind kind, String nodeType,
                                       String of, Direction direction) {
        this(kind, nodeType, of, direction, UNSPECIFIED, UNSPECIFIED);
    }

    public int effectiveMinCount() {
        if (minCount != UNSPECIFIED) return minCount;
        return kind == PatternKind.MATCH ? 0 : 1;
    }

    public int effectiveMaxCount() {
        return maxCount != UNSPECIFIED ? maxCount : Integer.MAX_VALUE;
    }

    public boolean hasCardinalityConstraint() {
        return minCount != UNSPECIFIED || maxCount != UNSPECIFIED;
    }
}
