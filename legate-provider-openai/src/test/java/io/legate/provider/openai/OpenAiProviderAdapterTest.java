package io.legate.provider.openai;

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

class OpenAiProviderAdapterTest {

    private OpenAiProviderAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new OpenAiProviderAdapter(objectMapper);
    }

    @Test
    void shouldReturnProviderName() {
        assertThat(adapter.getProviderName()).isEqualTo("openai");
    }

    @Test
    void shouldSupportOpenAiModels() {
        assertThat(adapter.supports("gpt-4o")).isTrue();
        assertThat(adapter.supports("gpt-4o-mini")).isTrue();
        assertThat(adapter.supports("gpt-3.5-turbo")).isTrue();
        assertThat(adapter.supports("o1-preview")).isTrue();
        assertThat(adapter.supports("text-embedding-3-small")).isTrue();
    }

    @Test
    void shouldNotSupportUnknownModels() {
        // Unknown/non-OpenAI models must not be caught by this adapter
        assertThat(adapter.supports("llama-3-70b")).isFalse();
        assertThat(adapter.supports("custom-model")).isFalse();
        assertThat(adapter.supports("claude-3-opus")).isFalse();
    }

    @Test
    void shouldNotSupportNullOrBlankModelNames() {
        assertThat(adapter.supports(null)).isFalse();
        assertThat(adapter.supports("")).isFalse();
        assertThat(adapter.supports("  ")).isFalse();
    }

    @Test
    void shouldTranslateRequestWithBearerToken() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.7)
            .build();

        var endpoint = new ResolvedEndpoint(
            "openai",
            "gpt-4o",
            "https://api.openai.com",
            new ProviderCredentials.BearerToken("sk-test-key")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.method()).isEqualTo("POST");
        assertThat(httpRequest.url()).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(httpRequest.headers())
            .containsEntry("Content-Type", "application/json")
            .containsEntry("Authorization", "Bearer sk-test-key");
        assertThat(httpRequest.body()).contains("\"model\":\"gpt-4o\"");
        assertThat(httpRequest.body()).contains("\"temperature\":0.7");
    }

    @Test
    void shouldTranslateRequestWithApiKeyHeader() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "openai",
            "gpt-4o",
            "https://api.openai.com",
            new ProviderCredentials.ApiKeyHeader("X-API-Key", "test-key")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.headers())
            .containsEntry("X-API-Key", "test-key");
    }

    @Test
    void shouldHandleBaseUrlWithTrailingSlash() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hi")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "openai",
            "gpt-4o",
            "https://api.openai.com/",
            new ProviderCredentials.BearerToken("sk-test")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.url()).isEqualTo("https://api.openai.com/v1/chat/completions");
    }

    @Test
    void shouldHandleBaseUrlWithPath() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hi")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "openai",
            "gpt-4o",
            "https://api.openai.com/v1/chat/completions",
            new ProviderCredentials.BearerToken("sk-test")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        // Should not duplicate the path
        assertThat(httpRequest.url()).isEqualTo("https://api.openai.com/v1/chat/completions");
    }

    @Test
    void shouldTranslateSuccessResponse() throws Exception {
        String responseBody = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "Hello! How can I help you today?"
                },
                "finish_reason": "stop"
              }],
              "usage": {
                "prompt_tokens": 9,
                "completion_tokens": 12,
                "total_tokens": 21
              }
            }
            """;

        var httpResponse = new ProviderHttpResponse(200, Map.of(), responseBody);

        ChatCompletionResponse response = adapter.translateResponse(httpResponse);

        assertThat(response.id()).isEqualTo("chatcmpl-123");
        assertThat(response.model()).isEqualTo("gpt-4o");
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().get(0).message().content())
            .isEqualTo("Hello! How can I help you today?");
        assertThat(response.usage().totalTokens()).isEqualTo(21);
    }

    @Test
    void shouldTranslateStreamChunk() throws Exception {
        String chunkData = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion.chunk",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [{
                "index": 0,
                "delta": {
                  "content": "Hello"
                },
                "finish_reason": null
              }]
            }
            """;

        StreamContext context = new StreamContext();

        ChatCompletionChunk chunk = adapter.translateStreamChunk(chunkData, context);

        assertThat(chunk).isNotNull();
        assertThat(chunk.id()).isEqualTo("chatcmpl-123");
        assertThat(chunk.getDeltaContent()).isEqualTo("Hello");
        assertThat(context.getAccumulatedContent()).isEqualTo("Hello");
    }

    @Test
    void shouldHandleMultipleStreamChunks() throws Exception {
        StreamContext context = new StreamContext();

        String chunk1 = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion.chunk",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [{"index": 0, "delta": {"content": "Hello"}, "finish_reason": null}]
            }
            """;

        String chunk2 = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion.chunk",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [{"index": 0, "delta": {"content": " world"}, "finish_reason": null}]
            }
            """;

        String chunk3 = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion.chunk",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [{"index": 0, "delta": {"content": "!"}, "finish_reason": "stop"}]
            }
            """;

        adapter.translateStreamChunk(chunk1, context);
        adapter.translateStreamChunk(chunk2, context);
        adapter.translateStreamChunk(chunk3, context);

        assertThat(context.getAccumulatedContent()).isEqualTo("Hello world!");
        assertThat(context.getFinishReason()).isEqualTo("stop");
        assertThat(context.isFinished()).isTrue();
    }

    @Test
    void shouldDetectStreamTerminator() {
        assertThat(adapter.isStreamTerminator("[DONE]")).isTrue();
        assertThat(adapter.isStreamTerminator("  [DONE]  ")).isTrue();
    }

    @Test
    void shouldNotDetectNonTerminatorAsTerminator() {
        assertThat(adapter.isStreamTerminator("some data")).isFalse();
        assertThat(adapter.isStreamTerminator("")).isFalse();
        assertThat(adapter.isStreamTerminator(null)).isFalse();
    }

    @Test
    void shouldSkipEmptyStreamChunks() throws Exception {
        StreamContext context = new StreamContext();

        ChatCompletionChunk chunk = adapter.translateStreamChunk("", context);

        assertThat(chunk).isNull();
    }

    @Test
    void shouldSkipTerminatorStreamChunk() throws Exception {
        StreamContext context = new StreamContext();

        ChatCompletionChunk chunk = adapter.translateStreamChunk("[DONE]", context);

        assertThat(chunk).isNull();
    }
}
