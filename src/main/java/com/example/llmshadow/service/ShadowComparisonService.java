package com.example.llmshadow.service;

import com.example.llmshadow.config.properties.ShadowProperties;
import com.example.llmshadow.dto.ShadowComparisonJob;
import com.example.llmshadow.logging.MismatchLogger;
import com.example.llmshadow.queue.QueuedShadowJob;
import com.example.llmshadow.queue.ShadowJobQueue;
import java.time.Instant;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ShadowComparisonService {

    private final MismatchLogger mismatchLogger;
    private final ShadowJobQueue shadowJobQueue;
    private final ShadowJobProcessor shadowJobProcessor;
    private final ShadowProperties shadowProperties;

    public ShadowComparisonService(
            MismatchLogger mismatchLogger,
            ShadowJobQueue shadowJobQueue,
            ShadowJobProcessor shadowJobProcessor,
            ShadowProperties shadowProperties) {
        this.mismatchLogger = mismatchLogger;
        this.shadowJobQueue = shadowJobQueue;
        this.shadowJobProcessor = shadowJobProcessor;
        this.shadowProperties = shadowProperties;
    }

    public void submit(ShadowComparisonJob job) {
        shadowJobQueue.publish(job);
        mismatchLogger.logDurableJobQueued(job.requestId());
    }

    @Scheduled(fixedDelayString = "${shadow.queue.poll-delay-ms:2000}")
    public void drainQueuedJobs() {
        try {
            int batchSize = shadowProperties.queue().batchSize();
            shadowJobQueue.moveDueRetriesToQueue(batchSize);
            shadowJobQueue.poll(batchSize).forEach(this::enqueue);
        } catch (RuntimeException ex) {
            mismatchLogger.logQueueError(ex.getClass().getSimpleName(), safeMessage(ex));
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
