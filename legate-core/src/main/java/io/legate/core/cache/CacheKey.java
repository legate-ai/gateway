package io.legate.core.cache;

import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A normalised, hashable key for the response cache.
 *
 * <p>The key is a SHA-256 digest of the canonical request representation:
 * model + sorted messages (role + content) + temperature + topP + maxTokens
 * + tools + toolChoice.</p>
 *
 * <p>A request is cacheable only when:</p>
 * <ul>
 *   <li>{@code stream} is {@code null} or {@code false}</li>
 *   <li>{@code temperature} is {@code null} or {@code 0.0}</li>
 *   <li>{@code n} is {@code null} or {@code 1}</li>
 * </ul>
 */
public record CacheKey(String hash) {

    /**
     * Computes a cache key for the given request.
     *
     * @param request the request to hash; must be cacheable
     * @return the cache key
     * @throws IllegalArgumentException if SHA-256 is not available (should never happen on JDK)
     */
    public static CacheKey from(ChatCompletionRequest request) {
        String normalised = normalise(request);
        return new CacheKey(sha256(normalised));
    }

    /**
     * Returns {@code true} if the request qualifies for cache lookup/storage.
     */
    public static boolean isCacheable(ChatCompletionRequest request) {
        return request != null && request.isCacheable();
    }

    // -------------------------------------------------------------------------

    private static String normalise(ChatCompletionRequest chatCompletionRequest) {
        StringBuilder sb = new StringBuilder();
        sb.append("model=").append(chatCompletionRequest.model()).append(";");

        // Sort messages for determinism (in practice order matters, but we sort for canonical form)
        if (chatCompletionRequest.messages() != null) {
            List<Message> sorted = chatCompletionRequest.messages().stream()
                .sorted(Comparator.comparing((Message m) -> m.role() == null ? "" : m.role())
                    .thenComparing(m -> m.content() == null ? "" : m.content()))
                .toList();
            for (Message message : sorted) {
                sb.append("role=").append(message.role()).append(",content=")
                  .append(message.content() == null ? "" : message.content()).append(";");
            }
        }

        sb.append("temperature=").append(chatCompletionRequest.temperature()).append(";");
        sb.append("topP=").append(chatCompletionRequest.topP()).append(";");
        sb.append("maxTokens=").append(chatCompletionRequest.maxTokens()).append(";");
        sb.append("tools=").append(chatCompletionRequest.tools() != null ? chatCompletionRequest.tools().toString() : "null").append(";");
        sb.append("toolChoice=").append(chatCompletionRequest.toolChoice()).append(";");
        return sb.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
