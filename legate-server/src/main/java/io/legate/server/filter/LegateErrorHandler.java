package io.legate.server.filter;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.legate.core.exception.AuthenticationException;
import io.legate.core.exception.GuardBlockedException;
import io.legate.core.exception.ModelNotAllowedException;
import io.legate.core.exception.NoEndpointAvailableException;
import io.legate.core.exception.RateLimitExceededException;
import io.legate.core.exception.SpendLimitExceededException;
import io.legate.core.exception.UpstreamException;
import io.legate.core.exception.LegateException;
import io.legate.server.constants.LegateHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.core.annotation.Order;
import java.time.Instant;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global error handler for the Legate gateway.
 *
 * <p>Converts exceptions escaping the WebFlux handler pipeline into structured
 * JSON error responses following the Legate error format:</p>
 * <pre>{@code
 * {
 *   "error": {
 *     "type": "legate_error",
 *     "code": "ERROR_CODE",
 *     "message": "Human-readable message",
 *     "legate_request_id": "req_xxx"
 *   }
 * }
 * }</pre>
 *
 * <p>Upstream provider errors (non-2xx from the LLM provider) are passed through
 * with the {@code X-Legate-Request-Id} header appended. Internal Legate errors use
 * the codes defined in each {@link LegateException} subclass.</p>
 *
 * <p>Ordered at {@code -1} to run before Spring Boot's default
 * {@code DefaultErrorWebExceptionHandler}.</p>
 */
@Component
@Order(-1)
public class LegateErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LegateErrorHandler.class);

    private final ObjectMapper objectMapper;

    public LegateErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String requestId = RequestIdWebFilter.getRequestId(exchange);

        HttpStatus status;
        String errorCode;
        String message;
        String upstreamProvider = null;

        if (ex instanceof AuthenticationException e) {
            status = HttpStatus.UNAUTHORIZED;
            errorCode = e.getErrorCode();
            message = e.getMessage();

        } else if (ex instanceof ModelNotAllowedException e) {
            status = HttpStatus.FORBIDDEN;
            errorCode = e.getErrorCode();
            message = e.getMessage();

        } else if (ex instanceof GuardBlockedException e) {
            status = HttpStatus.FORBIDDEN;
            errorCode = e.getErrorCode();
            message = e.getMessage();

        } else if (ex instanceof RateLimitExceededException e) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            errorCode = e.getErrorCode();
            message = e.getMessage();
            long retryAfterSeconds = e.getRetryAfter() != null
                    ? Math.max(0, e.getRetryAfter().getEpochSecond() - Instant.now().getEpochSecond())
                    : 60L;
            exchange.getResponse().getHeaders().set(LegateHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        } else if (ex instanceof SpendLimitExceededException e) {
            status = HttpStatus.FORBIDDEN;
            errorCode = e.getErrorCode();
            message = e.getMessage();

        } else if (ex instanceof NoEndpointAvailableException e) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            errorCode = e.getErrorCode();
            message = e.getMessage();

        } else if (ex instanceof UpstreamException e) {
            status = HttpStatus.BAD_GATEWAY;
            errorCode = e.getErrorCode();
            message = e.getMessage();
            upstreamProvider = e.getProvider();

        } else if (ex instanceof LegateException e) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = e.getErrorCode();
            message = e.getMessage();

        } else if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            errorCode = "HTTP_" + status.value();
            message = rse.getReason() != null ? rse.getReason() : status.getReasonPhrase();

        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "INTERNAL_SERVER_ERROR";
            message = "An unexpected error occurred";
            log.error("Unhandled exception for request {}", requestId, ex);
        }

        // Add request ID to response headers
        exchange.getResponse().getHeaders().set("X-Legate-Request-Id", requestId);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body = buildErrorBody(errorCode, message, requestId, upstreamProvider);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private byte[] buildErrorBody(String code, String message, String requestId, String upstreamProvider) {
        try {
            ObjectNode root  = objectMapper.createObjectNode();
            ObjectNode error = objectMapper.createObjectNode();
            error.put("type",               "legate_error");
            error.put("code",               code);
            error.put("message",            message);
            error.put("legate_request_id",  requestId);
            if (upstreamProvider != null) {
                error.put("upstream_provider", upstreamProvider);
            } else {
                error.putNull("upstream_provider");
            }
            root.set("error", error);
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            // Fallback — should never happen
            String fallback = "{\"error\":{\"type\":\"legate_error\",\"code\":\"" + code +
                "\",\"message\":\"" + message.replace("\"", "\\\"") +
                "\",\"legate_request_id\":\"" + requestId + "\",\"upstream_provider\":null}}";
            return fallback.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
