package io.legate.core.exception;

/**
 * Thrown when a virtual key attempts to access a model it doesn't have permission for.
 */
public class ModelNotAllowedException extends LegateException {
    private final String model;
    private final String virtualKeyId;

    public ModelNotAllowedException(String model, String virtualKeyId) {
        super(String.format("Model '%s' is not allowed for virtual key '%s'", model, virtualKeyId),
                "MODEL_NOT_ALLOWED");
        this.model = model;
        this.virtualKeyId = virtualKeyId;
    }

    public String getModel() {
        return model;
    }

    public String getVirtualKeyId() {
        return virtualKeyId;
    }
}
