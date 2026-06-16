package com.example.llmshadow.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "shadow")
public record ShadowProperties(
        @Valid @DefaultValue Sqlite sqlite,
        @Valid @DefaultValue Queue queue,
        @Valid @DefaultValue Retry retry,
        @Valid @DefaultValue CircuitBreaker circuitBreaker,
        @Valid @DefaultValue Redaction redaction,
        @Valid @DefaultValue Executor executor) {

    public record Sqlite(
            @NotBlank
            @DefaultValue("./data/llm-shadow-proxy.sqlite")
            String path) {
    }

    public record Queue(
            @NotBlank
            @DefaultValue("redis")
            String backend,

            @NotBlank
            @DefaultValue("llm-shadow:shadow-jobs")
            String streamKey,

            @NotBlank
            @DefaultValue("llm-shadow-proxy")
            String group,

            @NotBlank
            @DefaultValue("llm-shadow-proxy")
            String consumer,

            @NotBlank
            @DefaultValue("llm-shadow:shadow-jobs:dead-letter")
            String deadLetterStream,

            @NotBlank
            @DefaultValue("llm-shadow:shadow-jobs:retry")
            String retryZset,

            @DurationUnit(ChronoUnit.MILLIS)
            @DefaultValue("250")
            Duration pollDelayMs,

            @Min(1)
            @DefaultValue("10")
            int batchSize,

            @DurationUnit(ChronoUnit.MILLIS)
            @DefaultValue("100")
            Duration readTimeoutMs) {
    }

    public record Retry(
            @Min(1)
            @DefaultValue("3")
            int maxAttempts,

            @DurationUnit(ChronoUnit.MILLIS)
            @DefaultValue("1000")
            Duration backoffMs) {
    }

    public record CircuitBreaker(
            @Min(1)
            @DefaultValue("3")
            int failureThreshold,

            @DurationUnit(ChronoUnit.MILLIS)
            @DefaultValue("10000")
            Duration openDurationMs) {
    }

    public record Redaction(
            @DefaultValue({"customerId", "email", "phone", "ssn", "token", "apiKey", "password"})
            List<String> sensitiveKeys) {

        public Redaction {
            sensitiveKeys = List.copyOf(sensitiveKeys);
        }
    }

    public record Executor(
            @Min(1)
            @DefaultValue("2")
            int corePoolSize,

            @Min(1)
            @DefaultValue("4")
            int maxPoolSize,

            @Min(1)
            @DefaultValue("100")
            int queueCapacity,

            @Min(1)
            @DefaultValue("3")
            int awaitTerminationSeconds) {
    }
}
