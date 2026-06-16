package com.example.llmshadow.service;

import com.example.llmshadow.config.properties.CandidateProperties;
import com.example.llmshadow.dto.LlmProxyRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class CandidateLlmClient {

    private final RestClient restClient;
    private final InternalEndpointResolver endpointResolver;
    private final CandidateProperties candidateProperties;

    public CandidateLlmClient(
            RestClient.Builder restClientBuilder,
            InternalEndpointResolver endpointResolver,
            CandidateProperties candidateProperties) {
        this.restClient = restClientBuilder.build();
        this.endpointResolver = endpointResolver;
        this.candidateProperties = candidateProperties;
    }

    @CircuitBreaker(name = "candidate")
    public String complete(LlmProxyRequest request) {
        return restClient
                .post()
                .uri(StringUtils.hasText(candidateProperties.url())
                        ? candidateProperties.url()
                        : endpointResolver.baseUrl() + "/mock/candidate")
                .body(request)
                .retrieve()
                .body(String.class);
    }
}
