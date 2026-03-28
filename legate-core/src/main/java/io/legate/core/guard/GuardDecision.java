package io.legate.core.guard;

import io.legate.core.model.ChatCompletionRequest;

import java.time.Instant;

/**
 * Sealed interface representing a guard's decision about a request.
 *
 * <p>Decisions are produced by {@link RequestGuard#inspect(GuardContext)} and
 * consumed by {@link GuardPipeline}. A {@link Block} short-circuits the pipeline.
 * A {@link Modify} cascades the modified request to subsequent guards.</p>
 */
public sealed interface GuardDecision
        permits GuardDecision.Allow, GuardDecision.Block, GuardDecision.Modify, GuardDecision.Warn {

    /**
     * Name of the guard that produced this decision.
     */
    String guardName();

    /**
     * Timestamp when the decision was made.
     */
    Instant evaluatedAt();

    /**
     * Allow the request to proceed unchanged.
     */
    record Allow(String guardName, Instant evaluatedAt) implements GuardDecision {
        public Allow(String guardName) {
            this(guardName, Instant.now());
        }
    }

    /**
     * Block the request entirely. Returns a 403 to the client.
     * The upstream provider never sees the request.
     */
    record Block(String guardName, Instant evaluatedAt, String reason) implements GuardDecision {
        public Block(String guardName, String reason) {
            this(guardName, Instant.now(), reason);
        }
    }

    /**
     * Modify the request before forwarding. The {@code modifiedRequest} replaces the
     * effective request for all subsequent guards and the upstream call.
     */
    record Modify(
            String guardName,
            Instant evaluatedAt,
            ChatCompletionRequest modifiedRequest,
            String reason
    ) implements GuardDecision {
        public Modify(String guardName, ChatCompletionRequest modifiedRequest, String reason) {
            this(guardName, Instant.now(), modifiedRequest, reason);
        }
    }

    /**
     * Allow the request but emit a {@code GuardDecisionEvent} for audit purposes.
     * The request passes through unmodified.
     */
    record Warn(String guardName, Instant evaluatedAt, String reason) implements GuardDecision {
        public Warn(String guardName, String reason) {
            this(guardName, Instant.now(), reason);
        }
    }
}
