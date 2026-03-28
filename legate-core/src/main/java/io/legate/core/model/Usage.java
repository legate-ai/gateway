package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage information for a completion request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("total_tokens") Integer totalTokens
) {
    /**
     * Creates a Usage record with calculated total.
     */
    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    /**
     * Adds two usage records together.
     */
    public Usage add(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(
                (this.promptTokens != null ? this.promptTokens : 0) + (other.promptTokens != null ? other.promptTokens : 0),
                (this.completionTokens != null ? this.completionTokens : 0) + (other.completionTokens != null ? other.completionTokens : 0),
                (this.totalTokens != null ? this.totalTokens : 0) + (other.totalTokens != null ? other.totalTokens : 0)
        );
    }
}
