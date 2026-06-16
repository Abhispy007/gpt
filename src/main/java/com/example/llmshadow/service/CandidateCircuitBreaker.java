package com.example.llmshadow.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CandidateCircuitBreaker {

    private final boolean enabled;
    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant openUntil = Instant.EPOCH;

    public CandidateCircuitBreaker(
            @Value("${shadow.circuit-breaker.enabled:true}") boolean enabled,
            @Value("${shadow.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${shadow.circuit-breaker.open-duration-ms:10000}") long openDurationMs) {
        this.enabled = enabled;
        this.failureThreshold = failureThreshold;
        this.openDuration = Duration.ofMillis(openDurationMs);
    }

    public boolean allowRequest() {
        return !enabled || Instant.now().isAfter(openUntil);
    }

    public Instant openUntil() {
        return openUntil;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil = Instant.EPOCH;
    }

    public void recordFailure() {
        if (!enabled) {
            return;
        }

        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntil = Instant.now().plus(openDuration);
        }
    }
}
