package io.legate.core.config.guard;

/**
 * Identifiers for built-in response guard implementations.
 *
 * <p>{@link ResponseGuardConfig#type()} accepts either one of these values or
 * a fully-qualified class name of a custom {@code ResponseGuard} on the classpath.</p>
 */
public enum BuiltInResponseGuardType {

    /**
     * Applies PII detection and optional redaction to the completion text returned
     * by the upstream provider.
     * Config key: {@code pii-detector}.
     * Required config: a {@code pii} section (same structure as the request guard).
     * Default order: 100.
     */
    PII_DETECTOR;

    /** Returns the kebab-case config key used in YAML for this guard type. */
    public String configKey() {
        return name().toLowerCase().replace('_', '-');
    }

    /**
     * Parses a config type string to a {@code BuiltInResponseGuardType}, or returns
     * {@code null} if it represents a custom (fully-qualified class name) guard type.
     *
     * @param type the type string from configuration (e.g., {@code "pii-detector"})
     * @return matching built-in type, or {@code null} for custom types
     */
    public static BuiltInResponseGuardType fromConfigKey(String type) {
        if (type == null) {
            return null;
        }
        String normalised = type.toUpperCase().replace('-', '_');
        try {
            return valueOf(normalised);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
