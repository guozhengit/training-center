package com.guoyongzheng.training.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Lightweight API key authentication filter. When {@code training.api-key} is configured
 * (non-blank), every request to /api/** must carry a matching X-API-Key header.
 * Health and Swagger endpoints are always excluded. Leave the property blank to disable
 * authentication (suitable for local development).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-API-Key";
    private static final Set<String> OPEN_PATHS = Set.of(
            "/api/health",
            "/api/docs",
            "/api/swagger-ui.html",
            "/api/swagger-ui"
    );

    private final String configuredKey;

    public ApiKeyFilter(@Value("${training.api-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (configuredKey.isBlank()) {
            return true; // auth disabled
        }
        String path = request.getRequestURI();
        // Allow health check and Swagger without auth
        for (String open : OPEN_PATHS) {
            if (path.startsWith(open)) {
                return true;
            }
        }
        // Only protect /api/** paths; static resources (SPA) pass through
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER_NAME);
        if (configuredKey.equals(provided)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid X-API-Key header\"}");
        }
    }
}
