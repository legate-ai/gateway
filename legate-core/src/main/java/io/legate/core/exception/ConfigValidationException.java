package io.legate.core.exception;

import java.util.List;

/**
 * Thrown when configuration validation fails.
 */
public class ConfigValidationException extends LegateException {
    private final List<String> validationErrors;

    public ConfigValidationException(List<String> validationErrors) {
        super(String.format("Configuration validation failed with %d error(s): %s",
                        validationErrors.size(), String.join("; ", validationErrors)),
                "CONFIG_VALIDATION_FAILED");
        this.validationErrors = List.copyOf(validationErrors);
    }

    public ConfigValidationException(String singleError) {
        this(List.of(singleError));
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }
}
