package io.legate.core.provider;

import java.util.Map;

/**
 * HTTP request to be sent to a provider.
 */
public record ProviderHttpRequest(
        String method,
        String url,
        Map<String, String> headers,
        String body
) {
    /**
     * Creates a POST request.
     */
    public static ProviderHttpRequest post(String url, Map<String, String> headers, String body) {
        return new ProviderHttpRequest("POST", url, headers, body);
    }

    /**
     * Creates a GET request.
     */
    public static ProviderHttpRequest get(String url, Map<String, String> headers) {
        return new ProviderHttpRequest("GET", url, headers, null);
    }
}
