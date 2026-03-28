package io.legate.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Anthropic Messages API request format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicRequest(
    @JsonProperty("model") String model,
    @JsonProperty("messages") List<AnthropicMessage> messages,
    @JsonProperty("system") String system,
    @JsonProperty("max_tokens") int maxTokens,
    @JsonProperty("temperature") Double temperature,
    @JsonProperty("top_p") Double topP,
    @JsonProperty("top_k") Integer topK,
    @JsonProperty("stop_sequences") List<String> stopSequences,
    @JsonProperty("stream") Boolean stream,
    @JsonProperty("metadata") Object metadata,
    @JsonProperty("tools") List<Object> tools
) {
}
