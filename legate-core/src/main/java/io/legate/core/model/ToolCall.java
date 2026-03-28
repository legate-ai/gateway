package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tool call made by the model.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("function") FunctionCall function
) {
}
