package com.example.llmshadow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JWT access token returned by /auth/token.")
public record TokenResponse(
        @Schema(description = "Bearer JWT access token")
        String accessToken,

        @Schema(description = "Token type", example = "Bearer")
        String tokenType,

        @Schema(description = "Seconds until the token expires", example = "3600")
        long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }
}
