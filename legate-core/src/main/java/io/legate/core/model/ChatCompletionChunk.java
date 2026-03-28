package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Streaming chunk for server-sent events in chat completion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunk(
        @JsonProperty("id") String id,
        @JsonProperty("object") String object,
        @JsonProperty("created") Long created,
        @JsonProperty("model") String model,
        @JsonProperty("choices") List<Choice> choices,
        @JsonProperty("usage") Usage usage,
        @JsonProperty("system_fingerprint") String systemFingerprint
) {
    /**
     * Default constructor with "chat.completion.chunk" object type.
     */
    public ChatCompletionChunk(
            String id,
            Long created,
            String model,
            List<Choice> choices
    ) {
        this(id, "chat.completion.chunk", created, model, choices, null, null);
    }

    /**
     * Returns true if this chunk contains a finish reason (end of stream).
     */
    public boolean isFinished() {
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        return choices.stream()
                .anyMatch(choice -> choice.finishReason() != null);
    }

    /**
     * Extracts delta content from the first choice.
     */
    public String getDeltaContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice first = choices.get(0);
        if (first.delta() == null) {
            return null;
        }
        return first.delta().content();
    }
}
