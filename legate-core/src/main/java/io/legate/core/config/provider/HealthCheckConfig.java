package io.legate.core.config.provider;

import java.time.Duration;

/**
 * Periodic health-check configuration for an upstream provider endpoint.
 *
 * <p>When enabled, Legate probes the provider on a fixed interval. A provider that
 * fails the probe (non-2xx response or timeout) is removed from the routing pool
 * until it recovers. This works in concert with the circuit breaker — health checks
 * detect outages proactively, while circuit breakers react to live request failures.</p>
 *
 * <p>YAML usage (per-provider override):</p>
 * <pre>{@code
 * legate:
 *   providers:
 *     - name: openai-prod
 *       type: openai
 *       health-check:
 *         enabled: true
 *         interval: 30s
 *         timeout: 5s
 *         path: /v1/models
 * }</pre>
 */
public record HealthCheckConfig(

    /** Whether periodic health checks are enabled for this provider. Default: {@code true}. */
    boolean enabled,

    /**
     * How often to probe the provider. Supports any {@link Duration}-parseable string
     * (e.g., {@code "30s"}, {@code "1m"}). Default: 30 seconds.
     */
    Duration interval,

    /**
     * Maximum time to wait for the health-check HTTP response. Default: 5 seconds.
     */
    Duration timeout,

    /**
     * HTTP path appended to the provider's {@code baseUrl} for the probe request.
     * A GET request is sent; any 2xx response marks the provider as healthy.
     * Default: {@code /v1/models}.
     */
    String path

) {
    public HealthCheckConfig {
        if (interval == null) {
            interval = Duration.ofSeconds(30);
        }
        if (timeout == null) {
            timeout  = Duration.ofSeconds(5);
        }
        if (path == null || path.isBlank()) {
            path = "/v1/models";
        }
    }

    /** Default health-check settings — enabled, 30 s interval, 5 s timeout, /v1/models. */
    public static HealthCheckConfig defaults() {
        return new HealthCheckConfig(true, Duration.ofSeconds(30), Duration.ofSeconds(5), "/v1/models");
    }

    /** Disabled health check — provider is always treated as healthy. */
    public static HealthCheckConfig disabled() {
        return new HealthCheckConfig(false, Duration.ofSeconds(30), Duration.ofSeconds(5), "/v1/models");
    }
}
