package com.example.llmshadow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.llmshadow.persistence.MismatchRepository;
import com.example.llmshadow.queue.InMemoryShadowJobQueue;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shadow.sqlite.path=/tmp/llm-shadow-proxy-main-test.sqlite",
                "shadow.retry.backoff-ms=100",
                "shadow.retry.max-attempts=1",
                "shadow.queue.backend=memory",
                "shadow.queue.poll-delay-ms=50",
                "shadow.circuit-breaker.enabled=false"
        })
class ProxyControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MismatchRepository mismatchRepository;

    @Autowired
    private InMemoryShadowJobQueue shadowJobQueue;

    @BeforeEach
    void cleanDatabase() {
        mismatchRepository.deleteAll();
        shadowJobQueue.clear();
    }

    @Test
    void proxyReturnsPrimaryResponseWithoutWaitingForSlowCandidate() {
        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "123"),
                "candidateDelayMs", 3_000);

        Instant start = Instant.now();
        ResponseEntity<String> response = restTemplate.postForEntity("/api/proxy", request, String.class);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(response.getBody()).contains("\"model\":\"primary\"");
        assertThat(response.getBody()).contains("\"tier\":\"gold\"");
        assertThat(elapsed).isLessThan(Duration.ofMillis(750));
    }

    @Test
    void proxyReturnsPrimaryResponseWhenCandidateFails() {
        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "456"),
                "forceCandidateError", true);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/proxy", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"model\":\"primary\"");
        assertThat(response.getBody()).contains("\"customerId\":\"456\"");
        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(shadowJobQueue.deadLetterCount()).isEqualTo(1));
    }

    @Test
    void proxyReturnsPrimaryResponseWhenCandidateMismatches() {
        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "789"),
                "forceMismatch", true);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/proxy", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"model\":\"primary\"");
        assertThat(response.getBody()).contains("\"tier\":\"gold\"");
        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(mismatchRepository.count()).isEqualTo(1));
    }

    @Test
    void shadowJobPersistsAndRunsEvenWhenClientDisconnectsBeforeReadingResponse() throws Exception {
        String body = """
                {
                  "prompt": "Return customer tier",
                  "input": {
                    "customerId": "disconnect-1"
                  },
                  "forceMismatch": true
                }
                """;

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(("POST /api/proxy HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(bodyBytes);
            outputStream.flush();
        }

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(mismatchRepository.count()).isEqualTo(1));
    }

    @Test
    void openApiDocsAndSwaggerUiAreAvailable() {
        ResponseEntity<String> docs = restTemplate.getForEntity("/v3/api-docs", String.class);
        ResponseEntity<String> swaggerUi = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docs.getBody()).contains("GPT LLM Shadow Proxy API");
        assertThat(docs.getBody()).contains("/api/proxy");
        assertThat(swaggerUi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(swaggerUi.getBody()).contains("Swagger UI");
    }
}
