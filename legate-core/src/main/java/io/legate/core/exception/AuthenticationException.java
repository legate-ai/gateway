package io.legate.core.exception;

/**
 * Thrown when virtual key authentication fails.
 */
public class AuthenticationException extends LegateException {
    public AuthenticationException(String message) {
        super(message, "AUTHENTICATION_FAILED");
    }

    public static AuthenticationException missingKey() {
        return new AuthenticationException("Missing or invalid Authorization header");
    }

    public static AuthenticationException invalidKey() {
        return new AuthenticationException("Invalid virtual key");
    }

    public static AuthenticationException revokedKey() {
        return new AuthenticationException("Virtual key has been revoked");
    }
}
