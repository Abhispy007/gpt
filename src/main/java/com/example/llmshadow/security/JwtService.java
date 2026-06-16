package com.example.llmshadow.security;

import com.example.llmshadow.config.properties.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final AppProperties appProperties;

    public JwtService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public boolean isEnabled() {
        return appProperties.auth().jwtEnabled();
    }

    public String createToken(String subject) {
        if (!isEnabled()) {
            throw new IllegalStateException("JWT is not configured");
        }

        AppProperties.Auth auth = appProperties.auth();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(auth.jwtExpirationSeconds());

        return Jwts.builder()
                .issuer(auth.jwtIssuer())
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    public boolean isValid(String token) {
        if (!isEnabled() || token == null || token.isBlank()) {
            return false;
        }

        try {
            parseClaims(token);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public String subject(String token) {
        return parseClaims(token).getSubject();
    }

    public long expirationSeconds() {
        return appProperties.auth().jwtExpirationSeconds();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        byte[] keyBytes = appProperties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
