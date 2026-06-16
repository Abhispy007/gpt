package com.example.llmshadow.queue;

import com.example.llmshadow.dto.LlmProxyRequest;
import java.time.Instant;

public record QueuedShadowJob(
        String messageId,
        String requestId,
        LlmProxyRequest request,
        String primaryRawResponse,
        Instant createdAt,
        int attempts) {
}
