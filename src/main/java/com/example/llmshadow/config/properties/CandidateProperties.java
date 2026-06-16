package com.example.llmshadow.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "candidate")
public record CandidateProperties(
        @DefaultValue("")
        String url) {
}
