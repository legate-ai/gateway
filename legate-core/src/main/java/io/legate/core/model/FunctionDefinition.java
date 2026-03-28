package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Function definition for tool use.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("parameters") Map<String, Object> parameters,
        @JsonProperty("strict") Boolean strict
) {
}
