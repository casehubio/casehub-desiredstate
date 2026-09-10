package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.plugin.api.PluginInterpolator;
import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.api.StepPrimitive;
import io.casehub.desiredstate.plugin.api.StepResult;
import io.casehub.desiredstate.plugin.model.PluginStepDef;
import io.casehub.desiredstate.plugin.runtime.primitives.AssertPrimitive;
import io.casehub.desiredstate.plugin.runtime.primitives.CompareStatePrimitive;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepPipelineExecutorTest {

    private final PluginInterpolator interpolator = new PluginInterpolator();

    @Test
    void executesActualStateReturnsPresent() {
        var registry = PrimitiveRegistry.of(Map.of(
            "compare-state", new CompareStatePrimitive()));
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(step("compare-state", Map.of(
            "present-when", "${spec.value} == 1",
            "absent-when", "${spec.value} == 0"), null));
        var ctx = StepContext.builder().spec(Map.of("value", 1)).build();

        var status = executor.executeActualState(steps, ctx);
        assertThat(status).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void executesActualStateReturnsAbsent() {
        var registry = PrimitiveRegistry.of(Map.of(
            "compare-state", new CompareStatePrimitive()));
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(step("compare-state", Map.of(
            "present-when", "${spec.value} == 1",
            "absent-when", "${spec.value} == 0"), null));
        var ctx = StepContext.builder().spec(Map.of("value", 0)).build();

        var status = executor.executeActualState(steps, ctx);
        assertThat(status).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void accumulatesResultsAcrossSteps() {
        StepPrimitive echo = new StepPrimitive() {
            @Override
            public String name() { return "echo"; }

            @Override
            public StepResult execute(StepParameters params, StepContext context) {
                return StepResult.of(Map.of("value", params.get("data")));
            }
        };

        var registry = PrimitiveRegistry.of(Map.of(
            "echo", echo,
            "assert", new AssertPrimitive()));
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(
            step("echo", Map.of("data", 42), "first"),
            step("assert", Map.of(
                "condition", "${result.first.value} == 42"), null));
        var ctx = StepContext.builder().spec(Map.of()).build();

        var result = executor.execute(steps, ctx);
        assertThat(result.get("passed")).isEqualTo(true);
    }

    @Test
    void respectsWhenConditionSkipsStep() {
        AtomicInteger callCount = new AtomicInteger(0);
        StepPrimitive counter = new StepPrimitive() {
            @Override
            public String name() { return "counter"; }

            @Override
            public StepResult execute(StepParameters params, StepContext context) {
                callCount.incrementAndGet();
                return StepResult.of(Map.of("called", true));
            }
        };

        var registry = PrimitiveRegistry.of(Map.of("counter", counter));
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(stepWithWhen("counter", Map.of(), null,
            "${spec.enabled} == false"));
        var ctx = StepContext.builder()
            .spec(Map.of("enabled", true))
            .build();

        executor.execute(steps, ctx);
        assertThat(callCount.get()).isEqualTo(0);
    }

    @Test
    void respectsWhenConditionExecutesStep() {
        AtomicInteger callCount = new AtomicInteger(0);
        StepPrimitive counter = new StepPrimitive() {
            @Override
            public String name() { return "counter"; }

            @Override
            public StepResult execute(StepParameters params, StepContext context) {
                callCount.incrementAndGet();
                return StepResult.of(Map.of("called", true));
            }
        };

        var registry = PrimitiveRegistry.of(Map.of("counter", counter));
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(stepWithWhen("counter", Map.of(), null,
            "${spec.enabled} == true"));
        var ctx = StepContext.builder()
            .spec(Map.of("enabled", true))
            .build();

        executor.execute(steps, ctx);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void onErrorSkipContinuesPipeline() {
        StepPrimitive failing = new StepPrimitive() {
            @Override
            public String name() { return "failing"; }

            @Override
            public StepResult execute(StepParameters params, StepContext context) {
                throw new StepExecutionException("boom");
            }
        };

        var registry = PrimitiveRegistry.of(Map.of(
            "failing", failing,
            "assert", new AssertPrimitive()));
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(
            stepWithOnError("failing", Map.of(), null, "skip"),
            step("assert", Map.of("condition", "1 == 1"), null));
        var ctx = StepContext.builder().spec(Map.of()).build();

        var result = executor.execute(steps, ctx);
        assertThat(result.get("passed")).isEqualTo(true);
    }

    @Test
    void onErrorFailStopsPipeline() {
        StepPrimitive failing = new StepPrimitive() {
            @Override
            public String name() { return "failing"; }

            @Override
            public StepResult execute(StepParameters params, StepContext context) {
                throw new StepExecutionException("boom");
            }
        };

        var registry = PrimitiveRegistry.of(Map.of("failing", failing));
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(step("failing", Map.of(), null));
        var ctx = StepContext.builder().spec(Map.of()).build();

        assertThatThrownBy(() -> executor.execute(steps, ctx))
            .isInstanceOf(StepExecutionException.class)
            .hasMessageContaining("boom");
    }

    @Test
    void executeActualStateReturnsUnknownOnFailure() {
        StepPrimitive failing = new StepPrimitive() {
            @Override
            public String name() { return "failing"; }

            @Override
            public StepResult execute(StepParameters params, StepContext context) {
                throw new StepExecutionException("boom");
            }
        };

        var registry = PrimitiveRegistry.of(Map.of("failing", failing));
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(step("failing", Map.of(), null));
        var ctx = StepContext.builder().spec(Map.of()).build();

        var status = executor.executeActualState(steps, ctx);
        assertThat(status).isEqualTo(NodeStatus.UNKNOWN);
    }

    @Test
    void unknownPrimitiveThrows() {
        var registry = PrimitiveRegistry.of(Map.of());
        var executor = new StepPipelineExecutor(registry, interpolator);

        var steps = List.of(step("nonexistent", Map.of(), null));
        var ctx = StepContext.builder().spec(Map.of()).build();

        assertThatThrownBy(() -> executor.execute(steps, ctx))
            .isInstanceOf(StepExecutionException.class)
            .hasMessageContaining("nonexistent");
    }

    @Test
    void emptyPipelineReturnsEmptyResult() {
        var registry = PrimitiveRegistry.of(Map.of());
        var executor = new StepPipelineExecutor(registry, interpolator);

        var ctx = StepContext.builder().spec(Map.of()).build();
        var result = executor.execute(List.of(), ctx);
        assertThat(result.data()).isEmpty();
    }

    private static PluginStepDef step(String primitive, Map<String, Object> params,
                                      String result) {
        return new PluginStepDef(primitive, params, result, null, null, 3, null);
    }

    private static PluginStepDef stepWithWhen(String primitive, Map<String, Object> params,
                                              String result, String when) {
        return new PluginStepDef(primitive, params, result, when, null, 3, null);
    }

    private static PluginStepDef stepWithOnError(String primitive, Map<String, Object> params,
                                                 String result, String onError) {
        return new PluginStepDef(primitive, params, result, null, onError, 3, null);
    }
}
