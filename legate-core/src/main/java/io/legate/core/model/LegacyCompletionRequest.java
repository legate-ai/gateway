package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for the legacy {@code POST /v1/completions} endpoint.
 *
 * <p>The {@code prompt} field accepts a string or array of strings.
 * Token arrays are not supported; use the chat completions API instead.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LegacyCompletionRequest(
    @JsonProperty("model") String model,
    @JsonProperty("prompt") Object prompt,
    @JsonProperty("suffix") String suffix,
    @JsonProperty("max_tokens") Integer maxTokens,
    @JsonProperty("temperature") Double temperature,
    @JsonProperty("top_p") Double topP,
    @JsonProperty("n") Integer n,
    @JsonProperty("stream") Boolean stream,
    @JsonProperty("stop") Object stop,
    @JsonProperty("presence_penalty") Double presencePenalty,
    @JsonProperty("frequency_penalty") Double frequencyPenalty,
    @JsonProperty("user") String user
) {
    public String promptText() {
        if (prompt instanceof String s) {
            return s;
        }
        if (prompt != null) {
            return prompt.toString();
        }
        return "";
    }
}
