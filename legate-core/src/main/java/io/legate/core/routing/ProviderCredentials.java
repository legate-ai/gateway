package io.legate.core.routing;

/**
 * Provider authentication credentials.
 * Sealed interface for type-safe credential variants.
 */
public sealed interface ProviderCredentials {

    /**
     * Bearer token authentication (Authorization: Bearer <token>).
     */
    record BearerToken(String token) implements ProviderCredentials {
    }

    /**
     * API key header authentication (e.g., X-API-Key: <key>).
     */
    record ApiKeyHeader(String headerName, String apiKey) implements ProviderCredentials {
    }

    /**
     * AWS Signature V4 authentication.
     */
    record AwsSigV4(
        String accessKeyId,
        String secretAccessKey,
        String region,
        String service
    ) implements ProviderCredentials {
    }

    /**
     * OAuth2 authentication.
     */
    record OAuth2(String accessToken) implements ProviderCredentials {
    }

    /**
     * No authentication required.
     */
    record None() implements ProviderCredentials {
        public static final None INSTANCE = new None();
    }
}
