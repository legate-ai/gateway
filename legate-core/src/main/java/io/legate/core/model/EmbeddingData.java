package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Single embedding data item.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingData(
        @JsonProperty("object") String object,
        @JsonProperty("embedding") List<Double> embedding,
        @JsonProperty("index") Integer index
) {
    /**
     * Default constructor with "embedding" object type.
     */
    public EmbeddingData(List<Double> embedding, int index) {
        this("embedding", embedding, index);
    }
}
