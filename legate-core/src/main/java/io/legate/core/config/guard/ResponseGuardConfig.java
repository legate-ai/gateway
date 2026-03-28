package io.legate.core.config.guard;

import java.util.Map;

/**
 * Configuration for one guard in the response guard pipeline.
 *
 * <p>Response guards run after the upstream provider returns a completion,
 * before Legate forwards it to the client. They can inspect, modify, or block
 * the response.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   guards:
 *     response-guards:
 *       - type: pii-detector
 *         enabled: true
 *         order: 100
 *         pii:
 *           action: redact
 * }</pre>
 */
public record ResponseGuardConfig(

    /**
     * Guard type identifier. One of the built-in {@link BuiltInResponseGuardType}
     * config keys ({@code pii-detector}), or a fully-qualified class name for
     * custom {@code ResponseGuard} implementations. Must not be blank.
     */
    String type,

    /**
     * Whether this guard is active. Default: {@code true}.
     */
    boolean enabled,

    /**
     * Execution order — lower values run first. Default: {@code 100}.
     */
    int order,

    /**
     * PII detector settings used when {@code type = "pii-detector"}.
     */
    PiiDetectorConfig pii,

    /**
     * Arbitrary key-value configuration forwarded to custom response guard
     * implementations.
     */
    Map<String, Object> config

) {
    public ResponseGuardConfig {
        if (config == null) {
            config = Map.of();
        }
        if (order == 0){
            order  = 100;
        }
    }

    /**
     * Returns {@code true} if this guard is a recognised built-in type.
     */
    public boolean isBuiltIn() {
        return BuiltInResponseGuardType.fromConfigKey(type) != null;
    }
}
