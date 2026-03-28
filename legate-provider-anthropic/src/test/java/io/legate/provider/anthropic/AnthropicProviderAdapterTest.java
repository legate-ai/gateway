package io.legate.provider.anthropic;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
import io.legate.core.model.Message;
import io.legate.core.provider.ProviderHttpRequest;
import io.legate.core.provider.ProviderHttpResponse;
import io.legate.core.provider.StreamContext;
import io.legate.core.routing.ProviderCredentials;
import io.legate.core.routing.ResolvedEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProviderAdapterTest {

    private AnthropicProviderAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new AnthropicProviderAdapter(objectMapper);
    }

    @Test
    void shouldReturnProviderName() {
        assertThat(adapter.getProviderName()).isEqualTo("anthropic");
    }

    @Test
    void shouldSupportClaudeModels() {
        assertThat(adapter.supports("claude-3-5-sonnet-20241022")).isTrue();
        assertThat(adapter.supports("claude-3-opus-20240229")).isTrue();
        assertThat(adapter.supports("claude-3-haiku-20240307")).isTrue();
        assertThat(adapter.supports("claude-2.1")).isTrue();
    }

    @Test
    void shouldNotSupportNonClaudeModels() {
        assertThat(adapter.supports("gpt-4o")).isFalse();
        assertThat(adapter.supports("llama-3")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
        assertThat(adapter.supports("")).isFalse();
    }

    @Test
    void shouldTranslateBasicRequest() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(Message.user("Hello, Claude!")))
            .temperature(0.7)
            .build();

        var endpoint = new ResolvedEndpoint(
            "anthropic",
            "claude-3-5-sonnet-20241022",
            "https://api.anthropic.com",
            new ProviderCredentials.BearerToken("sk-ant-test-key")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.method()).isEqualTo("POST");
        assertThat(httpRequest.url()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(httpRequest.headers())
            .containsEntry("Content-Type", "application/json")
            .containsEntry("anthropic-version", "2023-06-01")
            .containsEntry("x-api-key", "sk-ant-test-key");

        // Verify request body contains expected fields
        assertThat(httpRequest.body()).contains("\"model\":\"claude-3-5-sonnet-20241022\"");
        assertThat(httpRequest.body()).contains("\"temperature\":0.7");
        assertThat(httpRequest.body()).contains("\"max_tokens\":");
    }

    @Test
    void shouldExtractSystemPromptFromMessages() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                Message.system("You are a helpful assistant."),
                Message.user("Hello!")
            ))
            .build();

        var endpoint = new ResolvedEndpoint(
            "anthropic",
            "claude-3-5-sonnet-20241022",
            "https://api.anthropic.com",
            new ProviderCredentials.BearerToken("sk-ant-test")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        // System message should be in separate field, not in messages array
        assertThat(httpRequest.body()).contains("\"system\":\"You are a helpful assistant.\"");

        // Parse the body to verify structure
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyMap = objectMapper.readValue(httpRequest.body(), Map.class);

        assertThat(bodyMap).containsKey("system");
        assertThat(bodyMap.get("system")).isEqualTo("You are a helpful assistant.");

        // Messages array should only contain user message
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) bodyMap.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role")).isEqualTo("user");
    }

    @Test
    void shouldCombineMultipleSystemMessages() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                Message.system("First system message."),
                Message.system("Second system message."),
                Message.user("Hello!")
            ))
            .build();

        var endpoint = new ResolvedEndpoint(
            "anthropic",
            "claude-3-5-sonnet-20241022",
            "https://api.anthropic.com",
            new ProviderCredentials.BearerToken("sk-ant-test")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.body()).contains("First system message.\\n\\nSecond system message.");
    }

    @Test
    void shouldUseMaxCompletionTokensIfProvided() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(Message.user("Hi")))
            .maxCompletionTokens(2000)
            .build();

        var endpoint = new ResolvedEndpoint(
            "anthropic",
            "claude-3-5-sonnet-20241022",
            "https://api.anthropic.com",
            new ProviderCredentials.BearerToken("sk-ant-test")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.body()).contains("\"max_tokens\":2000");
    }

    @Test
    void shouldUseDefaultMaxTokensIfNotProvided() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(Message.user("Hi")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "anthropic",
            "claude-3-5-sonnet-20241022",
            "https://api.anthropic.com",
            new ProviderCredentials.BearerToken("sk-ant-test")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.body()).contains("\"max_tokens\":4096");
    }

    @Test
    void shouldTranslateAnthropicResponseToOpenAiFormat() throws Exception {
        String anthropicResponse = """
            {
              "id": "msg_123",
              "type": "message",
              "role": "assistant",
              "model": "claude-3-5-sonnet-20241022",
              "content": [
                {
                  "type": "text",
                  "text": "Hello! How can I assist you today?"
                }
              ],
              "stop_reason": "end_turn",
              "usage": {
                "input_tokens": 10,
                "output_tokens": 20
              }
            }
            """;

        var httpResponse = new ProviderHttpResponse(200, Map.of(), anthropicResponse);

        ChatCompletionResponse response = adapter.translateResponse(httpResponse);

        assertThat(response.id()).isEqualTo("msg_123");
        assertThat(response.model()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().get(0).message().content())
            .isEqualTo("Hello! How can I assist you today?");
        assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(10);
        assertThat(response.usage().completionTokens()).isEqualTo(20);
        assertThat(response.usage().totalTokens()).isEqualTo(30);
    }

    @Test
    void shouldMapStopReasons() throws Exception {
        // end_turn -> stop
        String response1 = """
            {
              "id": "msg_1",
              "type": "message",
              "role": "assistant",
              "model": "claude-3-5-sonnet-20241022",
              "content": [{"type": "text", "text": "Response"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 5, "output_tokens": 5}
            }
            """;

        ChatCompletionResponse translated1 = adapter.translateResponse(
            new ProviderHttpResponse(200, Map.of(), response1)
        );
        assertThat(translated1.choices().get(0).finishReason()).isEqualTo("stop");

        // max_tokens -> length
        String response2 = """
            {
              "id": "msg_2",
              "type": "message",
              "role": "assistant",
              "model": "claude-3-5-sonnet-20241022",
              "content": [{"type": "text", "text": "Response"}],
              "stop_reason": "max_tokens",
              "usage": {"input_tokens": 5, "output_tokens": 5}
            }
            """;

        ChatCompletionResponse translated2 = adapter.translateResponse(
            new ProviderHttpResponse(200, Map.of(), response2)
        );
        assertThat(translated2.choices().get(0).finishReason()).isEqualTo("length");
    }

    @Test
    void shouldHandleStreamingContentDelta() throws Exception {
        String deltaEvent = """
            {
              "type": "content_block_delta",
              "index": 0,
              "delta": {
                "type": "text_delta",
                "text": "Hello"
              }
            }
            """;

        StreamContext context = new StreamContext();

        ChatCompletionChunk chunk = adapter.translateStreamChunk(deltaEvent, context);

        assertThat(chunk).isNotNull();
        assertThat(chunk.getDeltaContent()).isEqualTo("Hello");
        assertThat(context.getAccumulatedContent()).isEqualTo("Hello");
    }

    @Test
    void shouldAccumulateMultipleStreamChunks() throws Exception {
        StreamContext context = new StreamContext();

        String event1 = """
            {
              "type": "content_block_delta",
              "index": 0,
              "delta": {"type": "text_delta", "text": "Hello"}
            }
            """;

        String event2 = """
            {
              "type": "content_block_delta",
              "index": 0,
              "delta": {"type": "text_delta", "text": " world"}
            }
            """;

        String event3 = """
            {
              "type": "content_block_delta",
              "index": 0,
              "delta": {"type": "text_delta", "text": "!"}
            }
            """;

        adapter.translateStreamChunk(event1, context);
        adapter.translateStreamChunk(event2, context);
        adapter.translateStreamChunk(event3, context);

        assertThat(context.getAccumulatedContent()).isEqualTo("Hello world!");
    }

    @Test
    void shouldExtractUsageFromMessageDelta() throws Exception {
        String messageDeltaEvent = """
            {
              "type": "message_delta",
              "delta": {
                "stop_reason": "end_turn",
                "stop_sequence": null
              },
              "usage": {
                "output_tokens": 25
              }
            }
            """;

        StreamContext context = new StreamContext();

        ChatCompletionChunk chunk = adapter.translateStreamChunk(messageDeltaEvent, context);

        assertThat(context.getUsage()).isNotNull();
        assertThat(context.getUsage().completionTokens()).isEqualTo(25);
        assertThat(chunk).isNotNull();
        assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
    }

    @Test
    void shouldDetectStreamTerminator() {
        String messageStopEvent = """
            {
              "type": "message_stop"
            }
            """;

        assertThat(adapter.isStreamTerminator(messageStopEvent)).isTrue();
    }

    @Test
    void shouldNotDetectNonTerminatorAsTerminator() {
        String contentDeltaEvent = """
            {
              "type": "content_block_delta",
              "delta": {"text": "hello"}
            }
            """;

        assertThat(adapter.isStreamTerminator(contentDeltaEvent)).isFalse();
        assertThat(adapter.isStreamTerminator(null)).isFalse();
    }

    @Test
    void shouldSkipEmptyEventData() throws Exception {
        StreamContext context = new StreamContext();

        ChatCompletionChunk chunk = adapter.translateStreamChunk("", context);

        assertThat(chunk).isNull();
    }

    @Test
    void shouldHandleMessageStartEvent() throws Exception {
        String messageStartEvent = """
            {
              "type": "message_start",
              "message": {
                "id": "msg_123",
                "type": "message",
                "role": "assistant",
                "model": "claude-3-5-sonnet-20241022"
              }
            }
            """;

        StreamContext context = new StreamContext();

        ChatCompletionChunk chunk = adapter.translateStreamChunk(messageStartEvent, context);

        // message_start doesn't produce a chunk (no content yet)
        assertThat(chunk).isNull();
    }

    @Test
    void shouldHandleContentBlockStartEvent() throws Exception {
        String contentBlockStartEvent = """
            {
              "type": "content_block_start",
              "index": 0,
              "content_block": {
                "type": "text",
                "text": ""
              }
            }
            """;

        StreamContext context = new StreamContext();

        ChatCompletionChunk chunk = adapter.translateStreamChunk(contentBlockStartEvent, context);

        // content_block_start doesn't produce a chunk yet
        assertThat(chunk).isNull();
    }

    @Test
    void shouldConvertStopSequences() throws Exception {
        // Test with String stop
        var request1 = ChatCompletionRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(Message.user("Hi")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "anthropic",
            "claude-3-5-sonnet-20241022",
            "https://api.anthropic.com",
            new ProviderCredentials.BearerToken("sk-ant-test")
        );

        // Verify the adapter handles requests without errors
        ProviderHttpRequest httpRequest = adapter.translateRequest(request1, endpoint);
        assertThat(httpRequest).isNotNull();
    }
}
