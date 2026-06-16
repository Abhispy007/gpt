package com.example.llmshadow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shadow.sqlite.path=/tmp/llm-shadow-proxy-rate-limit-test.sqlite",
                "shadow.queue.backend=memory",
                "resilience4j.ratelimiter.instances.proxy.limit-for-period=1",
                "resilience4j.ratelimiter.instances.proxy.limit-refresh-period=1m",
                "resilience4j.ratelimiter.instances.proxy.timeout-duration=0"
        })
class RateLimitIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void rejectsTrafficAboveRateLimit() {
        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "rate-limit"));

        ResponseEntity<String> first = restTemplate.postForEntity("/api/proxy", request, String.class);
        ResponseEntity<String> second = restTemplate.postForEntity("/api/proxy", request, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
