package io.legate.core.provider;

import java.util.Map;

/**
 * HTTP response received from a provider.
 */
public record ProviderHttpResponse(
        int statusCode,
        Map<String, String> headers,
        String body
) {
    /**
     * Returns true if the response indicates success (2xx).
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Returns true if the response indicates a client error (4xx).
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * Returns true if the response indicates a server error (5xx).
     */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * Returns true if the error is retryable (429 or 5xx).
     */
    public boolean isRetryable() {
        return statusCode == 429 || isServerError();
    }
}
