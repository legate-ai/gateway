package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Embedding response following OpenAI API specification.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingResponse(
        @JsonProperty("object") String object,
        @JsonProperty("data") List<EmbeddingData> data,
        @JsonProperty("model") String model,
        @JsonProperty("usage") Usage usage
) {
    /**
     * Default constructor with "list" object type.
     */
    public EmbeddingResponse(List<EmbeddingData> data, String model, Usage usage) {
        this("list", data, model, usage);
    }
}
