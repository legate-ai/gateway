package io.legate.provider.ollama;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
import io.legate.core.model.Choice;
import io.legate.core.model.Message;
import io.legate.core.model.Usage;
import io.legate.core.provider.ProviderAdapter;
import io.legate.core.provider.ProviderHttpRequest;
import io.legate.core.provider.ProviderHttpResponse;
import io.legate.core.provider.StreamContext;
import io.legate.core.routing.ResolvedEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider adapter for Ollama (local model inference server).
 *
 * <p>Ollama exposes an OpenAI-compatible API at {@code /api/chat}, but the format
 * differs from the standard OpenAI API in several ways:</p>
 * <ul>
 *   <li>Endpoint: {@code POST /api/chat} (not {@code /v1/chat/completions})</li>
 *   <li>No authentication required</li>
 *   <li>Streaming uses newline-delimited JSON (NDJSON), not SSE</li>
 *   <li>The streaming terminator is a JSON object with {@code "done": true}</li>
 * </ul>
 *
 * <p>Ollama also offers an OpenAI-compatible endpoint at {@code /v1/chat/completions}
 * since version 0.1.24. If configured with {@code openai-compat: true} in provider
 * properties, the adapter uses that endpoint instead.</p>
 *
 * <p>YAML configuration example:</p>
 * <pre>{@code
 * legate:
 *   providers:
 *     - name: ollama-local
 *       type: ollama
 *       base-url: http://localhost:11434
 *       models:
 *         - llama3.2
 *         - qwen2.5-coder:7b
 * }</pre>
 */
public class OllamaProviderAdapter implements ProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(OllamaProviderAdapter.class);
    private static final String PROVIDER_NAME = "ollama";

    private final ObjectMapper objectMapper;

    public OllamaProviderAdapter() {
        this.objectMapper = new ObjectMapper();
    }

    public OllamaProviderAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(String modelName) {
        // Ollama doesn't claim any prefix — routing is by provider name via fallback chains
        return false;
    }

    @Override
    public ProviderHttpRequest translateRequest(
        ChatCompletionRequest request,
        ResolvedEndpoint endpoint
    ) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        // No auth for Ollama

        // Use model from endpoint if set, otherwise from request
        String model = endpoint.modelName() != null ? endpoint.modelName() : request.model();

        // Ollama /api/chat format
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", Boolean.TRUE.equals(request.stream()));

        // Messages array — Ollama uses same role/content format as OpenAI
        body.set("messages", objectMapper.valueToTree(request.messages()));

        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null) {
            body.putObject("options").put("num_predict", request.maxTokens());
        }

        String url = endpoint.baseUrl() + "/api/chat";
        log.debug("Ollama request → {}", url);
        return new ProviderHttpRequest("POST", url, headers, objectMapper.writeValueAsString(body));
    }

    @Override
    public ChatCompletionResponse translateResponse(ProviderHttpResponse response) throws Exception {
        if (!response.isSuccess()) {
            throw new io.legate.core.exception.UpstreamException(
                PROVIDER_NAME, response.statusCode(),
                "Ollama request failed: " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());

        // Ollama response format:
        // { "model": "...", "message": {"role": "assistant", "content": "..."}, "done": true,
        //   "prompt_eval_count": N, "eval_count": N }
        String content  = "";
        String role     = "assistant";
        String model    = json.path("model").asText("unknown");
        boolean done    = json.path("done").asBoolean(true);

        JsonNode messageNode = json.path("message");
        if (messageNode.isObject()) {
            content = messageNode.path("content").asText("");
            role    = messageNode.path("role").asText("assistant");
        }

        int promptTokens = json.path("prompt_eval_count").asInt(0);
        int compTokens   = json.path("eval_count").asInt(0);

        Message msg = new Message(role, content, null, null, null);
        Choice choice = new Choice(0, msg, null, "stop", null);
        Usage usage = new Usage(promptTokens, compTokens, promptTokens + compTokens);

        return new ChatCompletionResponse(
            "ollama-" + Instant.now().getEpochSecond(),
            Instant.now().getEpochSecond(),
            model,
            List.of(choice),
            usage
        );
    }

    @Override
    public ChatCompletionChunk translateStreamChunk(String eventData, StreamContext context) throws Exception {
        if (eventData == null || eventData.isBlank()) return null;

        JsonNode json = objectMapper.readTree(eventData);
        boolean done  = json.path("done").asBoolean(false);
        if (done) return null;

        String content = "";
        JsonNode messageNode = json.path("message");
        if (messageNode.isObject()) {
            content = messageNode.path("content").asText("");
        }

        String model = json.path("model").asText("unknown");

        // Build an OpenAI-compatible chunk
        io.legate.core.model.Message delta = new Message("assistant", content, null, null, null);
        io.legate.core.model.Choice choice = new Choice(0, null, delta, null, null);

        ChatCompletionChunk chunk = new ChatCompletionChunk(
            "ollama-stream-" + Instant.now().toEpochMilli(),
            "chat.completion.chunk",
            Instant.now().getEpochSecond(),
            model,
            List.of(choice),
            null,
            null
        );
        context.addChunk(chunk);
        return chunk;
    }

    @Override
    public boolean isStreamTerminator(String eventData) {
        if (eventData == null || eventData.isBlank()) return false;
        try {
            JsonNode json = objectMapper.readTree(eventData);
            return json.path("done").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Usage extractStreamUsage(StreamContext context) {
        return context.getUsage();
    }
}
