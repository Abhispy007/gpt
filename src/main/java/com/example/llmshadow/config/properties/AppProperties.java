package com.example.llmshadow.config.properties;

import jakarta.validation.Valid;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @DefaultValue Auth auth,
        @Valid @DefaultValue HttpClient httpClient) {

    public record Auth(
            @DefaultValue("")
            String apiKey,

            @DefaultValue("")
            String jwtSecret,

            @DefaultValue("3600")
            long jwtExpirationSeconds,

            @DefaultValue("llm-shadow-proxy")
            String jwtIssuer) {

        public boolean apiKeyEnabled() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean jwtEnabled() {
            return jwtSecret != null && !jwtSecret.isBlank();
        }

        public boolean authEnabled() {
            return apiKeyEnabled() || jwtEnabled();
        }
    }

    public record HttpClient(
            @DurationUnit(ChronoUnit.MILLIS)
            @DefaultValue("1000")
            Duration connectTimeoutMs,

            @DurationUnit(ChronoUnit.MILLIS)
            @DefaultValue("2000")
            Duration readTimeoutMs) {
    }
}
