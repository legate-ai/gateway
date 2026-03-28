package io.legate.core.exception;

/**
 * Thrown when no provider endpoint is available for a requested model.
 */
public class NoEndpointAvailableException extends LegateException {
    private final String model;

    public NoEndpointAvailableException(String model) {
        super(String.format("No endpoint available for model '%s'", model),
                "NO_ENDPOINT_AVAILABLE");
        this.model = model;
    }

    public NoEndpointAvailableException(String model, String reason) {
        super(String.format("No endpoint available for model '%s': %s", model, reason),
                "NO_ENDPOINT_AVAILABLE");
        this.model = model;
    }

    public String getModel() {
        return model;
    }
}
