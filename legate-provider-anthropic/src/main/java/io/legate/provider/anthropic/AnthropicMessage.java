package io.legate.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Anthropic message format (different from OpenAI).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicMessage(
    @JsonProperty("role") String role,
    @JsonProperty("content") Object content // Can be String or List<ContentBlock>
) {
    /**
     * Anthropic cache-control directive. Attach to content blocks or the system prompt
     * to enable prompt caching (requires {@code anthropic-beta: prompt-caching-2024-07-31}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CacheControl(@JsonProperty("type") String type) {
        public static CacheControl ephemeral() {
            return new CacheControl("ephemeral");
        }
    }

    /**
     * Content block for structured content, with optional prompt-caching hint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlock(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text,
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("input") Object input,
        @JsonProperty("cache_control") CacheControl cacheControl
    ) {
        /** Convenience constructor without cache_control (backward compatibility). */
        public ContentBlock(String type, String text, String id, String name, Object input) {
            this(type, text, id, name, input, null);
        }
    }

    /**
     * System prompt block that can carry a cache_control directive.
     * Used when the system prompt needs to be cached.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SystemBlock(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text,
        @JsonProperty("cache_control") CacheControl cacheControl
    ) {
        public static SystemBlock text(String text) {
            return new SystemBlock("text", text, null);
        }

        public static SystemBlock cachedText(String text) {
            return new SystemBlock("text", text, CacheControl.ephemeral());
        }

        public boolean hasCacheControl() {
            return cacheControl != null;
        }
    }

    /**
     * Creates a simple text message.
     */
    public static AnthropicMessage text(String role, String content) {
        return new AnthropicMessage(role, content);
    }

    /**
     * Creates a message with structured content blocks.
     */
    public static AnthropicMessage blocks(String role, List<ContentBlock> blocks) {
        return new AnthropicMessage(role, blocks);
    }
}
