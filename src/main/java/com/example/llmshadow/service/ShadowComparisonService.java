package com.example.llmshadow.service;

import com.example.llmshadow.dto.ShadowComparisonJob;
import com.example.llmshadow.logging.MismatchLogger;
import com.example.llmshadow.persistence.MismatchRepository;
import com.example.llmshadow.queue.QueuedShadowJob;
import com.example.llmshadow.queue.ShadowJobQueue;
import com.example.llmshadow.service.JsonComparisonService.JsonComparisonResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class ShadowComparisonService {

    private final CandidateLlmClient candidateLlmClient;
    private final CircuitBreaker candidateCircuitBreaker;
    private final boolean circuitBreakerEnabled;
    private final long circuitBreakerOpenDurationMs;
    private final JsonComparisonService jsonComparisonService;
    private final MismatchLogger mismatchLogger;
    private final MismatchRepository mismatchRepository;
    private final RedactionService redactionService;
    private final ShadowJobQueue shadowJobQueue;
    private final ThreadPoolTaskExecutor shadowExecutor;
    private final int maxAttempts;
    private final long retryBackoffMs;
    private final int batchSize;

    public ShadowComparisonService(
            CandidateLlmClient candidateLlmClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            JsonComparisonService jsonComparisonService,
            MismatchLogger mismatchLogger,
            MismatchRepository mismatchRepository,
            RedactionService redactionService,
            ShadowJobQueue shadowJobQueue,
            @Value("${shadow.retry.max-attempts:3}") int maxAttempts,
            @Value("${shadow.retry.backoff-ms:1000}") long retryBackoffMs,
            @Value("${shadow.circuit-breaker.enabled:true}") boolean circuitBreakerEnabled,
            @Value("${shadow.circuit-breaker.open-duration-ms:10000}") long circuitBreakerOpenDurationMs,
            @Value("${shadow.queue.batch-size:10}") int batchSize,
            ThreadPoolTaskExecutor shadowExecutor) {
        this.candidateLlmClient = candidateLlmClient;
        this.candidateCircuitBreaker = circuitBreakerRegistry.circuitBreaker("candidate");
        this.circuitBreakerEnabled = circuitBreakerEnabled;
        this.circuitBreakerOpenDurationMs = circuitBreakerOpenDurationMs;
        this.jsonComparisonService = jsonComparisonService;
        this.mismatchLogger = mismatchLogger;
        this.mismatchRepository = mismatchRepository;
        this.redactionService = redactionService;
        this.shadowJobQueue = shadowJobQueue;
        this.shadowExecutor = shadowExecutor;
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
        this.batchSize = batchSize;
    }

    public void submit(ShadowComparisonJob job) {
        shadowJobQueue.publish(job);
        mismatchLogger.logDurableJobQueued(job.requestId());
    }

    @Scheduled(fixedDelayString = "${shadow.queue.poll-delay-ms:2000}")
    public void drainQueuedJobs() {
        try {
            shadowJobQueue.moveDueRetriesToQueue(batchSize);
            shadowJobQueue.poll(batchSize).forEach(this::enqueue);
        } catch (RuntimeException ex) {
            mismatchLogger.logQueueError(ex.getClass().getSimpleName(), safeMessage(ex));
        }
    }

    private void enqueue(QueuedShadowJob job) {
        try {
            shadowExecutor.execute(() -> processQueuedJob(job));
        } catch (RejectedExecutionException ex) {
            retryOrFail(job, job.attempts(), "Executor rejected job: " + safeMessage(ex));
            mismatchLogger.logShadowDropped(job.requestId(), safeMessage(ex));
        }
    }

    private void processQueuedJob(QueuedShadowJob job) {
        MDC.put("requestId", job.requestId());
        try {
            compare(job);
        } catch (RuntimeException ex) {
            retryOrFail(job, job.attempts() + 1, ex.getClass().getSimpleName() + ": " + safeMessage(ex));
            mismatchLogger.logShadowError(job.requestId(), ex.getClass().getSimpleName(), safeMessage(ex));
        } finally {
            MDC.remove("requestId");
        }
    }

    private void compare(QueuedShadowJob job) {
        int currentAttempt = job.attempts() + 1;
        String candidateRawResponse;
        try {
            candidateRawResponse = completeCandidate(job);
        } catch (CallNotPermittedException ex) {
            retryCircuitOpen(job);
            mismatchLogger.logShadowError(job.requestId(), "CircuitOpen", "Candidate circuit breaker is open");
            return;
        } catch (RuntimeException ex) {
            retryOrFail(job, currentAttempt, ex.getClass().getSimpleName() + ": " + safeMessage(ex));
            mismatchLogger.logShadowError(job.requestId(), ex.getClass().getSimpleName(), safeMessage(ex));
            return;
        }

        JsonComparisonResult result = jsonComparisonService.compareOutputs(job.primaryRawResponse(), candidateRawResponse);
        if (!result.comparable()) {
            retryOrFail(job, currentAttempt, "JSON extraction failed for " + result.failedSource() + ": " + result.error());
            mismatchLogger.logJsonExtractionError(job.requestId(), result.failedSource(), result.error());
            return;
        }

        if (!result.matched()) {
            mismatchRepository.save(
                    job.requestId(),
                    redactionService.redactToString(result.primaryJson()),
                    redactionService.redactToString(result.candidateJson()));
            mismatchLogger.logMismatch(job.requestId(), result.primaryJson(), result.candidateJson());
        }

        shadowJobQueue.acknowledge(job);
    }

    private String completeCandidate(QueuedShadowJob job) {
        if (!circuitBreakerEnabled) {
            return candidateLlmClient.complete(job.request());
        }

        return candidateCircuitBreaker.executeSupplier(() -> candidateLlmClient.complete(job.request()));
    }

    private void retryCircuitOpen(QueuedShadowJob job) {
        shadowJobQueue.retry(
                job,
                job.attempts(),
                Instant.now().plusMillis(circuitBreakerOpenDurationMs),
                "Candidate circuit breaker open");
    }

    private void retryOrFail(QueuedShadowJob job, int currentAttempt, String error) {
        if (currentAttempt >= maxAttempts) {
            shadowJobQueue.deadLetter(job, error);
            mismatchLogger.logShadowDeadLettered(job.requestId(), error);
            return;
        }

        shadowJobQueue.retry(job, currentAttempt, Instant.now().plusMillis(retryBackoffMs), error);
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null ? "no message" : ex.getMessage();
    }
}
