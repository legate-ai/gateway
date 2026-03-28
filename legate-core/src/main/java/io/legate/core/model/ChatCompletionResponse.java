package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Chat completion response following OpenAI API specification.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(
        @JsonProperty("id") String id,
        @JsonProperty("object") String object,
        @JsonProperty("created") Long created,
        @JsonProperty("model") String model,
        @JsonProperty("choices") List<Choice> choices,
        @JsonProperty("usage") Usage usage,
        @JsonProperty("system_fingerprint") String systemFingerprint,
        @JsonProperty("service_tier") String serviceTier
) {
    /**
     * Default constructor with "chat.completion" object type.
     */
    public ChatCompletionResponse(
            String id,
            Long created,
            String model,
            List<Choice> choices,
            Usage usage
    ) {
        this(id, "chat.completion", created, model, choices, usage, null, null);
    }

    /**
     * Returns the first choice message content, or null if no choices.
     */
    public String getFirstMessageContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice first = choices.get(0);
        if (first.message() == null) {
            return null;
        }
        return first.message().content();
    }
}
