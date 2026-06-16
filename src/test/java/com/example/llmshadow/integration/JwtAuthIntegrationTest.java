package com.example.llmshadow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.llmshadow.dto.TokenResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.api-key=test-key",
                "app.auth.jwt-secret=test-jwt-secret-at-least-32-characters-long",
                "shadow.sqlite.path=/tmp/llm-shadow-proxy-jwt-test.sqlite",
                "shadow.queue.backend=memory"
        })
class JwtAuthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void bearerTokenAuthorizesProtectedEndpoints() {
        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.set("X-API-Key", "test-key");

        ResponseEntity<TokenResponse> tokenResponse = restTemplate.postForEntity(
                "/auth/token",
                new HttpEntity<>(tokenHeaders),
                TokenResponse.class);

        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResponse.getBody()).isNotNull();
        assertThat(tokenResponse.getBody().accessToken()).isNotBlank();
        assertThat(tokenResponse.getBody().tokenType()).isEqualTo("Bearer");

        HttpHeaders bearerHeaders = new HttpHeaders();
        bearerHeaders.setBearerAuth(tokenResponse.getBody().accessToken());

        ResponseEntity<String> metricsResponse = restTemplate.exchange(
                "/metrics",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders),
                String.class);

        assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tokenEndpointRejectsMissingApiKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/auth/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void bearerTokenWorksOnProxyEndpoint() {
        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.set("X-API-Key", "test-key");
        ResponseEntity<TokenResponse> tokenResponse = restTemplate.postForEntity(
                "/auth/token",
                new HttpEntity<>(tokenHeaders),
                TokenResponse.class);

        HttpHeaders bearerHeaders = new HttpHeaders();
        bearerHeaders.setContentType(MediaType.APPLICATION_JSON);
        bearerHeaders.setBearerAuth(tokenResponse.getBody().accessToken());

        Map<String, Object> request = Map.of(
                "prompt", "Return customer tier",
                "input", Map.of("customerId", "jwt-client"));

        ResponseEntity<String> proxyResponse = restTemplate.postForEntity(
                "/api/proxy",
                new HttpEntity<>(request, bearerHeaders),
                String.class);

        assertThat(proxyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(proxyResponse.getBody()).contains("\"model\":\"primary\"");
    }
}
