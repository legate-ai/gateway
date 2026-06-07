package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response body for the legacy {@code POST /v1/completions} endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LegacyCompletionResponse(
    @JsonProperty("id") String id,
    @JsonProperty("object") String object,
    @JsonProperty("created") Long created,
    @JsonProperty("model") String model,
    @JsonProperty("choices") List<LegacyChoice> choices,
    @JsonProperty("usage") Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LegacyChoice(
        @JsonProperty("text") String text,
        @JsonProperty("index") Integer index,
        @JsonProperty("logprobs") Object logprobs,
        @JsonProperty("finish_reason") String finishReason
    ) {}

    public static LegacyCompletionResponse from(ChatCompletionResponse chat) {
        List<LegacyChoice> legacyChoices = chat.choices() == null ? List.of()
            : chat.choices().stream()
                .map(c -> new LegacyChoice(
                    c.message() != null ? c.message().content() : null,
                    c.index(),
                    null,
                    c.finishReason()))
                .toList();

        String id = chat.id() != null ? chat.id().replace("chatcmpl-", "cmpl-") : null;
        return new LegacyCompletionResponse(id, "text_completion", chat.created(),
            chat.model(), legacyChoices, chat.usage());
    }
}
