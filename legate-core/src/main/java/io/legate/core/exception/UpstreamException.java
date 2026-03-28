package io.legate.core.exception;

/**
 * Wraps errors from upstream provider API calls.
 */
public class UpstreamException extends LegateException {
    private final int statusCode;
    private final String provider;
    private final String responseBody;

    public UpstreamException(String provider, int statusCode, String responseBody) {
        super(String.format("Upstream provider '%s' returned status %d", provider, statusCode),
                "UPSTREAM_ERROR");
        this.provider = provider;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public UpstreamException(String provider, int statusCode, String responseBody, Throwable cause) {
        super(String.format("Upstream provider '%s' returned status %d", provider, statusCode),
                "UPSTREAM_ERROR", cause);
        this.provider = provider;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getProvider() {
        return provider;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public boolean isRetryable() {
        return statusCode == 429 || statusCode >= 500;
    }
}
