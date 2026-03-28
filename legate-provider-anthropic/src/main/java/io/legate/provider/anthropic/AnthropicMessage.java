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
     * Content block for structured content.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlock(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text,
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("input") Object input
    ) {
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
