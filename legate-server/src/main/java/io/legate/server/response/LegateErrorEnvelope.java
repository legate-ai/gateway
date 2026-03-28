package io.legate.server.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Top-level envelope wrapping a {@link LegateError} in HTTP error responses.
 *
 * <p>All Legate-originated error responses use this structure to remain
 * compatible with OpenAI's own error envelope, making it easy for clients
 * already handling OpenAI errors to handle Legate errors too.</p>
 *
 * @param error the structured error payload
 */
public record LegateErrorEnvelope(@JsonProperty("error") LegateError error) {

    /**
     * Creates an envelope with a request-correlated error.
     *
     * @param code      machine-readable error code
     * @param message   human-readable message
     * @param requestId Legate request ID for log correlation
     */
    public static LegateErrorEnvelope of(String code, String message, String requestId) {
        return new LegateErrorEnvelope(LegateError.of(code, message, requestId));
    }

    /**
     * Creates an envelope for errors that occur before a request ID is available
     * (e.g., authentication failures at the filter layer).
     *
     * @param code    machine-readable error code
     * @param message human-readable message
     */
    public static LegateErrorEnvelope of(String code, String message) {
        return new LegateErrorEnvelope(LegateError.of(code, message));
    }
}
