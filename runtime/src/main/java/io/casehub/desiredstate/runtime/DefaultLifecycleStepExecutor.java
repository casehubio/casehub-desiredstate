package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.LifecycleStep;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.NotificationSink;
import io.casehub.desiredstate.api.StepOutcome;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@DefaultBean
@ApplicationScoped
public class DefaultLifecycleStepExecutor implements LifecycleStepExecutor {

    private static final Logger LOG = Logger.getLogger(DefaultLifecycleStepExecutor.class);

    private final NotificationSink notificationSink;

    public DefaultLifecycleStepExecutor(NotificationSink notificationSink) {
        this.notificationSink = notificationSink;
    }

    @Override
    public StepOutcome execute(LifecycleStep step, String tenancyId) {
        return switch (step) {
            case LifecycleStep.Verify v -> executeVerify(v);
            case LifecycleStep.Notify n -> executeNotify(n, tenancyId);
            case LifecycleStep.Wait w -> executeWait(w);
        };
    }

    private StepOutcome executeVerify(LifecycleStep.Verify step) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(step.timeoutSeconds()))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(step.url()))
                    .timeout(Duration.ofSeconds(step.timeoutSeconds()))
                    .GET()
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new StepOutcome.Succeeded();
            }
            return new StepOutcome.Failed("verify failed: HTTP " + response.statusCode() + " from " + step.url());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StepOutcome.Failed("verify interrupted: " + step.url());
        } catch (Exception e) {
            return new StepOutcome.Failed("verify failed: " + e.getMessage());
        }
    }

    private StepOutcome executeNotify(LifecycleStep.Notify step, String tenancyId) {
        try {
            notificationSink.send(step.channel(), step.message(), tenancyId);
            return new StepOutcome.Succeeded();
        } catch (Exception e) {
            return new StepOutcome.Failed("notify failed: " + e.getMessage());
        }
    }

    private StepOutcome executeWait(LifecycleStep.Wait step) {
        try {
            Thread.sleep(step.seconds() * 1000L);
            return new StepOutcome.Succeeded();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StepOutcome.Failed("wait interrupted");
        }
    }
}
