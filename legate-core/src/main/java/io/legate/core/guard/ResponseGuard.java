package io.legate.core.guard;

/**
 * SPI for response guards that evaluate (and optionally modify) responses after the
 * upstream provider call completes.
 *
 * <p>Implementations are registered in {@code legate.guards.response-guards} YAML.</p>
 *
 * <p>Thread-safety: guards must be stateless or thread-safe.</p>
 */
public interface ResponseGuard {

    /**
     * Returns the unique name of this guard.
     */
    String getName();

    /**
     * Returns the execution order. Lower values run first.
     */
    int getOrder();

    /**
     * Evaluates the guard against the response context.
     *
     * @param context the response context
     * @return the decision — never {@code null}
     */
    ResponseGuardDecision inspect(ResponseGuardContext context);
}
