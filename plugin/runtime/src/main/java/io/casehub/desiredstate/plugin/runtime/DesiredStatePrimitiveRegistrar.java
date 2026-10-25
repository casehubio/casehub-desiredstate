package io.casehub.desiredstate.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.plugin.runtime.primitives.ApprovalGatePrimitive;
import io.casehub.desiredstate.plugin.runtime.primitives.CompareStatePrimitive;
import io.casehub.desiredstate.plugin.runtime.primitives.GraphqlCallPrimitive;
import io.casehub.desiredstate.plugin.runtime.primitives.JsonExtractPrimitive;
import io.casehub.desiredstate.plugin.runtime.primitives.WriterAction;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.PluginRegistry;

import java.io.Writer;
import java.util.logging.Logger;

public class DesiredStatePrimitiveRegistrar {

    private static final Logger LOG = Logger.getLogger(DesiredStatePrimitiveRegistrar.class.getName());

    public static Writer logWriter() {
        return new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                LOG.info(new String(cbuf, off, len).stripTrailing());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    private final ConditionEvaluator conditionEvaluator;
    private final ObjectMapper mapper;
    private final Writer writer;

    public DesiredStatePrimitiveRegistrar(ConditionEvaluator conditionEvaluator,
                                          ObjectMapper mapper,
                                          Writer writer) {
        this.conditionEvaluator = conditionEvaluator;
        this.mapper = mapper;
        this.writer = writer;
    }

    public void registerAll(PluginRegistry registry) {
        registry.register(Definition.of("compare-state")
                .description("Evaluates conditions to determine node status")
                .portability(Portability.JAVA)
                .execute(new CompareStatePrimitive(conditionEvaluator))
                .build());

        registry.register(Definition.of("json-extract")
                .description("Extracts fields from JSON using dot-path notation")
                .portability(Portability.JAVA)
                .execute(new JsonExtractPrimitive(mapper))
                .build());

        registry.register(Definition.of("graphql-call")
                .description("Executes a GraphQL query/mutation over HTTP")
                .portability(Portability.JAVA)
                .execute(new GraphqlCallPrimitive(mapper))
                .build());

        registry.register(Definition.of("approval-gate")
                .description("Blocks step execution pending approval")
                .portability(Portability.JAVA)
                .execute(new ApprovalGatePrimitive())
                .build());

        for (String name : PluginValidator.BUILT_IN_PRIMITIVES) {
            if (registry.resolve(name).isEmpty()) {
                registry.register(Definition.of(name)
                        .description("Writer fallback for " + name)
                        .portability(Portability.TS)
                        .execute(new WriterAction(name, writer))
                        .build());
            }
        }
    }
}
