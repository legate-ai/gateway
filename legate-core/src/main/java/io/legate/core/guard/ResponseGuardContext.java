package io.legate.core.guard;

import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;

/**
 * Context passed to each {@link ResponseGuard#inspect(ResponseGuardContext)}.
 */
public record ResponseGuardContext(
        ChatCompletionResponse response,
        ChatCompletionRequest request,
        VirtualKeyInfo virtualKeyInfo,
        String requestId
) {
}
