package com.example.llmshadow.service;

import com.example.llmshadow.config.properties.PrimaryProperties;
import com.example.llmshadow.dto.LlmProxyRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class PrimaryLlmClient {

    private final RestClient restClient;
    private final InternalEndpointResolver endpointResolver;
    private final PrimaryProperties primaryProperties;

    public PrimaryLlmClient(
            RestClient.Builder restClientBuilder,
            InternalEndpointResolver endpointResolver,
            PrimaryProperties primaryProperties) {
        this.restClient = restClientBuilder.build();
        this.endpointResolver = endpointResolver;
        this.primaryProperties = primaryProperties;
    }

    public String complete(LlmProxyRequest request) {
        return restClient
                .post()
                .uri(StringUtils.hasText(primaryProperties.url())
                        ? primaryProperties.url()
                        : endpointResolver.baseUrl() + "/mock/primary")
                .body(request)
                .retrieve()
                .body(String.class);
    }
}
