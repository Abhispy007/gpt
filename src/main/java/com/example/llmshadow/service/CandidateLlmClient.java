package com.example.llmshadow.service;

import com.example.llmshadow.dto.LlmProxyRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class CandidateLlmClient {

    private final RestClient restClient;
    private final InternalEndpointResolver endpointResolver;
    private final String candidateUrl;

    public CandidateLlmClient(
            RestClient.Builder restClientBuilder,
            InternalEndpointResolver endpointResolver,
            @Value("${candidate.url:}") String candidateUrl) {
        this.restClient = restClientBuilder.build();
        this.endpointResolver = endpointResolver;
        this.candidateUrl = candidateUrl;
    }

    public String complete(LlmProxyRequest request) {
        return restClient
                .post()
                .uri(StringUtils.hasText(candidateUrl) ? candidateUrl : endpointResolver.baseUrl() + "/mock/candidate")
                .body(request)
                .retrieve()
                .body(String.class);
    }
}
