package com.example.llmshadow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.llmshadow.dto.LlmProxyRequest;
import com.example.llmshadow.dto.ShadowComparisonJob;
import com.example.llmshadow.queue.InMemoryShadowJobQueue;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shadow.sqlite.path=/tmp/llm-shadow-proxy-backpressure-test.sqlite",
                "shadow.queue.backend=memory",
                "shadow.queue.poll-delay-ms=5000",
                "shadow.backpressure.max-queued-jobs=1",
                "resilience4j.ratelimiter.instances.proxy.limit-for-period=1000"
        })
class BackpressureIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InMemoryShadowJobQueue shadowJobQueue;

    @BeforeEach
    void cleanQueue() {
        shadowJobQueue.clear();
    }

    @Test
    void rejectsTrafficWhenShadowQueueBacklogIsTooHigh() {
        LlmProxyRequest queuedRequest = new LlmProxyRequest(
                "Return customer tier",
                Map.of("customerId", "queued"),
                false,
                false,
                0,
                false);
        shadowJobQueue.publish(new ShadowComparisonJob(
                "queued-request",
                queuedRequest,
                "{\"model\":\"primary\",\"output\":{\"tier\":\"gold\"}}",
                Instant.now()));

        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "backpressure"));

        ResponseEntity<String> response = restTemplate.postForEntity("/api/proxy", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("backlog");
    }
}
