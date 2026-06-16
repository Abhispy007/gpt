package com.example.llmshadow.security;

import com.example.llmshadow.config.properties.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AppProperties appProperties;
    private final JwtService jwtService;

    public ApiAuthenticationFilter(AppProperties appProperties, JwtService jwtService) {
        this.appProperties = appProperties;
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !appProperties.auth().authEnabled() || !isProtectedPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (authenticateWithBearer(request) || authenticateWithApiKey(request)) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing or invalid API key or Bearer token\"}");
    }

    private boolean authenticateWithBearer(HttpServletRequest request) {
        if (!jwtService.isEnabled()) {
            return false;
        }

        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!jwtService.isValid(token)) {
            return false;
        }

        authenticate("jwt:" + jwtService.subject(token));
        return true;
    }

    private boolean authenticateWithApiKey(HttpServletRequest request) {
        if (!appProperties.auth().apiKeyEnabled()) {
            return false;
        }

        String providedApiKey = request.getHeader(API_KEY_HEADER);
        if (!appProperties.auth().apiKey().equals(providedApiKey)) {
            return false;
        }

        authenticate("api-key-client");
        return true;
    }

    private boolean isProtectedPath(String path) {
        return path.startsWith("/api/")
                || path.startsWith("/mock/")
                || path.equals("/metrics")
                || path.startsWith("/metrics/");
    }

    private void authenticate(String principal) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                principal,
                List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
    }
}
