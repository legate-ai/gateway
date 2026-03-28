package io.legate.core.config.logging;

import java.util.List;

/**
 * Logging configuration controlling what is captured from each request and
 * where the structured log entries are sent.
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   logging:
 *     content:
 *       log-prompts: false
 *       log-responses: false
 *       redact-patterns:
 *         - "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"
 *     destinations:
 *       - type: console
 *         format: json
 *       - type: file
 *         path: /var/log/legate/requests.log
 *         format: json
 *         rolling: daily
 *         retention: 30d
 *       - type: webhook
 *         url: https://example.com/legate-events
 *         batch-size: 100
 * }</pre>
 */
public record LoggingConfig(

    /** Controls whether and how prompt and completion content is captured. */
    ContentLoggingConfig content,

    /**
     * One or more log output destinations. If empty, a default console destination
     * in JSON format is used.
     */
    List<DestinationConfig> destinations

) {
    public LoggingConfig {
        if (content == null) {
            content = ContentLoggingConfig.defaults();
        }
        if (destinations == null || destinations.isEmpty()) {
            destinations = List.of(DestinationConfig.console());
        }
    }

    /** Default logging — JSON to console, no prompt/response content captured. */
    public static LoggingConfig defaults() {
        return new LoggingConfig(ContentLoggingConfig.defaults(), List.of(DestinationConfig.console()));
    }

    // -------------------------------------------------------------------------

    /**
     * Controls which parts of request/response content are included in log entries.
     *
     * <p><strong>Privacy warning:</strong> enabling {@code logPrompts} or
     * {@code logResponses} may cause personally-identifiable information to be
     * written to persistent storage. Consider combining with {@code redactPatterns}
     * and the PII detector guard.</p>
     *
     * @param logPrompts      when {@code true}, the full message list is logged. Default: {@code false}
     * @param logResponses    when {@code true}, the completion text is logged. Default: {@code false}
     * @param redactPatterns  regex patterns applied to logged content before writing;
     *                        matches are replaced with {@code [REDACTED]}
     */
    public record ContentLoggingConfig(
        boolean logPrompts,
        boolean logResponses,
        List<String> redactPatterns
    ) {
        public ContentLoggingConfig {
            if (redactPatterns == null) redactPatterns = List.of();
        }

        /** Conservative defaults — no content logged. */
        public static ContentLoggingConfig defaults() {
            return new ContentLoggingConfig(false, false, List.of());
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Configuration for one log output destination.
     *
     * @param type      destination type; must not be {@code null}
     * @param format    serialisation format; default {@link LogFormat#JSON}
     * @param path      file path — required when {@code type = FILE}
     * @param rolling   rotation policy — applies when {@code type = FILE}
     * @param retention retention period string (e.g., {@code "30d"}, {@code "7d"});
     *                  applies when {@code type = FILE}
     * @param maxFileSize maximum file size before rotation when {@code rolling = SIZE}
     *                    (e.g., {@code "100MB"}); applies when {@code type = FILE}
     * @param url       webhook URL — required when {@code type = WEBHOOK}
     * @param batchSize maximum events per webhook POST batch; default {@code 100};
     *                  applies when {@code type = WEBHOOK}
     */
    public record DestinationConfig(
        DestinationType type,
        LogFormat format,
        String path,
        RollingPolicy rolling,
        String retention,
        String maxFileSize,
        String url,
        int batchSize
    ) {
        public DestinationConfig {
            if (format == null) {
                format = LogFormat.JSON;
            }
            if (rolling == null){
                rolling = RollingPolicy.DAILY;
            }
            if (batchSize <= 0) {
                batchSize = 100;
            }
        }

        /** Returns a JSON console destination using default settings. */
        public static DestinationConfig console() {
            return new DestinationConfig(
                DestinationType.CONSOLE, LogFormat.JSON,
                null, null, null, null, null, 0
            );
        }

        /** Returns a daily-rolling JSON file destination. */
        public static DestinationConfig file(String path, String retention) {
            return new DestinationConfig(
                DestinationType.FILE, LogFormat.JSON,
                path, RollingPolicy.DAILY, retention, null, null, 0
            );
        }

        /** Returns a webhook destination. */
        public static DestinationConfig webhook(String url, int batchSize) {
            return new DestinationConfig(
                DestinationType.WEBHOOK, LogFormat.JSON,
                null, null, null, null, url, batchSize
            );
        }
    }
}
