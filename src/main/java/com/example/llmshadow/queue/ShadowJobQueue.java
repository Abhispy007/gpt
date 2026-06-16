package com.example.llmshadow.queue;

import com.example.llmshadow.dto.ShadowComparisonJob;
import java.time.Instant;
import java.util.List;

public interface ShadowJobQueue {

    void publish(ShadowComparisonJob job);

    void moveDueRetriesToQueue(int batchSize);

    List<QueuedShadowJob> poll(int batchSize);

    void acknowledge(QueuedShadowJob job);

    void retry(QueuedShadowJob job, int nextAttempt, Instant runAt, String reason);

    void deadLetter(QueuedShadowJob job, String reason);

    void clear();
}
