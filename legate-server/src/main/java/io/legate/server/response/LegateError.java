package io.legate.server.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Structured error payload included in all Legate-originated error responses.
 *
 * <p>Every gateway-originated error follows this envelope:</p>
 * <pre>{@code
 * {
 *   "error": {
 *     "type": "legate_error",
 *     "code": "RATE_LIMIT_EXCEEDED",
 *     "message": "Request rate limit reached for key wdn_live_xxx",
 *     "legate_request_id": "req_abc123",
 *     "details": { "retry_after": "2026-03-06T12:00:00Z" }
 *   }
 * }
 * }</pre>
 *
 * <p>Upstream provider errors are passed through unchanged, with only the
 * {@code X-Legate-Request-Id} header added.</p>
 *
 * @param type            always {@code "legate_error"} — identifies Legate as the error source
 * @param code            machine-readable error code (SCREAMING_SNAKE_CASE)
 * @param message         human-readable explanation suitable for display
 * @param legateRequestId the {@code req_xxx} identifier for correlation with logs
 * @param details         optional structured context specific to the error type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LegateError(
    @JsonProperty("type")               String type,
    @JsonProperty("code")               String code,
    @JsonProperty("message")            String message,
    @JsonProperty("legate_request_id")  String legateRequestId,
    @JsonProperty("details")            Map<String, Object> details
) {
    /** Canonical value for the {@link #type} field in all Legate errors. */
    public static final String LEGATE_ERROR_TYPE = "legate_error";

    /**
     * Creates a {@link LegateError} with a request ID and no additional details.
     *
     * @param code      machine-readable error code
     * @param message   human-readable error message
     * @param requestId the Legate request ID for correlation
     */
    public static LegateError of(String code, String message, String requestId) {
        return new LegateError(LEGATE_ERROR_TYPE, code, message, requestId, null);
    }

    /**
     * Creates a {@link LegateError} with no request ID (for auth-level errors
     * where the request ID may not yet be available).
     *
     * @param code    machine-readable error code
     * @param message human-readable error message
     */
    public static LegateError of(String code, String message) {
        return new LegateError(LEGATE_ERROR_TYPE, code, message, null, null);
    }

    /**
     * Creates a {@link LegateError} with a request ID and structured details.
     *
     * @param code      machine-readable error code
     * @param message   human-readable error message
     * @param requestId the Legate request ID for correlation
     * @param details   additional structured context (e.g., retry-after, limit info)
     */
    public static LegateError of(String code, String message, String requestId, Map<String, Object> details) {
        return new LegateError(LEGATE_ERROR_TYPE, code, message, requestId, details);
    }
}
