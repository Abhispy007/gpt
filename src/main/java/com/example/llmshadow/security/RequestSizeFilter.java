package com.example.llmshadow.security;

import com.example.llmshadow.config.properties.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestSizeFilter extends OncePerRequestFilter {

    private final AppProperties appProperties;

    public RequestSizeFilter(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/proxy")
                && !request.getRequestURI().startsWith("/mock/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        long contentLength = request.getContentLengthLong();
        long maxBodyBytes = appProperties.requestLimits().maxBodyBytes();
        if (contentLength > maxBodyBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"request body too large\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
