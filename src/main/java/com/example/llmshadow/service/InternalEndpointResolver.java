package com.example.llmshadow.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InternalEndpointResolver {

    private final Environment environment;

    public InternalEndpointResolver(Environment environment) {
        this.environment = environment;
    }

    public String baseUrl() {
        String port = environment.getProperty("local.server.port");
        if (!StringUtils.hasText(port)) {
            port = environment.getProperty("server.port", "8080");
        }
        return "http://localhost:" + port;
    }
}
