package com.example.llmshadow.dto;

import java.time.Instant;

public record ShadowComparisonJob(
        String requestId,
        LlmProxyRequest request,
        String primaryRawResponse,
        Instant createdAt) {
}
