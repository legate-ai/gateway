package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single choice in a chat completion response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Choice(
        @JsonProperty("index") Integer index,
        @JsonProperty("message") Message message,
        @JsonProperty("delta") Message delta,
        @JsonProperty("finish_reason") String finishReason,
        @JsonProperty("logprobs") Object logprobs
) {
}
