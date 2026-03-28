package io.legate.core.config.logging;

/**
 * Output destination type for a Legate log destination.
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   logging:
 *     destinations:
 *       - type: console
 *       - type: file
 *         path: /var/log/legate/requests.log
 *       - type: webhook
 *         url: https://example.com/events
 * }</pre>
 */
public enum DestinationType {

    /**
     * Write log entries to standard output via SLF4J.
     * Suitable for containerised environments where logs are collected by the runtime.
     */
    CONSOLE,

    /**
     * Write log entries to a rolling file on disk.
     * Requires {@link LoggingConfig.DestinationConfig#path()} to be set.
     */
    FILE,

    /**
     * POST batches of log entries to an HTTP webhook endpoint.
     * Requires {@link LoggingConfig.DestinationConfig#url()} to be set.
     */
    WEBHOOK
}
