package com.example.llmshadow.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.example.llmshadow.config.properties.ShadowProperties;
import com.example.llmshadow.dto.LlmProxyRequest;
import com.example.llmshadow.dto.ShadowComparisonJob;
import com.example.llmshadow.logging.MismatchLogger;
import com.example.llmshadow.persistence.ShadowOutboxRepository;
import com.example.llmshadow.queue.ShadowJobQueue;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShadowComparisonServiceTest {

    @Test
    void submitBuffersJobInOutboxWhenQueuePublishFails() {
        MismatchLogger mismatchLogger = mock(MismatchLogger.class);
        ShadowJobQueue shadowJobQueue = mock(ShadowJobQueue.class);
        ShadowJobProcessor shadowJobProcessor = mock(ShadowJobProcessor.class);
        ShadowOutboxRepository shadowOutboxRepository = mock(ShadowOutboxRepository.class);
        ShadowProperties shadowProperties = mock(ShadowProperties.class);
        ShadowComparisonJob job = new ShadowComparisonJob(
                "request-1",
                new LlmProxyRequest("Return customer tier", Map.of("customerId", "1"), false, false, 0, false),
                "{\"model\":\"primary\"}",
                Instant.now());

        RuntimeException publishFailure = new IllegalStateException("redis unavailable");
        doThrow(publishFailure).when(shadowJobQueue).publish(job);

        ShadowComparisonService service = new ShadowComparisonService(
                mismatchLogger,
                shadowJobQueue,
                shadowJobProcessor,
                shadowOutboxRepository,
                shadowProperties);

        service.submit(job);

        verify(shadowOutboxRepository).save(job, "IllegalStateException: redis unavailable");
        verify(mismatchLogger).logShadowOutboxed("request-1", "redis unavailable");
    }
}
