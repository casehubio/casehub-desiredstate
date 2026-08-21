package io.casehub.desiredstate.annotations.runtime;

public record GoalMethodDescriptor(
        String methodName,
        String goalsTypeName,
        boolean returnsCompilationResult,
        boolean hasFactoryParam) {}
