package io.legate.core.guard;

import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.model.ChatCompletionRequest;

import java.util.Map;

/**
 * Context passed to each {@link RequestGuard#inspect(GuardContext)}.
 *
 * <p>Guards receive the effective request (possibly modified by earlier guards in the
 * same pipeline pass), the authenticated virtual key info (may be {@code null} in
 * dev/no-auth mode), and the request HTTP headers.</p>
 */
public record GuardContext(
        ChatCompletionRequest request,
        VirtualKeyInfo virtualKeyInfo,
        Map<String, String> headers,
        String requestId
) {
    public GuardContext {
        if (headers == null) {
            headers = Map.of();
        }
    }
}
