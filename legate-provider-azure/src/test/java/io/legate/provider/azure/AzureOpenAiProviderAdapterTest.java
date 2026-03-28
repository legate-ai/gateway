package io.legate.provider.azure;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AzureOpenAiProviderAdapterTest {

    private AzureOpenAiProviderAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new AzureOpenAiProviderAdapter(objectMapper);
    }

    // ── Provider metadata ──────────────────────────────────────────────────────

    @Test
    void getProviderName_returnsAzureOpenAi() {
        assertThat(adapter.getProviderName()).isEqualTo("azure-openai");
    }

    @Test
    void supports_returnsFalse_forAllModels() {
        // Azure is selected via provider name, not model prefix
        assertThat(adapter.supports("gpt-4o")).isFalse();
        assertThat(adapter.supports("gpt-4-turbo")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    // ── Request translation ────────────────────────────────────────────────────

    @Test
    void translateRequest_buildsCorrectAzureUrl() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello Azure")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "azure-openai",
            "my-gpt4o-deployment",
            "https://my-resource.openai.azure.com",
            new ProviderCredentials.BearerToken("azure-api-key-123")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.method()).isEqualTo("POST");
        assertThat(httpRequest.url())
            .contains("my-resource.openai.azure.com")
            .contains("/openai/deployments/my-gpt4o-deployment")
            .contains("/chat/completions")
            .contains("api-version=2024-10-21");
    }

    @Test
    void translateRequest_setsApiKeyHeader() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "azure-openai",
            "gpt-4o",
            "https://myresource.openai.azure.com",
            new ProviderCredentials.BearerToken("my-azure-key")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        // Azure uses api-key, not Bearer token
        assertThat(httpRequest.headers()).containsEntry("api-key", "my-azure-key");
        assertThat(httpRequest.headers()).doesNotContainKey("Authorization");
    }

    @Test
    void translateRequest_setsContentTypeHeader() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "azure-openai",
            "gpt-4o",
            "https://myresource.openai.azure.com",
            new ProviderCredentials.BearerToken("key")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.headers()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void translateRequest_withApiKeyHeaderCredentials() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "azure-openai",
            "gpt-4o",
            "https://myresource.openai.azure.com",
            new ProviderCredentials.ApiKeyHeader("Ocp-Apim-Subscription-Key", "subscription-key-123")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.headers()).containsEntry("Ocp-Apim-Subscription-Key", "subscription-key-123");
    }

    @Test
    void translateRequest_includesRequestBodyAsJson() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello Azure")))
            .temperature(0.5)
            .maxTokens(100)
            .build();

        var endpoint = new ResolvedEndpoint(
            "azure-openai",
            "gpt-4o",
            "https://myresource.openai.azure.com",
            new ProviderCredentials.BearerToken("key")
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.body()).contains("\"model\":\"gpt-4o\"");
        assertThat(httpRequest.body()).contains("\"temperature\":0.5");
        assertThat(httpRequest.body()).contains("\"max_tokens\":100");
    }

    // ── Response translation ───────────────────────────────────────────────────

    @Test
    void translateResponse_parsesOpenAiFormatResponse() throws Exception {
        String responseBody = """
            {
              "id": "chatcmpl-azure-123",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "Hello from Azure OpenAI!"
                },
                "finish_reason": "stop"
              }],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 7,
                "total_tokens": 17
              }
            }
            """;

        var httpResponse = new ProviderHttpResponse(200, Map.of(), responseBody);

        ChatCompletionResponse response = adapter.translateResponse(httpResponse);

        assertThat(response.id()).isEqualTo("chatcmpl-azure-123");
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().get(0).message().content()).isEqualTo("Hello from Azure OpenAI!");
        assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(response.usage().totalTokens()).isEqualTo(17);
    }

    @Test
    void translateResponse_throwsOnErrorStatus() {
        var errorResponse = new ProviderHttpResponse(401, Map.of(), "{\"error\":{\"message\":\"Unauthorized\"}}");

        assertThatThrownBy(() -> adapter.translateResponse(errorResponse))
            .isInstanceOf(io.legate.core.exception.UpstreamException.class);
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Test
    void translateStreamChunk_parsesOpenAiStreamChunk() throws Exception {
        String chunkData = """
            {
              "id": "chatcmpl-azure-stream-123",
              "object": "chat.completion.chunk",
              "created": 1677652288,
              "model": "gpt-4",
              "choices": [{
                "index": 0,
                "delta": {"content": "Hello"},
                "finish_reason": null
              }]
            }
            """;

        StreamContext context = new StreamContext();
        ChatCompletionChunk chunk = adapter.translateStreamChunk(chunkData, context);

        assertThat(chunk).isNotNull();
        assertThat(chunk.getDeltaContent()).isEqualTo("Hello");
    }

    @Test
    void translateStreamChunk_nullForEmptyData() throws Exception {
        StreamContext context = new StreamContext();

        assertThat(adapter.translateStreamChunk(null, context)).isNull();
        assertThat(adapter.translateStreamChunk("", context)).isNull();
        assertThat(adapter.translateStreamChunk("   ", context)).isNull();
    }

    @Test
    void translateStreamChunk_nullForDoneTerminator() throws Exception {
        StreamContext context = new StreamContext();
        assertThat(adapter.translateStreamChunk("[DONE]", context)).isNull();
    }

    @Test
    void isStreamTerminator_detectsDone() {
        assertThat(adapter.isStreamTerminator("[DONE]")).isTrue();
    }

    @Test
    void isStreamTerminator_returnsFalseForNonTerminator() {
        assertThat(adapter.isStreamTerminator("{\"choices\":[]}")).isFalse();
        assertThat(adapter.isStreamTerminator(null)).isFalse();
        assertThat(adapter.isStreamTerminator("")).isFalse();
    }
}
