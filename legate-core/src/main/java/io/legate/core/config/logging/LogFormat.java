package io.legate.core.config.logging;

/**
 * Serialisation format for log entries.
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   logging:
 *     destinations:
 *       - type: console
 *         format: json       # or JSON, TEXT
 * }</pre>
 */
public enum LogFormat {

    /**
     * Single-line JSON object per log entry.
     * Example: {@code {"timestamp":"…","request_id":"…","model":"gpt-4o","latency_ms":450}}
     * This is the default and recommended format for machine-readable log aggregation.
     */
    JSON,

    /**
     * Human-readable text format.
     * Example: {@code 2026-03-03 12:00:00 [req_abc123] gpt-4o 450ms SUCCESS}
     * Useful for development and debugging.
     */
    TEXT
}
