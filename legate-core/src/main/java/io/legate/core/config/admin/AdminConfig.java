package io.legate.core.config.admin;

/**
 * Configuration for the Legate admin API.
 *
 * <p>The admin API exposes management endpoints for virtual key CRUD,
 * configuration reload, metrics, audit queries, and statistics. It should
 * be protected by an admin token and — in production — served on a separate
 * non-public port.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   admin:
 *     require-auth: true
 *     token-env-var: LEGATE_ADMIN_TOKEN    # value is read from env at runtime
 *     port: 8081                           # separate from main 8080
 * }</pre>
 *
 * <p><strong>Security note:</strong> the admin token value is <em>never</em> stored
 * in the config file. It is read from the environment variable named by
 * {@link #tokenEnvVar()} at startup.</p>
 */
public record AdminConfig(

    /**
     * Whether the admin API requires an {@code Authorization: Bearer <token>} header.
     * Set to {@code false} only in local development environments.
     * Default: {@code true}.
     */
    boolean requireAuth,

    /**
     * Name of the environment variable that holds the admin bearer token.
     * The token is resolved at runtime via {@link #resolveToken()}.
     * Default: {@code LEGATE_ADMIN_TOKEN}.
     */
    String tokenEnvVar,

    /**
     * Optional port on which the admin API is served.
     * When {@code null}, the admin API is co-hosted on the main server port
     * (not recommended for production).
     * Setting this to a separate port (e.g., {@code 8081}) allows network-level
     * isolation of admin endpoints.
     */
    Integer port

) {
    public AdminConfig {
        if (tokenEnvVar == null || tokenEnvVar.isBlank()) {
            tokenEnvVar = "LEGATE_ADMIN_TOKEN";
        }
    }

    /** Default admin config — auth required, reads from {@code LEGATE_ADMIN_TOKEN}, same port. */
    public static AdminConfig defaults() {
        return new AdminConfig(true, "LEGATE_ADMIN_TOKEN", null);
    }

    /**
     * Resolves the admin token value from the environment.
     *
     * @return the token string, or {@code null} if the environment variable is not set
     */
    public String resolveToken() {
        return System.getenv(tokenEnvVar);
    }

    /**
     * Returns {@code true} when the admin API listens on a port separate from
     * the main server port.
     */
    public boolean hasDedicatedPort() {
        return port != null;
    }
}
