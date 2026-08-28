package io.casehub.desiredstate.annotations.runtime;

import java.lang.reflect.Method;
import java.util.List;

public sealed interface ResolvedInvariant {

    String name();

    List<PatternParameterDescriptor> patterns();

    String[] bindingNames();

    record ImperativeInvariant(String name, Method method, Object instance) implements ResolvedInvariant {
        @Override
        public List<PatternParameterDescriptor> patterns() { return List.of(); }

        @Override
        public String[] bindingNames() { return new String[0]; }
    }

    record ParameterizedReflectiveInvariant(String name, Method method, Object instance,
                                            List<PatternParameterDescriptor> patterns) implements ResolvedInvariant {
        @Override
        public String[] bindingNames() {
            return PatternMatchingSupport.getParameterNames(method);
        }
    }

    record DeclarativeInvariant(String name, List<PatternParameterDescriptor> patterns,
                                String[] bindingNames, String messageTemplate) implements ResolvedInvariant {
    }
}
