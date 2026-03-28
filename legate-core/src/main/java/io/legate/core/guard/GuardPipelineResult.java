package io.legate.core.guard;

import java.util.List;

/**
 * Sealed result type from {@link GuardPipeline#execute}.
 *
 * <ul>
 *   <li>{@link Approved} — all guards passed; the effective request in
 *       {@code RequestContext} may have been modified by {@link GuardDecision.Modify} decisions.</li>
 *   <li>{@link Rejected} — one guard returned {@link GuardDecision.Block};
 *       the request must not be forwarded upstream.</li>
 * </ul>
 */
public sealed interface GuardPipelineResult
        permits GuardPipelineResult.Approved, GuardPipelineResult.Rejected {

    /**
     * All decisions recorded during pipeline execution (including Allow and Warn).
     */
    List<GuardDecision> decisions();

    /**
     * All guards passed. The effective request may have been modified.
     */
    record Approved(List<GuardDecision> decisions) implements GuardPipelineResult {
    }

    /**
     * A guard blocked the request.
     *
     * @param decisions all decisions up to and including the blocking decision
     * @param reason    human-readable reason from the blocking guard
     * @param guardName name of the guard that blocked the request
     */
    record Rejected(
            List<GuardDecision> decisions,
            String reason,
            String guardName
    ) implements GuardPipelineResult {
    }
}
