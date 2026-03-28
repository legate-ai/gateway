package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tool definition for tool use.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tool(
        @JsonProperty("type") String type,
        @JsonProperty("function") FunctionDefinition function
) {
    /**
     * Creates a function tool.
     */
    public static Tool function(FunctionDefinition function) {
        return new Tool("function", function);
    }
}
