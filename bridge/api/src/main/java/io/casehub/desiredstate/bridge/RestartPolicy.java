package io.casehub.desiredstate.bridge;

public record RestartPolicy(RestartPolicyType policy, int maxRetries, BackoffStrategy backoff) {
    public RestartPolicy {
        if (backoff == null) backoff = BackoffStrategy.EXPONENTIAL;
    }
}
