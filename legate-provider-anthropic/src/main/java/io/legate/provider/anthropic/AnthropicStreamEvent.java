package io.legate.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Anthropic streaming event format.
 * Events: message_start, content_block_start, content_block_delta,
 * content_block_stop, message_delta, message_stop
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicStreamEvent(
    @JsonProperty("type") String type,
    @JsonProperty("index") Integer index,
    @JsonProperty("message") Object message,
    @JsonProperty("content_block") Object contentBlock,
    @JsonProperty("delta") Delta delta,
    @JsonProperty("usage") AnthropicResponse.Usage usage
) {
    /**
     * Delta for content_block_delta events.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text,
        @JsonProperty("stop_reason") String stopReason,
        @JsonProperty("stop_sequence") String stopSequence
    ) {
    }

    /**
     * Returns true if this is a message_stop event (end of stream).
     */
    public boolean isMessageStop() {
        return "message_stop".equals(type);
    }

    /**
     * Returns true if this is a content_block_delta event.
     */
    public boolean isContentDelta() {
        return "content_block_delta".equals(type);
    }

    /**
     * Returns true if this is a message_delta event.
     */
    public boolean isMessageDelta() {
        return "message_delta".equals(type);
    }
}
