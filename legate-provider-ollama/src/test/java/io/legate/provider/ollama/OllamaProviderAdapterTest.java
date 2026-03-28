package io.legate.provider.ollama;

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

class OllamaProviderAdapterTest {

    private OllamaProviderAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new OllamaProviderAdapter(objectMapper);
    }

    private ResolvedEndpoint localEndpoint(String model) {
        return new ResolvedEndpoint(
            "ollama",
            model,
            "http://localhost:11434",
            new ProviderCredentials.None()
        );
    }

    // ── Provider metadata ──────────────────────────────────────────────────────

    @Test
    void getProviderName_returnsOllama() {
        assertThat(adapter.getProviderName()).isEqualTo("ollama");
    }

    @Test
    void supports_returnsFalse_forAllModels() {
        // Ollama is selected via provider name in fallback chains
        assertThat(adapter.supports("llama3.2")).isFalse();
        assertThat(adapter.supports("mistral")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    // ── Request translation ────────────────────────────────────────────────────

    @Test
    void translateRequest_buildsCorrectOllamaUrl() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("llama3.2")
            .messages(List.of(Message.user("Hello Ollama!")))
            .build();

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, localEndpoint("llama3.2"));

        assertThat(httpRequest.method()).isEqualTo("POST");
        assertThat(httpRequest.url()).isEqualTo("http://localhost:11434/api/chat");
    }

    @Test
    void translateRequest_noAuthHeader() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("llama3.2")
            .messages(List.of(Message.user("Hello")))
            .build();

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, localEndpoint("llama3.2"));

        assertThat(httpRequest.headers()).doesNotContainKey("Authorization");
        assertThat(httpRequest.headers()).doesNotContainKey("api-key");
        assertThat(httpRequest.headers()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void translateRequest_includesModel() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("llama3.2")
            .messages(List.of(Message.user("Test")))
            .build();

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, localEndpoint("llama3.2"));

        assertThat(httpRequest.body()).contains("\"model\":\"llama3.2\"");
    }

    @Test
    void translateRequest_passesMessages() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("llama3.2")
            .messages(List.of(
                Message.system("You are helpful"),
                Message.user("What is 2+2?")
            ))
            .build();

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, localEndpoint("llama3.2"));

        assertThat(httpRequest.body()).contains("\"role\":\"system\"");
        assertThat(httpRequest.body()).contains("\"role\":\"user\"");
        assertThat(httpRequest.body()).contains("What is 2+2?");
    }

    @Test
    void translateRequest_includesTemperature() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("llama3.2")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.8)
            .build();

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, localEndpoint("llama3.2"));

        assertThat(httpRequest.body()).contains("\"temperature\":0.8");
    }

    @Test
    void translateRequest_usesEndpointModelName() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("llama3.2") // request model
            .messages(List.of(Message.user("Hello")))
            .build();

        var endpoint = new ResolvedEndpoint(
            "ollama",
            "llama3.2:13b", // endpoint model is more specific
            "http://localhost:11434",
            new ProviderCredentials.None()
        );

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, endpoint);

        assertThat(httpRequest.body()).contains("\"model\":\"llama3.2:13b\"");
    }

    @Test
    void translateRequest_maxTokensMapsToNumPredict() throws Exception {
        var request = ChatCompletionRequest.builder()
            .model("llama3.2")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(512)
            .build();

        ProviderHttpRequest httpRequest = adapter.translateRequest(request, localEndpoint("llama3.2"));

        assertThat(httpRequest.body()).contains("\"num_predict\":512");
    }

    // ── Response translation ───────────────────────────────────────────────────

    @Test
    void translateResponse_parsesOllamaFormat() throws Exception {
        String ollamaResponse = """
            {
              "model": "llama3.2",
              "message": {
                "role": "assistant",
                "content": "4"
              },
              "done": true,
              "prompt_eval_count": 15,
              "eval_count": 1
            }
            """;

        var httpResponse = new ProviderHttpResponse(200, Map.of(), ollamaResponse);

        ChatCompletionResponse response = adapter.translateResponse(httpResponse);

        assertThat(response.model()).isEqualTo("llama3.2");
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().get(0).message().content()).isEqualTo("4");
        assertThat(response.choices().get(0).message().role()).isEqualTo("assistant");
        assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(15);
        assertThat(response.usage().completionTokens()).isEqualTo(1);
        assertThat(response.usage().totalTokens()).isEqualTo(16);
    }

    @Test
    void translateResponse_throwsOnErrorStatus() {
        var errorResponse = new ProviderHttpResponse(500, Map.of(), "Internal error");

        assertThatThrownBy(() -> adapter.translateResponse(errorResponse))
            .isInstanceOf(io.legate.core.exception.UpstreamException.class);
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Test
    void translateStreamChunk_parsesNdjsonChunk() throws Exception {
        String ndjsonChunk = """
            {
              "model": "llama3.2",
              "message": {
                "role": "assistant",
                "content": "Hello"
              },
              "done": false
            }
            """;

        StreamContext context = new StreamContext();
        ChatCompletionChunk chunk = adapter.translateStreamChunk(ndjsonChunk, context);

        assertThat(chunk).isNotNull();
        assertThat(chunk.getDeltaContent()).isEqualTo("Hello");
    }

    @Test
    void translateStreamChunk_returnNullForDoneChunk() throws Exception {
        String doneChunk = """
            {
              "model": "llama3.2",
              "done": true,
              "total_duration": 123456,
              "eval_count": 50
            }
            """;

        StreamContext context = new StreamContext();
        ChatCompletionChunk chunk = adapter.translateStreamChunk(doneChunk, context);

        assertThat(chunk).isNull();
    }

    @Test
    void translateStreamChunk_accumulatesContent() throws Exception {
        StreamContext context = new StreamContext();

        String chunk1 = "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"done\":false}";
        String chunk2 = "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\" world\"},\"done\":false}";

        adapter.translateStreamChunk(chunk1, context);
        adapter.translateStreamChunk(chunk2, context);

        assertThat(context.getAccumulatedContent()).isEqualTo("Hello world");
    }

    @Test
    void translateStreamChunk_nullForEmpty() throws Exception {
        StreamContext context = new StreamContext();

        assertThat(adapter.translateStreamChunk(null, context)).isNull();
        assertThat(adapter.translateStreamChunk("", context)).isNull();
        assertThat(adapter.translateStreamChunk("   ", context)).isNull();
    }

    // ── Stream terminator ──────────────────────────────────────────────────────

    @Test
    void isStreamTerminator_detectsDoneTrue() {
        assertThat(adapter.isStreamTerminator("{\"done\":true}")).isTrue();
        assertThat(adapter.isStreamTerminator("{\"model\":\"llama3.2\",\"done\":true,\"total_duration\":1000}")).isTrue();
    }

    @Test
    void isStreamTerminator_falseForDoneFalse() {
        assertThat(adapter.isStreamTerminator("{\"done\":false}")).isFalse();
    }

    @Test
    void isStreamTerminator_falseForContentChunks() {
        assertThat(adapter.isStreamTerminator("{\"model\":\"llama3.2\",\"message\":{\"content\":\"Hello\"},\"done\":false}")).isFalse();
    }

    @Test
    void isStreamTerminator_falseForNullOrEmpty() {
        assertThat(adapter.isStreamTerminator(null)).isFalse();
        assertThat(adapter.isStreamTerminator("")).isFalse();
        assertThat(adapter.isStreamTerminator("invalid json")).isFalse();
    }
}
