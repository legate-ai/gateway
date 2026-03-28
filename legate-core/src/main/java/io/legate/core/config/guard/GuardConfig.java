package io.legate.core.config.guard;

import java.util.List;

/**
 * Top-level guard pipeline configuration.
 *
 * <p>Guards execute in two distinct phases:</p>
 * <ol>
 *   <li><b>Request guards</b> — run before the upstream call; can block or modify the prompt.</li>
 *   <li><b>Response guards</b> — run after the upstream call; can block or modify the completion.</li>
 * </ol>
 *
 * <p>Within each phase, guards execute in ascending {@code order} value. A {@code Block}
 * decision from any guard short-circuits the remaining guards in that phase.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   guards:
 *     request-guards:
 *       - type: system-prompt-injector
 *         system-prompt: "You are a helpful assistant."
 *       - type: pii-detector
 *         pii:
 *           action: redact
 *     response-guards:
 *       - type: pii-detector
 *         pii:
 *           action: redact
 * }</pre>
 */
public record GuardConfig(

    /**
     * Guards evaluated on every incoming request before it is forwarded upstream.
     * Sorted by {@link RequestGuardConfig#order()} at startup.
     */
    List<RequestGuardConfig> requestGuards,

    /**
     * Guards evaluated on every upstream response before it is returned to the client.
     * Sorted by {@link ResponseGuardConfig#order()} at startup.
     */
    List<ResponseGuardConfig> responseGuards

) {
    public GuardConfig {
        if (requestGuards == null)  {
            requestGuards  = List.of();
        }
        if (responseGuards == null) {
            responseGuards = List.of();
        }
    }

    /** Returns a guard config with no guards active — all traffic passes through. */
    public static GuardConfig empty() {
        return new GuardConfig(List.of(), List.of());
    }

    /** Returns {@code true} when at least one request or response guard is configured. */
    public boolean hasGuards() {
        return !requestGuards.isEmpty() || !responseGuards.isEmpty();
    }
}
