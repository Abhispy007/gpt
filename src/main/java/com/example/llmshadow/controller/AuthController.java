package com.example.llmshadow.controller;

import com.example.llmshadow.config.properties.AppProperties;
import com.example.llmshadow.dto.TokenResponse;
import com.example.llmshadow.security.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Exchange a configured API key for a short-lived JWT Bearer token.")
public class AuthController {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final AppProperties appProperties;
    private final JwtService jwtService;

    public AuthController(AppProperties appProperties, JwtService jwtService) {
        this.appProperties = appProperties;
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    @Operation(
            summary = "Issue a JWT access token",
            description = "Requires a valid X-API-Key when API_KEY is configured. "
                    + "Returns a Bearer token for use on protected endpoints. "
                    + "Existing DigitalOcean deployments can keep using X-API-Key directly.")
    @ApiResponse(responseCode = "200", description = "JWT issued")
    @ApiResponse(responseCode = "401", description = "Missing or invalid API key")
    @ApiResponse(responseCode = "503", description = "JWT is not configured")
    public ResponseEntity<TokenResponse> token(HttpServletRequest request) {
        if (!jwtService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "JWT is not configured");
        }

        if (!appProperties.auth().apiKeyEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Token exchange requires API_KEY to be configured");
        }

        String providedApiKey = request.getHeader(API_KEY_HEADER);
        if (!StringUtils.hasText(providedApiKey) || !appProperties.auth().apiKey().equals(providedApiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing or invalid API key");
        }

        String accessToken = jwtService.createToken("api-client");
        return ResponseEntity.ok(TokenResponse.bearer(accessToken, jwtService.expirationSeconds()));
    }
}
