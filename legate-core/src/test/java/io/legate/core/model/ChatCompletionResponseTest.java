package io.legate.core.model;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCompletionResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldSerializeResponse() throws Exception {
        var response = new ChatCompletionResponse(
            "chatcmpl-123",
            System.currentTimeMillis() / 1000,
            "gpt-4o",
            List.of(new Choice(
                0,
                Message.assistant("Hello! How can I help you?"),
                null,
                "stop",
                null
            )),
            Usage.of(10, 15)
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"id\":\"chatcmpl-123\"");
        assertThat(json).contains("\"model\":\"gpt-4o\"");
        assertThat(json).contains("\"object\":\"chat.completion\"");
        assertThat(json).contains("\"finish_reason\":\"stop\"");
    }

    @Test
    void shouldDeserializeResponse() throws Exception {
        String json = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion",
              "created": 1234567890,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "Hello!"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15
              }
            }
            """;

        ChatCompletionResponse response = objectMapper.readValue(json, ChatCompletionResponse.class);

        assertThat(response.id()).isEqualTo("chatcmpl-123");
        assertThat(response.model()).isEqualTo("gpt-4o");
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().get(0).message().content()).isEqualTo("Hello!");
        assertThat(response.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void shouldExtractFirstMessageContent() {
        var response = new ChatCompletionResponse(
            "chatcmpl-123",
            System.currentTimeMillis() / 1000,
            "gpt-4o",
            List.of(new Choice(
                0,
                Message.assistant("First response"),
                null,
                "stop",
                null
            )),
            Usage.of(10, 5)
        );

        assertThat(response.getFirstMessageContent()).isEqualTo("First response");
    }

    @Test
    void shouldReturnNullForEmptyChoices() {
        var response = new ChatCompletionResponse(
            "chatcmpl-123",
            System.currentTimeMillis() / 1000,
            "gpt-4o",
            List.of(),
            Usage.of(10, 5)
        );

        assertThat(response.getFirstMessageContent()).isNull();
    }
}
