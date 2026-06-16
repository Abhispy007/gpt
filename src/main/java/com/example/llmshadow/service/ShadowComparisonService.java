package com.example.llmshadow.service;

import com.example.llmshadow.config.properties.ShadowProperties;
import com.example.llmshadow.dto.ShadowComparisonJob;
import com.example.llmshadow.logging.MismatchLogger;
import com.example.llmshadow.persistence.ShadowOutboxRecord;
import com.example.llmshadow.persistence.ShadowOutboxRepository;
import com.example.llmshadow.queue.QueuedShadowJob;
import com.example.llmshadow.queue.ShadowJobQueue;
import java.time.Instant;
import java.util.List;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ShadowComparisonService {

    private final MismatchLogger mismatchLogger;
    private final ShadowJobQueue shadowJobQueue;
    private final ShadowJobProcessor shadowJobProcessor;
    private final ShadowOutboxRepository shadowOutboxRepository;
    private final ShadowProperties shadowProperties;

    public ShadowComparisonService(
            MismatchLogger mismatchLogger,
            ShadowJobQueue shadowJobQueue,
            ShadowJobProcessor shadowJobProcessor,
            ShadowOutboxRepository shadowOutboxRepository,
            ShadowProperties shadowProperties) {
        this.mismatchLogger = mismatchLogger;
        this.shadowJobQueue = shadowJobQueue;
        this.shadowJobProcessor = shadowJobProcessor;
        this.shadowOutboxRepository = shadowOutboxRepository;
        this.shadowProperties = shadowProperties;
    }

    public void submit(ShadowComparisonJob job) {
        try {
            shadowJobQueue.publish(job);
            mismatchLogger.logDurableJobQueued(job.requestId());
        } catch (RuntimeException ex) {
            shadowOutboxRepository.save(job, ex.getClass().getSimpleName() + ": " + safeMessage(ex));
            mismatchLogger.logShadowOutboxed(job.requestId(), safeMessage(ex));
        }
    }

    @Scheduled(fixedDelayString = "${shadow.queue.poll-delay-ms:2000}")
    public void drainQueuedJobs() {
        try {
            int batchSize = shadowProperties.queue().batchSize();
            replayOutbox(batchSize);
            shadowJobQueue.moveDueRetriesToQueue(batchSize);
            shadowJobQueue.recoverStalePending(batchSize).forEach(this::enqueue);
            shadowJobQueue.poll(batchSize).forEach(this::enqueue);
        } catch (RuntimeException ex) {
            mismatchLogger.logQueueError(ex.getClass().getSimpleName(), safeMessage(ex));
        }
    }

    private void replayOutbox(int batchSize) {
        List<ShadowOutboxRecord> records = shadowOutboxRepository.findBatch(batchSize);
        for (ShadowOutboxRecord record : records) {
            try {
                shadowJobQueue.publish(record.job());
                shadowOutboxRepository.delete(record.id());
                mismatchLogger.logShadowOutboxReplayed(record.job().requestId());
            } catch (RuntimeException ex) {
                mismatchLogger.logQueueError(ex.getClass().getSimpleName(), safeMessage(ex));
                return;
            }
        }
    }

    private void enqueue(QueuedShadowJob job) {
        try {
            shadowJobProcessor.process(job);
        } catch (TaskRejectedException ex) {
            shadowJobQueue.retry(
                    job,
                    job.attempts(),
                    Instant.now().plus(shadowProperties.retry().backoffMs()),
                    "Async executor rejected job: " + safeMessage(ex));
            mismatchLogger.logShadowDropped(job.requestId(), safeMessage(ex));
        }
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null ? "no message" : ex.getMessage();
    }
}
