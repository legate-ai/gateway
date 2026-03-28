package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Embedding request following OpenAI API specification.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingRequest(
        @JsonProperty("model") String model,
        @JsonProperty("input") Object input, // Can be String or List<String>
        @JsonProperty("encoding_format") String encodingFormat,
        @JsonProperty("dimensions") Integer dimensions,
        @JsonProperty("user") String user
) {
    /**
     * Simple constructor for single input string.
     */
    public EmbeddingRequest(String model, String input) {
        this(model, (Object) input, null, null, null);
    }

    /**
     * Constructor for batch inputs.
     */
    public EmbeddingRequest(String model, List<String> inputs) {
        this(model, (Object) inputs, null, null, null);
    }
}
