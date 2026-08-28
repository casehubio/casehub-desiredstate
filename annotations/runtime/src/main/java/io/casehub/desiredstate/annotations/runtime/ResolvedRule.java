package io.casehub.desiredstate.annotations.runtime;

import java.lang.reflect.Method;
import java.util.List;

public sealed interface ResolvedRule {

    String name();

    List<PatternParameterDescriptor> patterns();

    String[] bindingNames();

    record ImperativeRule(String name, Method method, Object instance) implements ResolvedRule {
        @Override
        public List<PatternParameterDescriptor> patterns() { return List.of(); }

        @Override
        public String[] bindingNames() { return new String[0]; }
    }

    record ParameterizedReflectiveRule(String name, Method method, Object instance,
                                       List<PatternParameterDescriptor> patterns) implements ResolvedRule {
        @Override
        public String[] bindingNames() {
            return PatternMatchingSupport.getParameterNames(method);
        }
    }

    record DeclarativeRule(String name, List<PatternParameterDescriptor> patterns,
                           String[] bindingNames,
                           java.util.function.Function<java.util.Map<String, io.casehub.desiredstate.api.DesiredNode>,
                                                              java.util.List<io.casehub.desiredstate.api.GraphMutation>> actionEvaluator)
            implements ResolvedRule {
    }
}
