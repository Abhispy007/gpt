package com.example.llmshadow.service;

import com.example.llmshadow.dto.LlmProxyRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class PrimaryLlmClient {

    private final RestClient restClient;
    private final InternalEndpointResolver endpointResolver;
    private final String primaryUrl;

    public PrimaryLlmClient(
            RestClient.Builder restClientBuilder,
            InternalEndpointResolver endpointResolver,
            @Value("${primary.url:}") String primaryUrl) {
        this.restClient = restClientBuilder.build();
        this.endpointResolver = endpointResolver;
        this.primaryUrl = primaryUrl;
    }

    public String complete(LlmProxyRequest request) {
        return restClient
                .post()
                .uri(StringUtils.hasText(primaryUrl) ? primaryUrl : endpointResolver.baseUrl() + "/mock/primary")
                .body(request)
                .retrieve()
                .body(String.class);
    }
}
