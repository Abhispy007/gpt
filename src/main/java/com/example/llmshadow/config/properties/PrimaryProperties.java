package com.example.llmshadow.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "primary")
public record PrimaryProperties(
        @DefaultValue("")
        String url) {
}
