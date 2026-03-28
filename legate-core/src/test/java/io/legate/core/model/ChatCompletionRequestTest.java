package io.legate.core.model;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class ChatCompletionRequestTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldSerializeBasicRequest() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.7)
            .build();

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"model\":\"gpt-4o\"");
        assertThat(json).contains("\"role\":\"user\"");
        assertThat(json).contains("\"content\":\"Hello\"");
        assertThat(json).contains("\"temperature\":0.7");
    }

    @Test
    void shouldDeserializeBasicRequest() throws Exception {
        String json = """
            {
              "model": "gpt-4o",
              "messages": [
                {"role": "user", "content": "Hello"}
              ],
              "temperature": 0.7
            }
            """;

        ChatCompletionRequest request = objectMapper.readValue(json, ChatCompletionRequest.class);

        assertThat(request.model()).isEqualTo("gpt-4o");
        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().get(0).role()).isEqualTo("user");
        assertThat(request.messages().get(0).content()).isEqualTo("Hello");
        assertThat(request.temperature()).isEqualTo(0.7);
    }

    @Test
    void shouldPreserveUnknownFieldsInExtraMap() throws Exception {
        String json = """
            {
              "model": "gpt-4o",
              "messages": [{"role": "user", "content": "Hi"}],
              "unknown_field": "value",
              "another_unknown": 123
            }
            """;

        ChatCompletionRequest request = objectMapper.readValue(json, ChatCompletionRequest.class);

        assertThat(request.extra())
            .containsEntry("unknown_field", "value")
            .containsEntry("another_unknown", 123);
    }

    @Test
    void shouldSerializeExtraFields() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hi")))
            .extra("custom_field", "custom_value")
            .extra("number_field", 42)
            .build();

        String json = objectMapper.writeValueAsString(request);
        Map<String, Object> result = objectMapper.readValue(json, Map.class);

        assertThat(result).contains(
            entry("custom_field", "custom_value"),
            entry("number_field", 42)
        );
    }

    @Test
    void shouldIdentifyCacheableRequests() {
        var cacheableRequest = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hi")))
            .temperature(0.0)
            .stream(false)
            .build();

        assertThat(cacheableRequest.isCacheable()).isTrue();
    }

    @Test
    void shouldIdentifyNonCacheableRequests() {
        var streamingRequest = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hi")))
            .stream(true)
            .build();

        assertThat(streamingRequest.isCacheable()).isFalse();

        var nonDeterministicRequest = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hi")))
            .temperature(0.7)
            .build();

        assertThat(nonDeterministicRequest.isCacheable()).isFalse();
    }

    @Test
    void shouldCreateRequestWithModifiedModel() {
        var original = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hi")))
            .temperature(0.7)
            .build();

        var modified = original.withModel("gpt-4o-mini");

        assertThat(modified.model()).isEqualTo("gpt-4o-mini");
        assertThat(modified.messages()).isEqualTo(original.messages());
        assertThat(modified.temperature()).isEqualTo(original.temperature());
    }
}
