package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat completion request following OpenAI API specification.
 * Unknown fields are preserved in the extra map for forward compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = ChatCompletionRequest.Builder.class)
public record ChatCompletionRequest(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<Message> messages,
        @JsonProperty("temperature") Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("n") Integer n,
        @JsonProperty("stream") Boolean stream,
        @JsonProperty("stop") Object stop, // Can be String or List<String>
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        @JsonProperty("presence_penalty") Double presencePenalty,
        @JsonProperty("frequency_penalty") Double frequencyPenalty,
        @JsonProperty("logit_bias") Map<String, Integer> logitBias,
        @JsonProperty("user") String user,
        @JsonProperty("tools") List<Tool> tools,
        @JsonProperty("tool_choice") Object toolChoice, // Can be String or Object
        @JsonProperty("response_format") Map<String, Object> responseFormat,
        @JsonProperty("seed") Integer seed,
        @JsonProperty("logprobs") Boolean logprobs,
        @JsonProperty("top_logprobs") Integer topLogprobs,
        Map<String, Object> extra
) {
    /**
     * Compact constructor to ensure extra map is never null.
     */
    public ChatCompletionRequest {
        if (extra == null) {
            extra = new HashMap<>();
        }
    }

    /**
     * Creates a new request with the same fields but different model.
     */
    public ChatCompletionRequest withModel(String newModel) {
        return new ChatCompletionRequest(
                newModel, messages, temperature, topP, n, stream, stop,
                maxTokens, maxCompletionTokens, presencePenalty, frequencyPenalty,
                logitBias, user, tools, toolChoice, responseFormat, seed,
                logprobs, topLogprobs, new HashMap<>(extra)
        );
    }

    /**
     * Creates a new request with modified messages.
     */
    public ChatCompletionRequest withMessages(List<Message> newMessages) {
        return new ChatCompletionRequest(
                model, newMessages, temperature, topP, n, stream, stop,
                maxTokens, maxCompletionTokens, presencePenalty, frequencyPenalty,
                logitBias, user, tools, toolChoice, responseFormat, seed,
                logprobs, topLogprobs, new HashMap<>(extra)
        );
    }

    /**
     * Returns true if this request is cacheable (deterministic).
     */
    public boolean isCacheable() {
        return (stream == null || !stream) &&
                (n == null || n == 1) &&
                (temperature == null || temperature == 0.0);
    }

    /**
     * Exposes extra properties for JSON serialization.
     */
    @JsonAnyGetter
    public Map<String, Object> extra() {
        return extra;
    }

    /**
     * Factory method to create a builder-like instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ChatCompletionRequest.
     * withPrefix="" tells Jackson the builder setters use plain method names (model(), messages(), ...).
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private String model;
        private List<Message> messages;
        private Double temperature;
        private Double topP;
        private Integer n;
        private Boolean stream;
        private Object stop;
        private Integer maxTokens;
        private Integer maxCompletionTokens;
        private Double presencePenalty;
        private Double frequencyPenalty;
        private Map<String, Integer> logitBias;
        private String user;
        private List<Tool> tools;
        private Object toolChoice;
        private Map<String, Object> responseFormat;
        private Integer seed;
        private Boolean logprobs;
        private Integer topLogprobs;
        private final Map<String, Object> extra = new HashMap<>();

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder n(Integer n) {
            this.n = n;
            return this;
        }

        public Builder stream(Boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder maxCompletionTokens(Integer maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        @JsonAnySetter
        public Builder extra(String key, Object value) {
            this.extra.put(key, value);
            return this;
        }

        public ChatCompletionRequest build() {
            return new ChatCompletionRequest(
                    model, messages, temperature, topP, n, stream, stop,
                    maxTokens, maxCompletionTokens, presencePenalty, frequencyPenalty,
                    logitBias, user, tools, toolChoice, responseFormat, seed,
                    logprobs, topLogprobs, extra
            );
        }
    }
}
