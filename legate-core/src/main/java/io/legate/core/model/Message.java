package io.legate.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a message in a chat completion conversation.
 * Supports text content, tool calls, and function calls.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("name") String name,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {
    /**
     * Creates a simple user message.
     */
    public static Message user(String content) {
        return new Message("user", content, null, null, null);
    }

    /**
     * Creates a simple assistant message.
     */
    public static Message assistant(String content) {
        return new Message("assistant", content, null, null, null);
    }

    /**
     * Creates a system message.
     */
    public static Message system(String content) {
        return new Message("system", content, null, null, null);
    }

    /**
     * Creates a tool response message.
     */
    public static Message tool(String content, String toolCallId) {
        return new Message("tool", content, null, null, toolCallId);
    }
}
