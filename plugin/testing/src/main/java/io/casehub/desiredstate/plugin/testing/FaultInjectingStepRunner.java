package io.casehub.desiredstate.plugin.testing;

import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;

import java.util.concurrent.atomic.AtomicInteger;

public class FaultInjectingStepRunner implements StepRunner {

    private final StepRunner delegate;
    private final AtomicInteger failuresRemaining;
    private final String errorMessage;

    public FaultInjectingStepRunner(StepRunner delegate, int failCount, String errorMessage) {
        this.delegate = delegate;
        this.failuresRemaining = new AtomicInteger(failCount);
        this.errorMessage = errorMessage;
    }

    @Override
    public Result run(ResolvedStep step, VariableResolver resolver) {
        if (failuresRemaining.getAndDecrement() > 0) {
            throw new RuntimeException(errorMessage);
        }
        return delegate.run(step, resolver);
    }
}
