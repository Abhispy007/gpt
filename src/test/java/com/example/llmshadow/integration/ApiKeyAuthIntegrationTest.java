package com.example.llmshadow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.api-key=test-key",
                "shadow.sqlite.path=/tmp/llm-shadow-proxy-auth-test.sqlite",
                "shadow.queue.backend=memory"
        })
class ApiKeyAuthIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void proxyRequiresApiKeyWhenConfigured() {
        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "auth-1"));

        ResponseEntity<String> unauthorized = restTemplate.getForEntity("/api/mismatches", String.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "test-key");

        ResponseEntity<String> authorized = restTemplate.postForEntity(
                "/api/proxy",
                new HttpEntity<>(request, headers),
                String.class);

        assertThat(authorized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorized.getBody()).contains("\"model\":\"primary\"");
    }
}
