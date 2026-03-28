package io.legate.server.filter;

import io.legate.core.config.LegateConfig;
import io.legate.server.response.LegateErrorEnvelope;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * WebFilter that protects {@code /admin/**} endpoints with a bearer token.
 *
 * <p>The expected token is read from the environment variable named by
 * {@link io.legate.core.config.admin.AdminConfig#tokenEnvVar()}
 * (default: {@code LEGATE_ADMIN_TOKEN}).</p>
 *
 * <p>Authentication is skipped when {@code legate.admin.require-auth = false}
 * or when the {@code LEGATE_ADMIN_TOKEN} environment variable is not set
 * (development mode).</p>
 *
 * <p>Token comparison uses {@link MessageDigest#isEqual(byte[], byte[])} for
 * constant-time equality, preventing timing side-channel attacks.</p>
 */
@Component
@Order(-2)
public class AdminAuthWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthWebFilter.class);

    private static final String ADMIN_PATH_PREFIX  = "/admin";
    private static final String BEARER_PREFIX      = "Bearer ";
    private static final String ERROR_CODE         = "AUTHENTICATION_FAILED";
    private static final String ERROR_MESSAGE      = "Invalid or missing admin token";

    private final LegateConfig legateConfig;
    private final ObjectMapper objectMapper;

    public AdminAuthWebFilter(LegateConfig legateConfig, ObjectMapper objectMapper) {
        this.legateConfig = legateConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(ADMIN_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        boolean requireAuth = legateConfig.admin() != null && legateConfig.admin().requireAuth();
        if (!requireAuth) {
            return chain.filter(exchange);
        }

        String expectedToken = legateConfig.admin().resolveToken();
        if (StringUtils.isBlank(expectedToken)) {
            log.debug("Admin auth skipped: LEGATE_ADMIN_TOKEN not configured.");
            return chain.filter(exchange);
        }

        if (isValidToken(exchange, expectedToken)) {
            return chain.filter(exchange);
        }

        return rejectUnauthorized(exchange);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Validates the bearer token using constant-time comparison to prevent
     * timing side-channel attacks.
     *
     * @param exchange       the server web exchange
     * @param expectedToken  the configured admin token
     * @return {@code true} if the provided token matches
     */
    private boolean isValidToken(ServerWebExchange exchange, String expectedToken) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String providedToken = authHeader.substring(BEARER_PREFIX.length()).strip();
        byte[] expected      = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] provided      = providedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private Mono<Void> rejectUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body = serializeErrorBody();
        return response.writeWith(
            Mono.just(response.bufferFactory().wrap(body)));
    }

    private byte[] serializeErrorBody() {
        try {
            LegateErrorEnvelope envelope = LegateErrorEnvelope.of(ERROR_CODE, ERROR_MESSAGE);
            return objectMapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            // Fallback — should never happen with a correctly configured ObjectMapper
            log.error("Failed to serialize admin auth error response", e);
            return ("{\"error\":{\"type\":\"legate_error\",\"code\":\"%s\",\"message\":\"%s\"}}"
                .formatted(ERROR_CODE, ERROR_MESSAGE))
                .getBytes(StandardCharsets.UTF_8);
        }
    }
}
