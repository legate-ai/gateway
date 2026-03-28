package io.legate.core.routing;

import java.time.Duration;

/**
 * Resolved provider endpoint with all necessary configuration.
 */
public record ResolvedEndpoint(
    String providerName,
    String modelName,
    String baseUrl,
    ProviderCredentials credentials,
    Duration connectTimeout,
    Duration readTimeout,
    int weight
) {
    /**
     * Creates an endpoint with default timeouts and weight.
     */
    public ResolvedEndpoint(
        String providerName,
        String modelName,
        String baseUrl,
        ProviderCredentials credentials
    ) {
        this(
            providerName,
            modelName,
            baseUrl,
            credentials,
            Duration.ofSeconds(10),
            Duration.ofSeconds(60),
            100
        );
    }

    /**
     * Returns a unique key for this endpoint (provider + model).
     */
    public String getKey() {
        return providerName + ":" + modelName;
    }
}
