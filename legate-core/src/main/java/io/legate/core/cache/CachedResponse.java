package io.legate.core.cache;

import io.legate.core.model.ChatCompletionResponse;

import java.time.Instant;

/**
 * A cached {@link ChatCompletionResponse} with expiry metadata.
 *
 * @param response  the cached response
 * @param cachedAt  when the entry was stored
 * @param expiresAt when the entry should be evicted; may be {@code null} if managed by the cache backend
 */
public record CachedResponse(
    ChatCompletionResponse response,
    Instant cachedAt,
    Instant expiresAt
) {
    public CachedResponse(ChatCompletionResponse response) {
        this(response, Instant.now(), null);
    }

    /** Returns {@code true} if this entry has passed its expiry time. */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
