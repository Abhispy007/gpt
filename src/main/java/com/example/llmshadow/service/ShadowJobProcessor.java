package com.example.llmshadow.service;

import com.example.llmshadow.config.properties.ShadowProperties;
import com.example.llmshadow.logging.MismatchLogger;
import com.example.llmshadow.queue.QueuedShadowJob;
import com.example.llmshadow.queue.ShadowJobQueue;
import com.example.llmshadow.service.JsonComparisonService.JsonComparisonResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ShadowJobProcessor {

    private final CandidateLlmClient candidateLlmClient;
    private final JsonComparisonService jsonComparisonService;
    private final MismatchLogger mismatchLogger;
    private final ShadowMetricsService shadowMetricsService;
    private final ShadowJobQueue shadowJobQueue;
    private final ShadowProperties shadowProperties;

    public ShadowJobProcessor(
            CandidateLlmClient candidateLlmClient,
            JsonComparisonService jsonComparisonService,
            MismatchLogger mismatchLogger,
            ShadowMetricsService shadowMetricsService,
            ShadowJobQueue shadowJobQueue,
            ShadowProperties shadowProperties) {
        this.candidateLlmClient = candidateLlmClient;
        this.jsonComparisonService = jsonComparisonService;
        this.mismatchLogger = mismatchLogger;
        this.shadowMetricsService = shadowMetricsService;
        this.shadowJobQueue = shadowJobQueue;
        this.shadowProperties = shadowProperties;
    }

    @Async("shadowTaskExecutor")
    public void process(QueuedShadowJob job) {
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
            candidateRawResponse = candidateLlmClient.complete(job.request());
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

        shadowMetricsService.recordCompletedComparison(job.requestId(), result);

        if (!result.matched()) {
            mismatchLogger.logMismatch(
                    job.requestId(),
                    result.primaryJson(),
                    result.candidateJson(),
                    result.primaryHash(),
                    result.candidateHash());
        }

        shadowJobQueue.acknowledge(job);
    }

    private void retryCircuitOpen(QueuedShadowJob job) {
        shadowJobQueue.retry(
                job,
                job.attempts(),
                Instant.now().plus(shadowProperties.circuitBreaker().openDurationMs()),
                "Candidate circuit breaker open");
    }

    private void retryOrFail(QueuedShadowJob job, int currentAttempt, String error) {
        if (currentAttempt >= shadowProperties.retry().maxAttempts()) {
            shadowJobQueue.deadLetter(job, error);
            mismatchLogger.logShadowDeadLettered(job.requestId(), error);
            return;
        }

        shadowJobQueue.retry(
                job,
                currentAttempt,
                Instant.now().plus(shadowProperties.retry().backoffMs()),
                error);
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null ? "no message" : ex.getMessage();
    }
}
