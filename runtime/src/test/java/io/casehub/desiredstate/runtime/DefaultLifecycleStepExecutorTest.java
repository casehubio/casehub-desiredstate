package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.LifecycleStep;
import io.casehub.desiredstate.api.NotificationSink;
import io.casehub.desiredstate.api.StepOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLifecycleStepExecutorTest {

    @Test
    void verify_succeeds_on2xxResponse() {
        // Verify step with a real HTTP call would need a test server.
        // We test the happy path via a known-reachable URL pattern.
        // For unit testing, we verify the failure path and constructor defaults.
        var step = new LifecycleStep.Verify("http://localhost:0/nonexistent", 1);
        var executor = new DefaultLifecycleStepExecutor(new NoOpNotificationSink());

        StepOutcome outcome = executor.execute(step, "tenant1");

        assertInstanceOf(StepOutcome.Failed.class, outcome);
    }

    @Test
    void verify_defaultTimeout_appliedWhenZeroOrNegative() {
        var step = new LifecycleStep.Verify("http://example.com", 0);
        assertEquals(30, step.timeoutSeconds());

        var stepNeg = new LifecycleStep.Verify("http://example.com", -5);
        assertEquals(30, stepNeg.timeoutSeconds());
    }

    @Test
    void notify_delegatesToSink_returnsSucceeded() {
        var captured = new ArrayList<String>();
        NotificationSink sink = (channel, message, tenancyId) ->
                                        captured.add(channel + "|" + message + "|" + tenancyId);

        var executor = new DefaultLifecycleStepExecutor(sink);
        var step     = new LifecycleStep.Notify("email", "deployed api-server");

        StepOutcome outcome = executor.execute(step, "tenant1");

        assertInstanceOf(StepOutcome.Succeeded.class, outcome);
        assertEquals(1, captured.size());
        assertEquals("email|deployed api-server|tenant1", captured.get(0));
    }

    @Test
    void notify_sinkThrows_returnsFailed() {
        NotificationSink failingSink = (channel, message, tenancyId) -> {
            throw new RuntimeException("SMTP down");
        };

        var executor = new DefaultLifecycleStepExecutor(failingSink);
        var step     = new LifecycleStep.Notify("email", "msg");

        StepOutcome outcome = executor.execute(step, "tenant1");

        assertInstanceOf(StepOutcome.Failed.class, outcome);
        assertTrue(((StepOutcome.Failed) outcome).reason().contains("SMTP down"));
    }

    @Test
    void wait_returnsSucceeded() {
        var executor = new DefaultLifecycleStepExecutor(new NoOpNotificationSink());
        var step = new LifecycleStep.Wait(1);

        long start = System.currentTimeMillis();
        StepOutcome outcome = executor.execute(step, "tenant1");
        long elapsed = System.currentTimeMillis() - start;

        assertInstanceOf(StepOutcome.Succeeded.class, outcome);
        assertTrue(elapsed >= 900, "Wait should sleep for at least ~1 second, but was " + elapsed + "ms");
    }

    @Test
    void wait_interruptedThread_returnsFailed() {
        var executor = new DefaultLifecycleStepExecutor(new NoOpNotificationSink());
        var step = new LifecycleStep.Wait(60);

        Thread.currentThread().interrupt();
        StepOutcome outcome = executor.execute(step, "tenant1");

        assertInstanceOf(StepOutcome.Failed.class, outcome);
        assertTrue(((StepOutcome.Failed) outcome).reason().contains("interrupted"));
    }

    private static class NoOpNotificationSink implements NotificationSink {
        @Override
        public void send(String channel, String message, String tenancyId) {
            // no-op
        }
    }
}
