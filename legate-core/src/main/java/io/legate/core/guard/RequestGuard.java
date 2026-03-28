package io.legate.core.guard;

/**
 * SPI for request guards that evaluate (and optionally modify) requests before they
 * are forwarded to the upstream provider.
 *
 * <p>Implementations are registered in {@code legate.guards.request-guards} YAML.
 * Built-in implementations live in {@code io.legate.core.guard.builtin}.</p>
 *
 * <p>Thread-safety: guards must be stateless or thread-safe — they are called
 * concurrently from multiple request handlers.</p>
 */
public interface RequestGuard {

    /**
     * Returns the unique name of this guard, used in audit events and logs.
     */
    String getName();

    /**
     * Returns the execution order. Guards with lower values run first.
     * Built-in default orders: system-prompt-injector=50, pii-detector=100,
     * keyword-blocker=200, max-tokens=300.
     */
    int getOrder();

    /**
     * Evaluates the guard against the given context.
     *
     * @param context the request context including the effective request, virtual key,
     *                headers, and request ID
     * @return the decision — never {@code null}
     */
    GuardDecision inspect(GuardContext context);
}
