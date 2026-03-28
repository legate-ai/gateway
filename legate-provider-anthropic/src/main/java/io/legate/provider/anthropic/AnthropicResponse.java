package io.legate.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Anthropic Messages API response format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicResponse(
    @JsonProperty("id") String id,
    @JsonProperty("type") String type,
    @JsonProperty("role") String role,
    @JsonProperty("model") String model,
    @JsonProperty("content") List<AnthropicMessage.ContentBlock> content,
    @JsonProperty("stop_reason") String stopReason,
    @JsonProperty("stop_sequence") String stopSequence,
    @JsonProperty("usage") Usage usage
) {
    /**
     * Anthropic usage format.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(
        @JsonProperty("input_tokens") Integer inputTokens,
        @JsonProperty("output_tokens") Integer outputTokens
    ) {
    }

    /**
     * Extracts text content from the response.
     */
    public String getTextContent() {
        if (content == null || content.isEmpty()) {
            return null;
        }
        return content.stream()
            .filter(block -> "text".equals(block.type()))
            .map(AnthropicMessage.ContentBlock::text)
            .findFirst()
            .orElse(null);
    }
}
