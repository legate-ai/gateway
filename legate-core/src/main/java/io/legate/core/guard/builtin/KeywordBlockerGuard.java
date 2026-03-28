package io.legate.core.guard.builtin;

import io.legate.core.guard.GuardContext;
import io.legate.core.guard.GuardDecision;
import io.legate.core.guard.RequestGuard;
import io.legate.core.model.Message;

import java.util.List;
import java.util.Locale;

/**
 * Built-in guard that blocks (or warns on) requests containing forbidden keywords.
 *
 * <p>Matching is case-insensitive substring search — the keyword just has to appear
 * anywhere in the concatenated message content.</p>
 *
 * <p>Default execution order: 200.</p>
 */
public class KeywordBlockerGuard implements RequestGuard {

    private static final String GUARD_NAME = "keyword-blocker";

    private final List<String> keywords;   // lower-cased for fast comparison
    private final boolean block;           // true = BLOCK, false = WARN
    private final int order;

    /**
     * @param keywords case-insensitive keywords to detect
     * @param block    {@code true} to block; {@code false} to warn
     * @param order    execution order in the pipeline
     */
    public KeywordBlockerGuard(List<String> keywords, boolean block, int order) {
        this.keywords = keywords == null ? List.of() :
                keywords.stream().map(k -> k.toLowerCase(Locale.ROOT)).toList();
        this.block = block;
        this.order = order;
    }

    @Override
    public String getName() {
        return GUARD_NAME;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public GuardDecision inspect(GuardContext context) {
        if (keywords.isEmpty()) {
            return new GuardDecision.Allow(GUARD_NAME);
        }
        if (context.request().messages() == null) {
            return new GuardDecision.Allow(GUARD_NAME);
        }

        StringBuilder combined = new StringBuilder();
        for (Message msg : context.request().messages()) {
            if (msg.content() != null) {
                combined.append(msg.content().toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        String text = combined.toString();

        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                String reason = "Forbidden keyword detected: '" + keyword + "'";
                return block
                        ? new GuardDecision.Block(GUARD_NAME, reason)
                        : new GuardDecision.Warn(GUARD_NAME, reason);
            }
        }

        return new GuardDecision.Allow(GUARD_NAME);
    }
}
