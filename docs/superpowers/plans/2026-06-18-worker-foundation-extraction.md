# Worker Foundation Extraction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract Worker primitives from casehub-engine-api into a new foundation-tier casehub-worker repo, with execution governance types and PolicyEnforcer in casehub-platform.

**Architecture:** Four repos in dependency order — platform (governance types + enforcer), worker (new repo — api, runtime, testing), engine (migrate types out, adapt to composition), desiredstate (depend on worker-api). All Worker types move to `io.casehub.worker.api`. WorkerFunction becomes an extensible interface. ExecutionPolicy moves to `io.casehub.platform.api.governance`.

**Tech Stack:** Java 22, Quarkus 3.32, Mutiny, CDI, OTel API, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-18-worker-foundation-extraction-design.md`

---

## Phase 1: casehub-platform — Governance Foundation

**Repo:** `/Users/mdproctor/claude/casehub/platform`

### Task 1: Governance types in platform-api

**Files:**
- Create: `platform-api/src/main/java/io/casehub/platform/api/governance/BackoffStrategy.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/governance/RetryPolicy.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/governance/ExecutionPolicy.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/governance/ExecutionPolicyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.platform.api.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPolicyTest {

    @Test
    void defaultPolicy_hasDefaultRetry() {
        ExecutionPolicy policy = new ExecutionPolicy();
        assertThat(policy.timeoutMs()).isNull();
        assertThat(policy.retries()).isNotNull();
        assertThat(policy.retries().maxAttempts()).isEqualTo(3);
        assertThat(policy.retries().delayMs()).isEqualTo(10000);
        assertThat(policy.retries().backoffStrategy()).isEqualTo(BackoffStrategy.FIXED);
    }

    @Test
    void retryPolicy_defaultBackoff() {
        RetryPolicy retry = new RetryPolicy(5, 2000);
        assertThat(retry.backoffStrategy()).isEqualTo(BackoffStrategy.FIXED);
    }

    @Test
    void customPolicy() {
        RetryPolicy retry = new RetryPolicy(5, 500, BackoffStrategy.EXPONENTIAL_WITH_JITTER);
        ExecutionPolicy policy = new ExecutionPolicy(30000, retry);
        assertThat(policy.timeoutMs()).isEqualTo(30000);
        assertThat(policy.retries().maxAttempts()).isEqualTo(5);
        assertThat(policy.retries().backoffStrategy()).isEqualTo(BackoffStrategy.EXPONENTIAL_WITH_JITTER);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl platform-api test -Dtest=ExecutionPolicyTest -f /Users/mdproctor/claude/casehub/platform/pom.xml`
Expected: FAIL — classes do not exist

- [ ] **Step 3: Implement governance types**

`BackoffStrategy.java`:
```java
package io.casehub.platform.api.governance;

public enum BackoffStrategy {
    FIXED,
    EXPONENTIAL,
    EXPONENTIAL_WITH_JITTER
}
```

`RetryPolicy.java`:
```java
package io.casehub.platform.api.governance;

public record RetryPolicy(Integer maxAttempts, Integer delayMs, BackoffStrategy backoffStrategy) {

    public RetryPolicy() {
        this(3, 10000, BackoffStrategy.FIXED);
    }

    public RetryPolicy(Integer maxAttempts, Integer delayMs) {
        this(maxAttempts, delayMs, BackoffStrategy.FIXED);
    }
}
```

`ExecutionPolicy.java`:
```java
package io.casehub.platform.api.governance;

public record ExecutionPolicy(Integer timeoutMs, RetryPolicy retries) {

    public ExecutionPolicy() {
        this(null, new RetryPolicy());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl platform-api test -Dtest=ExecutionPolicyTest -f /Users/mdproctor/claude/casehub/platform/pom.xml`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/platform add platform-api/src/main/java/io/casehub/platform/api/governance/ platform-api/src/test/java/io/casehub/platform/api/governance/
git -C /Users/mdproctor/claude/casehub/platform commit -m "feat(#TBD): add execution governance types to platform-api — ExecutionPolicy, RetryPolicy, BackoffStrategy"
```

---

### Task 2: PolicyEnforcer governance submodule

**Files:**
- Create: `governance/pom.xml`
- Create: `governance/src/main/java/io/casehub/platform/governance/PolicyEnforcer.java`
- Create: `governance/src/main/java/io/casehub/platform/governance/DefaultPolicyEnforcer.java`
- Create: `governance/src/main/java/io/casehub/platform/governance/PolicyEnforcementException.java`
- Test: `governance/src/test/java/io/casehub/platform/governance/DefaultPolicyEnforcerTest.java`
- Modify: `pom.xml` (add governance to `<modules>`)

- [ ] **Step 1: Create governance module POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-platform-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>

    <artifactId>casehub-platform-governance</artifactId>

    <name>CaseHub Platform :: Governance</name>
    <description>Execution governance runtime — PolicyEnforcer applies retry, timeout,
        and backoff policies around any callable action.</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.smallrye.reactive</groupId>
            <artifactId>mutiny</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>

        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>${jandex-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals><goal>jandex</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write the failing test**

```java
package io.casehub.platform.governance;

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultPolicyEnforcerTest {

    private final PolicyEnforcer enforcer = new DefaultPolicyEnforcer();

    @Test
    void execute_noRetry_returnsResult() {
        ExecutionPolicy policy = new ExecutionPolicy(null, new RetryPolicy(1, 0));
        String result = enforcer.execute(policy, () -> "hello");
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void execute_retriesOnFailure() {
        AtomicInteger attempts = new AtomicInteger(0);
        ExecutionPolicy policy = new ExecutionPolicy(null, new RetryPolicy(3, 10));

        String result = enforcer.execute(policy, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void execute_exhaustsRetries_throws() {
        ExecutionPolicy policy = new ExecutionPolicy(null, new RetryPolicy(2, 10));

        assertThatThrownBy(() -> enforcer.execute(policy, () -> {
            throw new RuntimeException("permanent");
        }))
            .isInstanceOf(PolicyEnforcementException.class)
            .hasMessageContaining("2 attempts")
            .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void execute_exponentialBackoff_retriesSuccessfully() {
        AtomicInteger attempts = new AtomicInteger(0);
        ExecutionPolicy policy = new ExecutionPolicy(null,
            new RetryPolicy(3, 10, BackoffStrategy.EXPONENTIAL));

        String result = enforcer.execute(policy, () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("transient");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void execute_nullPolicy_executesOnce() {
        ExecutionPolicy policy = new ExecutionPolicy(null, null);
        String result = enforcer.execute(policy, () -> "direct");
        assertThat(result).isEqualTo("direct");
    }

    @Test
    void execute_timeout_failsIfExceeded() {
        ExecutionPolicy policy = new ExecutionPolicy(50, new RetryPolicy(1, 0));

        assertThatThrownBy(() -> enforcer.execute(policy, () -> {
            Thread.sleep(200);
            return "late";
        }))
            .isInstanceOf(PolicyEnforcementException.class)
            .hasMessageContaining("timed out");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl governance test -Dtest=DefaultPolicyEnforcerTest -f /Users/mdproctor/claude/casehub/platform/pom.xml`
Expected: FAIL — classes do not exist

- [ ] **Step 4: Implement PolicyEnforcer**

`PolicyEnforcer.java`:
```java
package io.casehub.platform.governance;

import io.casehub.platform.api.governance.ExecutionPolicy;

import java.util.function.Supplier;

public interface PolicyEnforcer {
    <T> T execute(ExecutionPolicy policy, Supplier<T> action);
}
```

`PolicyEnforcementException.java`:
```java
package io.casehub.platform.governance;

public class PolicyEnforcementException extends RuntimeException {

    public PolicyEnforcementException(String message) {
        super(message);
    }

    public PolicyEnforcementException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`DefaultPolicyEnforcer.java`:
```java
package io.casehub.platform.governance;

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@ApplicationScoped
public class DefaultPolicyEnforcer implements PolicyEnforcer {

    @Override
    public <T> T execute(ExecutionPolicy policy, Supplier<T> action) {
        int maxAttempts = 1;
        int delayMs = 0;
        BackoffStrategy backoff = BackoffStrategy.FIXED;

        if (policy.retries() != null) {
            RetryPolicy retry = policy.retries();
            if (retry.maxAttempts() != null) maxAttempts = retry.maxAttempts();
            if (retry.delayMs() != null) delayMs = retry.delayMs();
            if (retry.backoffStrategy() != null) backoff = retry.backoffStrategy();
        }

        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeWithTimeout(policy.timeoutMs(), action);
            } catch (PolicyEnforcementException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    sleep(computeDelay(delayMs, backoff, attempt));
                }
            }
        }
        throw new PolicyEnforcementException(
            "All " + maxAttempts + " attempts failed", lastException);
    }

    private <T> T executeWithTimeout(Integer timeoutMs, Supplier<T> action) {
        if (timeoutMs == null) {
            return action.get();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Callable<T> callable = action::get;
            Future<T> future = executor.submit(callable);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new PolicyEnforcementException(
                    "Action timed out after " + timeoutMs + "ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new PolicyEnforcementException("Action failed", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PolicyEnforcementException("Interrupted during execution", e);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private long computeDelay(int baseDelayMs, BackoffStrategy strategy, int attempt) {
        return switch (strategy) {
            case FIXED -> baseDelayMs;
            case EXPONENTIAL -> (long) (baseDelayMs * Math.pow(2, attempt - 1));
            case EXPONENTIAL_WITH_JITTER -> {
                long exponential = (long) (baseDelayMs * Math.pow(2, attempt - 1));
                yield exponential + ThreadLocalRandom.current().nextLong(exponential / 2 + 1);
            }
        };
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 5: Add governance module to parent POM**

In `/Users/mdproctor/claude/casehub/platform/pom.xml`, add `<module>governance</module>` to the `<modules>` list.

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl governance test -Dtest=DefaultPolicyEnforcerTest -f /Users/mdproctor/claude/casehub/platform/pom.xml`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/platform add governance/ pom.xml
git -C /Users/mdproctor/claude/casehub/platform commit -m "feat(#TBD): add PolicyEnforcer governance submodule — retry, timeout, backoff enforcement"
```

---

### Task 3: Build and install platform

- [ ] **Step 1: Run full platform build**

Run: `mvn --batch-mode install -f /Users/mdproctor/claude/casehub/platform/pom.xml`
Expected: BUILD SUCCESS — all existing tests pass, new governance module builds

- [ ] **Step 2: Commit if any adjustments were needed**

---

## Phase 2: casehub-worker — New Repo

**Repo:** New — needs creation at `/Users/mdproctor/claude/casehub/worker`

### Task 4: Create repo and module scaffold

- [ ] **Step 1: Create GitHub repo**

```bash
gh repo create casehubio/casehub-worker --private --description "Foundation-tier automated task primitives — Worker, WorkerFunction, Capability, execution policy"
```

- [ ] **Step 2: Clone and set up directory structure**

```bash
git clone https://github.com/casehubio/casehub-worker.git /Users/mdproctor/claude/casehub/worker
```

- [ ] **Step 3: Create parent POM**

Create `/Users/mdproctor/claude/casehub/worker/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>

    <artifactId>casehub-worker-parent</artifactId>
    <packaging>pom</packaging>

    <name>CaseHub Worker</name>
    <description>Foundation-tier automated task primitives — Worker, WorkerFunction,
        Capability, execution policy. Peer to casehub-work (human tasks).</description>

    <modules>
        <module>api</module>
        <module>runtime</module>
        <module>testing</module>
    </modules>
</project>
```

- [ ] **Step 4: Create api module POM**

Create `/Users/mdproctor/claude/casehub/worker/api/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-worker-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>

    <artifactId>casehub-worker-api</artifactId>

    <name>CaseHub Worker :: API</name>
    <description>Worker primitives — pure Java types for automated task definition.</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-api</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>${jandex-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals><goal>jandex</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 5: Create runtime module POM**

Create `/Users/mdproctor/claude/casehub/worker/runtime/pom.xml` — depends on `casehub-worker-api`, `casehub-platform-governance`, `quarkus-arc`, `opentelemetry-api`.

- [ ] **Step 6: Create testing module POM**

Create `/Users/mdproctor/claude/casehub/worker/testing/pom.xml` — depends on `casehub-worker-api`, scope test utilities.

- [ ] **Step 7: Commit scaffold**

```bash
git -C /Users/mdproctor/claude/casehub/worker add .
git -C /Users/mdproctor/claude/casehub/worker commit -m "init: casehub-worker repo scaffold — api, runtime, testing modules"
```

---

### Task 5: Worker API types — WorkerFunction, Capability, Worker

**Files:**
- Create: `api/src/main/java/io/casehub/worker/api/WorkerFunction.java`
- Create: `api/src/main/java/io/casehub/worker/api/Capability.java`
- Create: `api/src/main/java/io/casehub/worker/api/Worker.java`
- Test: `api/src/test/java/io/casehub/worker/api/WorkerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.worker.api;

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTest {

    @Test
    void syncWorker_executesFunction() {
        Worker worker = Worker.builder()
            .name("test-worker")
            .capability(Capability.of("process", "{}", "{}"))
            .function(input -> WorkerResult.of(Map.of("result", "done")))
            .build();

        assertThat(worker.name()).isEqualTo("test-worker");
        assertThat(worker.capabilities()).hasSize(1);
        assertThat(worker.capabilities().get(0).name()).isEqualTo("process");

        WorkerResult result = worker.function().execute(Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(result.output()).containsEntry("result", "done");
    }

    @Test
    void worker_defaultExecutionPolicy() {
        Worker worker = Worker.builder()
            .name("default-policy")
            .capability(Capability.of("test", "{}", "{}"))
            .function(input -> WorkerResult.of(Map.of()))
            .build();

        assertThat(worker.executionPolicy()).isNotNull();
        assertThat(worker.executionPolicy().retries().maxAttempts()).isEqualTo(3);
    }

    @Test
    void worker_customExecutionPolicy() {
        ExecutionPolicy policy = new ExecutionPolicy(5000,
            new RetryPolicy(5, 500, BackoffStrategy.EXPONENTIAL));

        Worker worker = Worker.builder()
            .name("custom-policy")
            .capability(Capability.of("test", "{}", "{}"))
            .function(input -> WorkerResult.of(Map.of()))
            .executionPolicy(policy)
            .build();

        assertThat(worker.executionPolicy().timeoutMs()).isEqualTo(5000);
        assertThat(worker.executionPolicy().retries().maxAttempts()).isEqualTo(5);
    }

    @Test
    void workerResult_factoryMethods() {
        WorkerResult success = WorkerResult.of(Map.of("key", "value"));
        assertThat(success.outcome()).isInstanceOf(WorkerOutcome.Success.class);

        WorkerResult declined = WorkerResult.declined("not my job");
        assertThat(declined.outcome()).isInstanceOf(WorkerOutcome.Declined.class);

        WorkerResult failed = WorkerResult.failed("broken");
        assertThat(failed.outcome()).isInstanceOf(WorkerOutcome.Failed.class);

        WorkerResult expired = WorkerResult.expired("too slow");
        assertThat(expired.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
    }

    @Test
    void capability_withDescription() {
        Capability cap = Capability.builder()
            .name("analyse")
            .inputSchema("{\"type\":\"object\"}")
            .outputSchema("{\"type\":\"object\"}")
            .description("Analyses input data")
            .build();

        assertThat(cap.name()).isEqualTo("analyse");
        assertThat(cap.description()).isEqualTo("Analyses input data");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl api test -Dtest=WorkerTest -f /Users/mdproctor/claude/casehub/worker/pom.xml`
Expected: FAIL — classes do not exist

- [ ] **Step 3: Implement WorkerOutcome and WorkerResult**

`WorkerOutcome.java`:
```java
package io.casehub.worker.api;

public sealed interface WorkerOutcome {

    static WorkerOutcome success() {
        return new Success();
    }

    record Success() implements WorkerOutcome {}
    record Declined(String reason) implements WorkerOutcome {}
    record Failed(String reason) implements WorkerOutcome {}
    record Expired(String reason) implements WorkerOutcome {}
}
```

`WorkerResult.java`:
```java
package io.casehub.worker.api;

import java.util.Map;

public record WorkerResult(Map<String, Object> output, WorkerOutcome outcome) {

    public static WorkerResult of(Map<String, Object> output) {
        return new WorkerResult(output, WorkerOutcome.success());
    }

    public static WorkerResult declined(String reason) {
        return new WorkerResult(Map.of(), new WorkerOutcome.Declined(reason));
    }

    public static WorkerResult failed(String reason) {
        return new WorkerResult(Map.of(), new WorkerOutcome.Failed(reason));
    }

    public static WorkerResult expired(String reason) {
        return new WorkerResult(Map.of(), new WorkerOutcome.Expired(reason));
    }
}
```

- [ ] **Step 4: Implement WorkerFunction**

```java
package io.casehub.worker.api;

import java.util.Map;
import java.util.function.Function;

public interface WorkerFunction {

    WorkerResult execute(Map<String, Object> input);

    record Sync(Function<Map<String, Object>, WorkerResult> fn) implements WorkerFunction {
        @Override
        public WorkerResult execute(Map<String, Object> input) {
            return fn.apply(input);
        }
    }
}
```

- [ ] **Step 5: Implement Capability**

```java
package io.casehub.worker.api;

import java.util.Objects;

public record Capability(String name, String inputSchema, String outputSchema, String description) {

    public Capability {
        Objects.requireNonNull(name);
        Objects.requireNonNull(inputSchema);
        Objects.requireNonNull(outputSchema);
    }

    public static Capability of(String name, String inputSchema, String outputSchema) {
        return new Capability(name, inputSchema, outputSchema, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String inputSchema;
        private String outputSchema;
        private String description;

        public Builder name(String name) { this.name = name; return this; }
        public Builder inputSchema(String inputSchema) { this.inputSchema = inputSchema; return this; }
        public Builder outputSchema(String outputSchema) { this.outputSchema = outputSchema; return this; }
        public Builder description(String description) { this.description = description; return this; }

        public Capability build() {
            return new Capability(name, inputSchema, outputSchema, description);
        }
    }
}
```

- [ ] **Step 6: Implement Worker**

```java
package io.casehub.worker.api;

import io.casehub.platform.api.governance.ExecutionPolicy;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record Worker(String name, List<Capability> capabilities, WorkerFunction function,
                     ExecutionPolicy executionPolicy, String description) {

    public Worker {
        Objects.requireNonNull(name);
        Objects.requireNonNull(capabilities);
        Objects.requireNonNull(function);
        if (executionPolicy == null) {
            executionPolicy = new ExecutionPolicy();
        }
        capabilities = List.copyOf(capabilities);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private List<Capability> capabilities;
        private WorkerFunction function;
        private ExecutionPolicy executionPolicy;
        private String description;

        public Builder name(String name) { this.name = name; return this; }

        public Builder capabilities(Capability... capabilities) {
            this.capabilities = Arrays.asList(capabilities);
            return this;
        }

        public Builder capabilities(List<Capability> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder capability(Capability capability) {
            this.capabilities = List.of(capability);
            return this;
        }

        public Builder function(WorkerFunction function) {
            this.function = function;
            return this;
        }

        public Builder function(java.util.function.Function<java.util.Map<String, Object>, WorkerResult> fn) {
            this.function = new WorkerFunction.Sync(fn);
            return this;
        }

        public Builder executionPolicy(ExecutionPolicy policy) {
            this.executionPolicy = policy;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Worker build() {
            return new Worker(name, capabilities, function, executionPolicy, description);
        }
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn -pl api test -Dtest=WorkerTest -f /Users/mdproctor/claude/casehub/worker/pom.xml`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/worker add api/
git -C /Users/mdproctor/claude/casehub/worker commit -m "feat: worker-api — Worker, WorkerFunction, Capability, WorkerResult, WorkerOutcome"
```

---

### Task 6: Worker runtime — WorkerExecutor

**Files:**
- Create: `runtime/src/main/java/io/casehub/worker/runtime/WorkerExecutor.java`
- Test: `runtime/src/test/java/io/casehub/worker/runtime/WorkerExecutorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.worker.runtime;

import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.governance.DefaultPolicyEnforcer;
import io.casehub.platform.governance.PolicyEnforcementException;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerExecutorTest {

    private final WorkerExecutor executor = new WorkerExecutor(new DefaultPolicyEnforcer());

    @Test
    void execute_successfulWorker() {
        Worker worker = Worker.builder()
            .name("greet")
            .capability(Capability.of("greet", "{}", "{}"))
            .function(input -> WorkerResult.of(Map.of("greeting", "hello " + input.get("name"))))
            .build();

        WorkerResult result = executor.execute(worker, Map.of("name", "world"));
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(result.output()).containsEntry("greeting", "hello world");
    }

    @Test
    void execute_retriesTransientFailures() {
        AtomicInteger attempts = new AtomicInteger(0);
        Worker worker = Worker.builder()
            .name("flaky")
            .capability(Capability.of("process", "{}", "{}"))
            .function(input -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new RuntimeException("transient");
                }
                return WorkerResult.of(Map.of("recovered", true));
            })
            .executionPolicy(new ExecutionPolicy(null, new RetryPolicy(3, 10)))
            .build();

        WorkerResult result = executor.execute(worker, Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void execute_exhaustsRetries_throwsPolicyException() {
        Worker worker = Worker.builder()
            .name("broken")
            .capability(Capability.of("fail", "{}", "{}"))
            .function(input -> { throw new RuntimeException("permanent"); })
            .executionPolicy(new ExecutionPolicy(null, new RetryPolicy(2, 10)))
            .build();

        assertThatThrownBy(() -> executor.execute(worker, Map.of()))
            .isInstanceOf(PolicyEnforcementException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl runtime test -Dtest=WorkerExecutorTest -f /Users/mdproctor/claude/casehub/worker/pom.xml`
Expected: FAIL — WorkerExecutor does not exist

- [ ] **Step 3: Implement WorkerExecutor**

```java
package io.casehub.worker.runtime;

import io.casehub.platform.governance.PolicyEnforcer;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class WorkerExecutor {

    private static final String INSTRUMENTATION_NAME = "io.casehub.worker";

    private final PolicyEnforcer policyEnforcer;

    @Inject
    public WorkerExecutor(PolicyEnforcer policyEnforcer) {
        this.policyEnforcer = policyEnforcer;
    }

    public WorkerResult execute(Worker worker, Map<String, Object> input) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME)
            .spanBuilder("worker.execute")
            .setAttribute(AttributeKey.stringKey("worker.name"), worker.name())
            .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            WorkerResult result = policyEnforcer.execute(
                worker.executionPolicy(),
                () -> worker.function().execute(input));
            span.setAttribute(AttributeKey.stringKey("worker.outcome"),
                result.outcome().getClass().getSimpleName());
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl runtime test -Dtest=WorkerExecutorTest -f /Users/mdproctor/claude/casehub/worker/pom.xml`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/worker add runtime/
git -C /Users/mdproctor/claude/casehub/worker commit -m "feat: worker runtime — WorkerExecutor with PolicyEnforcer + OTel tracing"
```

---

### Task 7: Worker testing — MockWorkerExecutor

**Files:**
- Create: `testing/src/main/java/io/casehub/worker/testing/MockWorkerExecutor.java`
- Create: `testing/src/main/java/io/casehub/worker/testing/TestWorkerBuilder.java`
- Test: `testing/src/test/java/io/casehub/worker/testing/MockWorkerExecutorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.worker.testing;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockWorkerExecutorTest {

    @Test
    void execute_bypassesPolicyEnforcement() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = TestWorkerBuilder.sync("test", input -> WorkerResult.of(Map.of("ok", true)));

        WorkerResult result = executor.execute(worker, Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(executor.executionCount()).isEqualTo(1);
        assertThat(executor.lastWorkerName()).isEqualTo("test");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: Implement MockWorkerExecutor and TestWorkerBuilder**

`MockWorkerExecutor.java`:
```java
package io.casehub.worker.testing;

import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MockWorkerExecutor {

    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicReference<String> lastWorkerName = new AtomicReference<>();

    public WorkerResult execute(Worker worker, Map<String, Object> input) {
        executionCount.incrementAndGet();
        lastWorkerName.set(worker.name());
        return worker.function().execute(input);
    }

    public int executionCount() {
        return executionCount.get();
    }

    public String lastWorkerName() {
        return lastWorkerName.get();
    }

    public void reset() {
        executionCount.set(0);
        lastWorkerName.set(null);
    }
}
```

`TestWorkerBuilder.java`:
```java
package io.casehub.worker.testing;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;

import java.util.Map;
import java.util.function.Function;

public final class TestWorkerBuilder {

    private TestWorkerBuilder() {}

    public static Worker sync(String name, Function<Map<String, Object>, WorkerResult> fn) {
        return Worker.builder()
            .name(name)
            .capability(Capability.of(name, "{}", "{}"))
            .function(fn)
            .build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/worker add testing/
git -C /Users/mdproctor/claude/casehub/worker commit -m "feat: worker-testing — MockWorkerExecutor + TestWorkerBuilder"
```

---

### Task 8: Build and install worker

- [ ] **Step 1: Run full build**

Run: `mvn --batch-mode install -f /Users/mdproctor/claude/casehub/worker/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 2: Push**

```bash
git -C /Users/mdproctor/claude/casehub/worker push -u origin main
```

---

## Phase 3: casehub-engine — Migration

**Repo:** `/Users/mdproctor/claude/casehub/engine`

This phase removes the Worker primitive types from engine-api, replaces them with a `casehub-worker-api` dependency, and adapts the engine's internal usage to the composition model.

### Task 9: Add worker-api dependency, remove migrated types

**Files:**
- Modify: `api/pom.xml` — add `casehub-worker-api` dependency
- Delete: `api/src/main/java/io/casehub/api/model/WorkerFunction.java`
- Delete: `api/src/main/java/io/casehub/api/model/WorkerOutcome.java`
- Delete: `api/src/main/java/io/casehub/api/model/Capability.java`
- Delete: `api/src/main/java/io/casehub/api/model/ExecutionPolicy.java`
- Delete: `api/src/main/java/io/casehub/api/model/RetryPolicy.java`
- Delete: `api/src/main/java/io/casehub/api/model/BackoffStrategy.java`

- [ ] **Step 1: Add casehub-worker-api dependency to engine api/pom.xml**

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-worker-api</artifactId>
</dependency>
```

Also add `casehub-platform-governance` dependency where the engine runtime needs `PolicyEnforcer` (if applicable — may not be needed if the engine has its own execution mechanisms).

- [ ] **Step 2: Delete migrated types from engine-api**

Delete the files listed above. These types now come from `casehub-worker-api` and `casehub-platform-api`.

- [ ] **Step 3: Update all imports across engine modules**

Use IntelliJ's rename refactoring across the engine repo:
- `io.casehub.api.model.Worker` → `io.casehub.worker.api.Worker`
- `io.casehub.api.model.WorkerFunction` → `io.casehub.worker.api.WorkerFunction`
- `io.casehub.api.model.WorkerResult` → `io.casehub.worker.api.WorkerResult`
- `io.casehub.api.model.WorkerOutcome` → `io.casehub.worker.api.WorkerOutcome`
- `io.casehub.api.model.Capability` → `io.casehub.worker.api.Capability`
- `io.casehub.api.model.ExecutionPolicy` → `io.casehub.platform.api.governance.ExecutionPolicy`
- `io.casehub.api.model.RetryPolicy` → `io.casehub.platform.api.governance.RetryPolicy`
- `io.casehub.api.model.BackoffStrategy` → `io.casehub.platform.api.governance.BackoffStrategy`

- [ ] **Step 4: Commit deletion and import updates**

```bash
git -C /Users/mdproctor/claude/casehub/engine commit -am "refactor(#TBD): migrate Worker primitives to casehub-worker-api, governance types to casehub-platform-api"
```

---

### Task 10: Adapt Worker — PlanElement, AgentDescriptor, PlannedAction

The engine's `Worker` class previously implemented `PlanElement` and carried an `AgentDescriptor` field. The foundation `Worker` record has neither. The engine must adapt.

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/Worker.java` — this file was deleted in Task 9. The engine no longer owns Worker. Instead, adapt the code that USES Worker.
- Modify: Code that calls `worker.agentDescriptor()` — use a lookup/registry instead
- Modify: Code that calls `worker instanceof PlanElement` — adapt plan model
- Modify: `api/src/main/java/io/casehub/api/model/WorkerResult.java` — deleted in Task 9. Adapt PlannedAction extraction.

- [ ] **Step 1: Adapt PlanElement**

Search engine codebase for `PlanElement` usage with Worker. The engine's `CaseDefinition` builder accepts `List<Worker>`. If PlanElement is used for type-level grouping with Bindings (which also implement PlanElement), the engine needs a wrapper:

Create `api/src/main/java/io/casehub/api/plan/PlanWorker.java`:
```java
package io.casehub.api.plan;

import io.casehub.worker.api.Worker;

public record PlanWorker(Worker worker) implements PlanElement {
    public String name() { return worker.name(); }
}
```

Replace `Worker` with `PlanWorker` in plan-model code that requires `PlanElement`.

- [ ] **Step 2: Adapt AgentDescriptor**

The engine's `WorkOrchestrator` called `worker.agentDescriptor()` for capability probe. With the foundation Worker record, this field is gone. Create an association map:

In the engine's dispatch code, maintain a `Map<String, AgentDescriptor>` (worker name → descriptor) that is populated at case definition build time. `CaseDefinition.Builder` accepts an optional descriptor per worker.

- [ ] **Step 3: Adapt PlannedAction**

Foundation `WorkerResult` has no `PlannedAction`. The engine recovers it via well-known key in the output map:

```java
private PlannedAction extractPlannedAction(WorkerResult result) {
    Object raw = result.output().get("_plannedAction");
    if (raw instanceof PlannedAction pa) return pa;
    return null;
}
```

Workers that need action risk classification put the `PlannedAction` in their output map under `"_plannedAction"`.

- [ ] **Step 4: Move WorkerFunction.AgentExec to engine-api**

Create `api/src/main/java/io/casehub/api/model/AgentWorkerFunction.java`:
```java
package io.casehub.api.model;

import io.casehub.api.model.ai.Agent;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;

import java.util.Map;

public record AgentWorkerFunction(Agent agent) implements WorkerFunction {
    @Override
    public WorkerResult execute(Map<String, Object> input) {
        return agent.execute(input);
    }
}
```

- [ ] **Step 5: Move WorkerFunction.Flow to engine-flow**

Create `flow/src/main/java/io/casehub/engine/flow/FlowWorkerFunction.java`:
```java
package io.casehub.engine.flow;

import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.serverlessworkflow.api.types.Workflow;

import java.util.Map;

public record FlowWorkerFunction(Workflow workflow) implements WorkerFunction {
    @Override
    public WorkerResult execute(Map<String, Object> input) {
        throw new UnsupportedOperationException(
            "Flow execution is handled by FlowWorkerExecutor, not by direct execute()");
    }

    public Workflow workflow() {
        return workflow;
    }
}
```

Note: `FlowWorkerFunction.execute()` throws because Flow execution is async and handled by the engine's `FlowWorkerExecutor` which calls `WorkflowApplication.workflowDefinition(workflow).instance(input).start()`. The engine detects `instanceof FlowWorkerFunction` and routes accordingly.

- [ ] **Step 6: Run engine tests**

Run: `mvn --batch-mode test -f /Users/mdproctor/claude/casehub/engine/pom.xml`
Expected: All tests pass after adaptations

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/engine commit -am "refactor(#TBD): adapt engine to foundation Worker — composition for PlanElement, AgentDescriptor, PlannedAction"
```

---

### Task 11: Build and install engine

- [ ] **Step 1: Full build**

Run: `mvn --batch-mode install -f /Users/mdproctor/claude/casehub/engine/pom.xml`
Expected: BUILD SUCCESS

---

## Phase 4: casehub-desiredstate — Integration

**Repo:** `/Users/mdproctor/claude/casehub/desiredstate`

### Task 12: Add worker-api dependency to desiredstate

**Files:**
- Modify: `api/pom.xml` — add `casehub-worker-api` dependency
- Modify: `engine-adapter/pom.xml` — update Worker imports (now from worker-api)

- [ ] **Step 1: Add dependency to api/pom.xml**

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-worker-api</artifactId>
</dependency>
```

- [ ] **Step 2: Update engine-adapter imports**

`CaseTransitionExecutor.java` and `TransitionWorkflowGenerator.java` import Worker-related types from engine-api. After the engine migration, these now come from worker-api. Update imports:
- `io.casehub.api.model.Worker` → `io.casehub.worker.api.Worker`
- `io.casehub.api.model.Capability` → `io.casehub.worker.api.Capability`

- [ ] **Step 3: Build desiredstate**

Run: `mvn --batch-mode install -f /Users/mdproctor/claude/casehub/desiredstate/pom.xml`
Expected: BUILD SUCCESS — all 32+ existing tests pass

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add api/pom.xml engine-adapter/
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#40): depend on casehub-worker-api — Worker primitives now at foundation tier"
```

---

## Phase 5: Cross-repo consumer migration

### Task 13: File migration issues for remaining consumers

These repos import Worker types from engine-api and need import updates. File one issue per repo:

- [ ] **Step 1: File issues**

| Repo | Issue title |
|------|-----------|
| casehubio/claudony | refactor: migrate Worker imports to casehub-worker-api |
| casehubio/casehub-workers | refactor: migrate Worker imports to casehub-worker-api |
| casehubio/casehub-openclaw | refactor: migrate Worker imports to casehub-worker-api |
| casehubio/casehub-ops | refactor: migrate Worker imports to casehub-worker-api |

Application repos (devtown, aml, clinical, life, quarkmind) get issues when they next build against the updated engine.

- [ ] **Step 2: Update PLATFORM.md**

Add casehub-worker to Repository Map, Build Order, Capability Ownership, and Cross-Repo Dependency Map in `/Users/mdproctor/claude/casehub/parent/docs/PLATFORM.md`.

---

## Summary

| Phase | Repo | What | Estimated tasks |
|-------|------|------|----------------|
| 1 | casehub-platform | Governance types + PolicyEnforcer | 3 tasks |
| 2 | casehub-worker | New repo — api, runtime, testing | 5 tasks |
| 3 | casehub-engine | Migrate types, adapt composition | 3 tasks |
| 4 | casehub-desiredstate | Depend on worker-api | 1 task |
| 5 | Cross-repo | File migration issues, update PLATFORM.md | 1 task |
