package com.example.llmshadow.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer timeoutCustomizer(@Value("${app.auth.api-key:}") String apiKey) {
        return restClientBuilder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(1));
            requestFactory.setReadTimeout(Duration.ofSeconds(2));
            restClientBuilder.requestFactory(requestFactory);

            if (StringUtils.hasText(apiKey)) {
                restClientBuilder.defaultHeader("X-API-Key", apiKey);
            }
        };
    }
}
