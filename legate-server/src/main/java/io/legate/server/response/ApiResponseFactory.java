package io.legate.server.response;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Factory for building consistent {@link ServerResponse} instances across all handlers.
 *
 * <p>Leverages Spring WebFlux's built-in Jackson codec integration via
 * {@link ServerResponse.BodyBuilder#bodyValue(Object)}, which serialises POJOs
 * through the configured {@code ObjectMapper} automatically — eliminating the
 * need for manual {@code objectMapper.writeValueAsString()} calls scattered
 * across handler methods.</p>
 *
 * <p>All error responses use the {@link LegateErrorEnvelope} structure so clients
 * receive a consistent, machine-readable error format regardless of which handler
 * produced the error.</p>
 */
@Component
public class ApiResponseFactory {

    /**
     * Returns a {@code 200 OK} response with the given body serialised as JSON.
     *
     * @param body the response payload; must be serialisable by Jackson
     */
    public Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    /**
     * Returns a {@code 400 Bad Request} error response.
     *
     * @param code      machine-readable error code
     * @param message   human-readable explanation
     * @param requestId Legate request ID for correlation; may be {@code null}
     */
    public Mono<ServerResponse> badRequest(String code, String message, String requestId) {
        return errorResponse(HttpStatus.BAD_REQUEST, code, message, requestId);
    }

    /**
     * Returns a {@code 401 Unauthorized} error response.
     *
     * @param code    machine-readable error code
     * @param message human-readable explanation
     */
    public Mono<ServerResponse> unauthorized(String code, String message) {
        return errorResponse(HttpStatus.UNAUTHORIZED, code, message, null);
    }

    /**
     * Returns a {@code 403 Forbidden} error response.
     *
     * @param code      machine-readable error code
     * @param message   human-readable explanation
     * @param requestId Legate request ID for correlation; may be {@code null}
     */
    public Mono<ServerResponse> forbidden(String code, String message, String requestId) {
        return errorResponse(HttpStatus.FORBIDDEN, code, message, requestId);
    }

    /**
     * Returns a {@code 404 Not Found} error response.
     *
     * @param code      machine-readable error code
     * @param message   human-readable explanation
     * @param requestId Legate request ID for correlation; may be {@code null}
     */
    public Mono<ServerResponse> notFound(String code, String message, String requestId) {
        return errorResponse(HttpStatus.NOT_FOUND, code, message, requestId);
    }

    /**
     * Returns a {@code 422 Unprocessable Entity} error response, used when
     * validation succeeds at the syntax level but the content is semantically invalid
     * (e.g., config reload fails validation).
     *
     * @param code      machine-readable error code
     * @param message   human-readable explanation
     * @param requestId Legate request ID for correlation; may be {@code null}
     */
    public Mono<ServerResponse> unprocessableEntity(String code, String message, String requestId) {
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, code, message, requestId);
    }

    /**
     * Returns a {@code 500 Internal Server Error} response.
     *
     * @param code      machine-readable error code
     * @param message   human-readable explanation
     * @param requestId Legate request ID for correlation; may be {@code null}
     */
    public Mono<ServerResponse> internalServerError(String code, String message, String requestId) {
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, code, message, requestId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Mono<ServerResponse> errorResponse(
        HttpStatus status,
        String code,
        String message,
        String requestId
    ) {
        LegateErrorEnvelope envelope = requestId != null
            ? LegateErrorEnvelope.of(code, message, requestId)
            : LegateErrorEnvelope.of(code, message);

        return ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(envelope);
    }
}
