package com.example.llmshadow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shadow.sqlite.path=/tmp/llm-shadow-proxy-guardrails-test.sqlite",
                "shadow.queue.backend=memory",
                "shadow.queue.poll-delay-ms=5000",
                "app.request-limits.max-estimated-tokens=10",
                "resilience4j.ratelimiter.instances.proxy.limit-for-period=1000"
        })
class ProductionGuardrailIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void rejectsUnsupportedStreamingRequests() {
        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "stream-1"),
                "stream", true);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/proxy", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Streaming responses are not supported");
    }

    @Test
    void rejectsRequestsAboveEstimatedTokenLimit() {
        Map<String, Object> request = Map.of(
                "prompt", "This prompt is intentionally long enough to trip the tiny test token limit.",
                "input", Map.of("customerId", "too-large"));

        ResponseEntity<String> response = restTemplate.postForEntity("/api/proxy", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).contains("estimated token limit");
    }
}
