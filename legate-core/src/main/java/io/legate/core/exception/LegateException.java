package io.legate.core.exception;

/**
 * Base exception for all Legate-specific errors.
 */
public class LegateException extends RuntimeException {
    private final String errorCode;

    public LegateException(String message) {
        super(message);
        this.errorCode = this.getClass().getSimpleName().toUpperCase();
    }

    public LegateException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = this.getClass().getSimpleName().toUpperCase();
    }

    public LegateException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public LegateException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
