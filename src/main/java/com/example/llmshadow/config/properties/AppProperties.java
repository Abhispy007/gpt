package com.example.llmshadow.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
        @Valid @DefaultValue HttpClient httpClient,
        @Valid @DefaultValue RequestLimits requestLimits) {

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

    public record RequestLimits(
            @Min(1)
            @DefaultValue("1048576")
            long maxBodyBytes,

            @Min(1)
            @DefaultValue("8000")
            int maxPromptChars,

            @Min(1)
            @DefaultValue("262144")
            int maxInputJsonBytes,

            @Min(1)
            @DefaultValue("24")
            int maxInputDepth,

            @Min(1)
            @DefaultValue("12000")
            int maxEstimatedTokens) {
    }
}
