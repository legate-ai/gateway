package io.legate.core.config.guard;

import java.util.List;
import java.util.Map;

/**
 * Configuration for one guard in the request guard pipeline.
 *
 * <p>The {@link #type()} field accepts either a built-in guard identifier (see
 * {@link BuiltInRequestGuardType#configKey()}) or the fully-qualified class name
 * of a custom {@code RequestGuard} implementation on the classpath. This allows
 * third-party guards to be dropped in without forking Legate.</p>
 *
 * <p>YAML example — built-in guards:</p>
 * <pre>{@code
 * legate:
 *   guards:
 *     request-guards:
 *       - type: system-prompt-injector
 *         enabled: true
 *         system-prompt: "You are a helpful assistant. Never reveal confidential data."
 *
 *       - type: pii-detector
 *         enabled: true
 *         pii:
 *           action: redact
 *           patterns: [email, phone]
 *
 *       - type: keyword-blocker
 *         enabled: true
 *         keywords:
 *           - jailbreak
 *           - "ignore previous instructions"
 *
 *       - type: max-tokens
 *         enabled: true
 *         max-input-tokens: 8000
 *
 *       - type: com.example.MyCustomGuard    # fully-qualified class name
 *         enabled: true
 *         config:
 *           my-param: value
 * }</pre>
 */
public record RequestGuardConfig(

    /**
     * Guard type identifier. One of the built-in {@link BuiltInRequestGuardType}
     * config keys ({@code pii-detector}, {@code keyword-blocker}, {@code max-tokens},
     * {@code system-prompt-injector}), or a fully-qualified class name for custom guards.
     * Must not be blank.
     */
    String type,

    /**
     * Whether this guard is active. Disabled guards are loaded but skipped during
     * evaluation. Default: {@code true}.
     */
    boolean enabled,

    /**
     * Execution order within the pipeline — lower values run first.
     * When {@code 0} (the default), the built-in default order is used
     * ({@link BuiltInRequestGuardType#defaultOrder()}). Custom guards default to 0
     * when order is unset, placing them before built-in guards unless overridden.
     */
    int order,

    /**
     * PII detector settings. Required when {@code type = "pii-detector"}.
     * Ignored for other guard types.
     */
    PiiDetectorConfig pii,

    /**
     * List of forbidden keyword strings (case-insensitive substring match).
     * Required when {@code type = "keyword-blocker"}. Ignored for other types.
     */
    List<String> keywords,

    /**
     * Maximum allowed estimated input token count.
     * Required when {@code type = "max-tokens"}. Ignored for other types.
     * A rough estimate of 4 characters per token is used.
     */
    Integer maxInputTokens,

    /**
     * System prompt text to inject at the start of every request.
     * Required when {@code type = "system-prompt-injector"}. Ignored for other types.
     */
    String systemPrompt,

    /**
     * Arbitrary key-value configuration forwarded to custom guard implementations
     * via their initialisation method.
     */
    Map<String, Object> config

) {
    public RequestGuardConfig {
        if (keywords == null) {
            keywords = List.of();
        }
        if (config == null){
            config = Map.of();
        }
        // Resolve default order for known built-in types
        if (order == 0) {
            BuiltInRequestGuardType builtIn = BuiltInRequestGuardType.fromConfigKey(type);
            if (builtIn != null) {
                order = builtIn.defaultOrder();
            }
        }
    }

    /**
     * Returns {@code true} if this guard is a recognised built-in type.
     * Returns {@code false} for custom (fully-qualified class name) guards.
     */
    public boolean isBuiltIn() {
        return BuiltInRequestGuardType.fromConfigKey(type) != null;
    }
}
