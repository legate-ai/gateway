package io.legate.core.config.guard;

/**
 * Identifiers for the built-in request guard implementations shipped with Legate.
 *
 * <p>{@link RequestGuardConfig#type()} accepts either one of these values (case-insensitive,
 * kebab-case) or the fully-qualified class name of a custom {@code RequestGuard}
 * implementation on the classpath.</p>
 *
 * <p>Default execution order (overridable via {@link RequestGuardConfig#order()}):</p>
 * <pre>
 *   SYSTEM_PROMPT_INJECTOR (50) → PII_DETECTOR (100) → KEYWORD_BLOCKER (200) → MAX_TOKENS (300)
 * </pre>
 */
public enum BuiltInRequestGuardType {

    /**
     * Prepends or appends a system message to every request before forwarding.
     * Config key: {@code system-prompt-injector}.
     * Required config: {@link RequestGuardConfig#systemPrompt()}.
     * Default order: 50.
     */
    PII_DETECTOR,

    /**
     * Detects (and optionally redacts) personally-identifiable information in prompts.
     * Config key: {@code pii-detector}.
     * Required config: {@link RequestGuardConfig#pii()}.
     * Default order: 100.
     */
    KEYWORD_BLOCKER,

    /**
     * Blocks requests whose messages contain any of the configured keywords.
     * Config key: {@code keyword-blocker}.
     * Required config: {@link RequestGuardConfig#keywords()}.
     * Default order: 200.
     */
    MAX_TOKENS,

    /**
     * Rejects requests whose estimated input token count exceeds a threshold.
     * Config key: {@code max-tokens}.
     * Required config: {@link RequestGuardConfig#maxInputTokens()}.
     * Default order: 300.
     */
    SYSTEM_PROMPT_INJECTOR;

    /** Returns the kebab-case config key used in YAML for this guard type. */
    public String configKey() {
        return name().toLowerCase().replace('_', '-');
    }

    /**
     * Returns the default execution order for this guard type.
     *
     * @return order value; lower runs first
     */
    public int defaultOrder() {
        return switch (this) {
            case SYSTEM_PROMPT_INJECTOR -> 50;
            case PII_DETECTOR          -> 100;
            case KEYWORD_BLOCKER       -> 200;
            case MAX_TOKENS            -> 300;
        };
    }

    /**
     * Parses a config type string to a {@code BuiltInRequestGuardType}, or returns
     * {@code null} if it is a custom (fully-qualified class name) guard type.
     *
     * @param type the type string from configuration (e.g., {@code "pii-detector"})
     * @return matching built-in type, or {@code null} for custom types
     */
    public static BuiltInRequestGuardType fromConfigKey(String type) {
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
