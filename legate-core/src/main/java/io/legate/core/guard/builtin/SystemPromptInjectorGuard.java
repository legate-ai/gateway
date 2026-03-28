package io.legate.core.guard.builtin;

import io.legate.core.guard.GuardContext;
import io.legate.core.guard.GuardDecision;
import io.legate.core.guard.RequestGuard;
import io.legate.core.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in guard that prepends a configured system message to every request.
 *
 * <p>If the request already has a system message at index 0, the injected system
 * prompt is prepended before it (making it the first message the model sees).
 * This ensures operator-level instructions are always present.</p>
 *
 * <p>Always returns {@link GuardDecision.Modify} so the injected prompt flows through
 * to the upstream call.</p>
 *
 * <p>Default execution order: 50 (runs before all other built-in guards).</p>
 */
public class SystemPromptInjectorGuard implements RequestGuard {

    private static final String GUARD_NAME = "system-prompt-injector";

    private final String systemPrompt;
    private final int order;

    /**
     * @param systemPrompt the system message to inject into every request
     * @param order        execution order in the pipeline (default 50)
     */
    public SystemPromptInjectorGuard(String systemPrompt, int order) {
        this.systemPrompt = systemPrompt;
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
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return new GuardDecision.Allow(GUARD_NAME);
        }

        List<Message> original = context.request().messages();
        List<Message> modified = new ArrayList<>();
        modified.add(Message.system(systemPrompt));
        if (original != null) {
            modified.addAll(original);
        }

        return new GuardDecision.Modify(
                GUARD_NAME,
                context.request().withMessages(modified),
                "System prompt injected"
        );
    }
}
