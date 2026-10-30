package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class PlanCapableContractTest {

    protected abstract PlanCapable createPlanCapableProvider();
    protected abstract BridgeRequest samplePlanRequest();

    private PlanCapable provider;

    @BeforeEach
    void setUp() {
        provider = createPlanCapableProvider();
    }

    @Test
    void planReturnsNonNull() {
        assertThat(provider.plan(samplePlanRequest())).isNotNull();
    }

    @Test
    void planHasPlanId() {
        var plan = provider.plan(samplePlanRequest());
        assertThat(plan.planId()).isNotNull();
    }

    @Test
    void applyPlanReturnsNonNull() {
        var plan = provider.plan(samplePlanRequest());
        assertThat(provider.applyPlan(plan)).isNotNull();
    }
}
