package io.legate.core.guard;

import io.legate.core.model.ChatCompletionResponse;

import java.time.Instant;

/**
 * Sealed interface representing a guard's decision about a response.
 *
 * <p>Produced by {@link ResponseGuard#inspect(ResponseGuardContext)} after an upstream call.</p>
 */
public sealed interface ResponseGuardDecision
        permits ResponseGuardDecision.Allow, ResponseGuardDecision.Block,
        ResponseGuardDecision.Modify, ResponseGuardDecision.Warn {

    String guardName();

    Instant evaluatedAt();

    record Allow(String guardName, Instant evaluatedAt) implements ResponseGuardDecision {
        public Allow(String guardName) {
            this(guardName, Instant.now());
        }
    }

    record Block(String guardName, Instant evaluatedAt, String reason)
            implements ResponseGuardDecision {
        public Block(String guardName, String reason) {
            this(guardName, Instant.now(), reason);
        }
    }

    record Modify(
            String guardName,
            Instant evaluatedAt,
            ChatCompletionResponse modifiedResponse,
            String reason
    ) implements ResponseGuardDecision {
        public Modify(String guardName, ChatCompletionResponse modifiedResponse, String reason) {
            this(guardName, Instant.now(), modifiedResponse, reason);
        }
    }

    record Warn(String guardName, Instant evaluatedAt, String reason)
            implements ResponseGuardDecision {
        public Warn(String guardName, String reason) {
            this(guardName, Instant.now(), reason);
        }
    }
}
