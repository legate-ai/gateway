package io.legate.core.config.guard;

import java.util.List;

/**
 * Configuration for the built-in PII detector guard.
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   guards:
 *     request-guards:
 *       - type: pii-detector
 *         pii:
 *           action: redact
 *           patterns:
 *             - email
 *             - phone
 *           custom-patterns:
 *             - "\\b[0-9]{16}\\b"   # 16-digit card numbers (custom)
 * }</pre>
 */
public record PiiDetectorConfig(

    /**
     * Action to take when PII is detected.
     * Default: {@link PiiAction#WARN}.
     */
    PiiAction action,

    /**
     * Built-in pattern categories to activate.
     * Defaults to all four built-in categories when left unconfigured.
     */
    List<PiiPattern> patterns,

    /**
     * Additional regex strings compiled as {@link java.util.regex.Pattern} at startup.
     * Each match is treated as PII and subject to the configured {@link #action()}.
     * Matches are replaced with {@code [REDACTED]} during REDACT operations.
     */
    List<String> customPatterns

) {
    public PiiDetectorConfig {
        if (action == null)          {
            action = PiiAction.WARN;
        }
        if (patterns == null) {
            patterns = List.of(PiiPattern.values());
        }
        if (customPatterns == null){
            customPatterns = List.of();
        }
    }

    /**
     * Returns a default PII config: WARN action on all built-in patterns,
     * no custom patterns.
     */
    public static PiiDetectorConfig defaults() {
        return new PiiDetectorConfig(PiiAction.WARN, List.of(PiiPattern.values()), List.of());
    }

    /**
     * Returns a config that blocks requests containing any PII matching all
     * built-in patterns.
     */
    public static PiiDetectorConfig blockAll() {
        return new PiiDetectorConfig(PiiAction.BLOCK, List.of(PiiPattern.values()), List.of());
    }

    /**
     * Returns a config that redacts PII matching all built-in patterns.
     */
    public static PiiDetectorConfig redactAll() {
        return new PiiDetectorConfig(PiiAction.REDACT, List.of(PiiPattern.values()), List.of());
    }
}
