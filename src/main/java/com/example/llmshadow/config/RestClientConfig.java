package com.example.llmshadow.config;

import com.example.llmshadow.config.properties.AppProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer timeoutCustomizer(AppProperties appProperties) {
        return restClientBuilder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(appProperties.httpClient().connectTimeoutMs());
            requestFactory.setReadTimeout(appProperties.httpClient().readTimeoutMs());
            restClientBuilder.requestFactory(requestFactory);

            if (StringUtils.hasText(appProperties.auth().apiKey())) {
                restClientBuilder.defaultHeader("X-API-Key", appProperties.auth().apiKey());
            }
        };
    }
}
