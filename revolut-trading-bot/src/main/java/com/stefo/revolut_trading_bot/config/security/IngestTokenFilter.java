package com.stefo.revolut_trading_bot.config.security;

import com.stefo.revolut_trading_bot.config.SentimentConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Scoped to {@code /api/sentiment/ingest/**} — rejects requests missing or
 * mismatching the configured Bearer token.
 *
 * Returns:
 *   - 503 when the sentiment pipeline is disabled ({@code sentiment.enabled: false})
 *   - 401 when the configured token is empty (endpoint disabled by config) or
 *     the Bearer header is missing / mismatched
 *   - otherwise passes the request to the controller unchanged
 *
 * Uses {@link String#equals(Object)} for token comparison — timing attacks on
 * shared tokens are low-impact for this use case and a constant-time compare
 * is complexity we don't need. Revisit if the threat model changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestTokenFilter extends OncePerRequestFilter {

    private static final String INGEST_PATH_PREFIX = "/api/sentiment/ingest/";
    private static final String BEARER_PREFIX       = "Bearer ";

    private final SentimentConfig config;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INGEST_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (!config.isEnabled()) {
            log.warn("[ingest] rejecting {} — sentiment pipeline disabled",
                    request.getRequestURI());
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "sentiment pipeline disabled");
            return;
        }

        String configuredToken = config.getIngest().getAuthToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            log.warn("[ingest] rejecting {} — sentiment.ingest.auth-token not configured",
                    request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "ingest endpoint not configured");
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing bearer token");
            return;
        }

        String presented = header.substring(BEARER_PREFIX.length());
        if (!configuredToken.equals(presented)) {
            log.warn("[ingest] rejecting {} — bearer token mismatch", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid bearer token");
            return;
        }

        chain.doFilter(request, response);
    }
}
