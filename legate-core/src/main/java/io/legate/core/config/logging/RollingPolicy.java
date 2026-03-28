package io.legate.core.config.logging;

/**
 * File rotation policy for the file-based log destination.
 *
 * <p>Only applies when {@link LoggingConfig.DestinationConfig#type()} is
 * {@link DestinationType#FILE}.</p>
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   logging:
 *     destinations:
 *       - type: file
 *         path: /var/log/legate/requests.log
 *         rolling: daily       # or DAILY, SIZE
 *         retention: 30d
 * }</pre>
 */
public enum RollingPolicy {

    /**
     * Rotate the log file at midnight each day.
     * Archived files are named {@code requests.log.2026-03-03}.
     */
    DAILY,

    /**
     * Rotate the log file when it reaches the configured maximum size.
     * Archived files are named {@code requests.log.1}, {@code requests.log.2}, etc.
     * Maximum size is specified as a string (e.g., {@code "100MB"}) — configurable
     * via the logging destination's {@code max-file-size} property.
     */
    SIZE
}
