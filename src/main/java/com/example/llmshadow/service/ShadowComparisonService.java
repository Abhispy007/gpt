package com.example.llmshadow.service;

import com.example.llmshadow.dto.ShadowComparisonJob;
import com.example.llmshadow.logging.MismatchLogger;
import com.example.llmshadow.persistence.MismatchRepository;
import com.example.llmshadow.queue.QueuedShadowJob;
import com.example.llmshadow.queue.ShadowJobQueue;
import com.example.llmshadow.service.JsonComparisonService.JsonComparisonResult;
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
    private final CandidateCircuitBreaker circuitBreaker;
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
            CandidateCircuitBreaker circuitBreaker,
            JsonComparisonService jsonComparisonService,
            MismatchLogger mismatchLogger,
            MismatchRepository mismatchRepository,
            RedactionService redactionService,
            ShadowJobQueue shadowJobQueue,
            @Value("${shadow.retry.max-attempts:3}") int maxAttempts,
            @Value("${shadow.retry.backoff-ms:1000}") long retryBackoffMs,
            @Value("${shadow.queue.batch-size:10}") int batchSize,
            ThreadPoolTaskExecutor shadowExecutor) {
        this.candidateLlmClient = candidateLlmClient;
        this.circuitBreaker = circuitBreaker;
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
        if (!circuitBreaker.allowRequest()) {
            shadowJobQueue.retry(job, job.attempts(), circuitBreaker.openUntil(), "Candidate circuit breaker open");
            mismatchLogger.logShadowError(job.requestId(), "CircuitOpen", "Candidate circuit breaker is open");
            return;
        }

        int currentAttempt = job.attempts() + 1;
        String candidateRawResponse;
        try {
            candidateRawResponse = candidateLlmClient.complete(job.request());
        } catch (RuntimeException ex) {
            circuitBreaker.recordFailure();
            retryOrFail(job, currentAttempt, ex.getClass().getSimpleName() + ": " + safeMessage(ex));
            mismatchLogger.logShadowError(job.requestId(), ex.getClass().getSimpleName(), safeMessage(ex));
            return;
        }

        circuitBreaker.recordSuccess();
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
