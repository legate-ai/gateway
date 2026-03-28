package io.legate.core.guard.builtin;

import io.legate.core.guard.GuardContext;
import io.legate.core.guard.GuardDecision;
import io.legate.core.guard.RequestGuard;
import io.legate.core.model.Message;

/**
 * Built-in guard that rejects requests whose estimated input token count exceeds
 * a configured threshold.
 *
 * <p>Estimation uses a rough heuristic of 4 characters per token, which is reasonable
 * for English text and similar languages. For precise token counting, implement a
 * custom {@link RequestGuard} using a provider-specific tokeniser.</p>
 *
 * <p>Default execution order: 300.</p>
 */
public class MaxTokensLimiterGuard implements RequestGuard {

    private static final String GUARD_NAME = "max-tokens";
    private static final int CHARS_PER_TOKEN = 4;

    private final int maxInputTokens;
    private final int order;

    /**
     * @param maxInputTokens maximum allowed estimated input tokens
     * @param order          execution order in the pipeline
     */
    public MaxTokensLimiterGuard(int maxInputTokens, int order) {
        this.maxInputTokens = maxInputTokens;
        this.order = order;
    }

    @Override
    public String getName() { return GUARD_NAME; }

    @Override
    public int getOrder() { return order; }

    @Override
    public GuardDecision inspect(GuardContext context) {
        int estimatedTokens = estimateTokens(context);
        if (estimatedTokens > maxInputTokens) {
            return new GuardDecision.Block(GUARD_NAME,
                "Estimated input tokens (" + estimatedTokens + ") exceeds limit (" + maxInputTokens + ")");
        }
        return new GuardDecision.Allow(GUARD_NAME);
    }

    private int estimateTokens(GuardContext context) {
        if (context.request().messages() == null) {
            return 0;
        }
        int totalChars = context.request().messages().stream()
            .mapToInt(m -> m.content() != null ? m.content().length() : 0)
            .sum();
        return totalChars / CHARS_PER_TOKEN;
    }
}
