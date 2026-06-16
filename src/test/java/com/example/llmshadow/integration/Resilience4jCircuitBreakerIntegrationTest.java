package com.example.llmshadow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.llmshadow.persistence.MismatchRepository;
import com.example.llmshadow.queue.InMemoryShadowJobQueue;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
                "shadow.sqlite.path=/tmp/llm-shadow-proxy-circuit-test.sqlite",
                "shadow.queue.backend=memory",
                "shadow.queue.poll-delay-ms=50",
                "shadow.retry.backoff-ms=1000",
                "shadow.retry.max-attempts=5",
                "shadow.circuit-breaker.failure-threshold=2",
                "shadow.circuit-breaker.open-duration-ms=5000"
        })
class Resilience4jCircuitBreakerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private InMemoryShadowJobQueue shadowJobQueue;

    @Autowired
    private MismatchRepository mismatchRepository;

    @BeforeEach
    void resetState() {
        circuitBreakerRegistry.circuitBreaker("candidate").reset();
        shadowJobQueue.clear();
        mismatchRepository.deleteAll();
    }

    @Test
    void repeatedCandidateFailuresOpenResilience4jCircuitBreaker() {
        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "circuit-1"),
                "forceCandidateError", true);

        ResponseEntity<String> first = restTemplate.postForEntity("/api/proxy", request, String.class);
        ResponseEntity<String> second = restTemplate.postForEntity("/api/proxy", request, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("candidate");
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN));
    }
}
