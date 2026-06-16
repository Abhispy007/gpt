package com.example.llmshadow.queue;

import com.example.llmshadow.dto.ShadowComparisonJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shadow.queue.backend", havingValue = "memory")
public class InMemoryShadowJobQueue implements ShadowJobQueue {

    private final AtomicLong idSequence = new AtomicLong();
    private final ConcurrentLinkedQueue<QueuedShadowJob> queue = new ConcurrentLinkedQueue<>();
    private final DelayQueue<DelayedShadowJob> retryQueue = new DelayQueue<>();
    private final ConcurrentLinkedQueue<QueuedShadowJob> deadLetters = new ConcurrentLinkedQueue<>();

    @Override
    public void publish(ShadowComparisonJob job) {
        queue.add(new QueuedShadowJob(
                nextId(),
                job.requestId(),
                job.request(),
                job.primaryRawResponse(),
                job.createdAt(),
                0));
    }

    @Override
    public void moveDueRetriesToQueue(int batchSize) {
        for (int i = 0; i < batchSize; i++) {
            DelayedShadowJob delayed = retryQueue.poll();
            if (delayed == null) {
                return;
            }
            queue.add(delayed.job());
        }
    }

    @Override
    public List<QueuedShadowJob> recoverStalePending(int batchSize) {
        return List.of();
    }

    @Override
    public List<QueuedShadowJob> poll(int batchSize) {
        List<QueuedShadowJob> jobs = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            QueuedShadowJob job = queue.poll();
            if (job == null) {
                break;
            }
            jobs.add(job);
        }
        return jobs;
    }

    @Override
    public void acknowledge(QueuedShadowJob job) {
        // No-op: polling removes the in-memory job.
    }

    @Override
    public void retry(QueuedShadowJob job, int nextAttempt, Instant runAt, String reason) {
        retryQueue.add(new DelayedShadowJob(new QueuedShadowJob(
                nextId(),
                job.requestId(),
                job.request(),
                job.primaryRawResponse(),
                job.createdAt(),
                nextAttempt), runAt));
    }

    @Override
    public void deadLetter(QueuedShadowJob job, String reason) {
        deadLetters.add(job);
    }

    @Override
    public void clear() {
        queue.clear();
        retryQueue.clear();
        deadLetters.clear();
    }

    @Override
    public long queuedCount() {
        return queue.size();
    }

    @Override
    public long retryCount() {
        return retryQueue.size();
    }

    @Override
    public long deadLetterCount() {
        return deadLetters.size();
    }

    private String nextId() {
        return String.valueOf(idSequence.incrementAndGet());
    }

    private record DelayedShadowJob(QueuedShadowJob job, Instant runAt) implements Delayed {

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(runAt.toEpochMilli() - Instant.now().toEpochMilli(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
