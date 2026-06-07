package io.legate.provider.openai;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
import io.legate.core.exception.UpstreamException;
import io.legate.core.model.EmbeddingRequest;
import io.legate.core.model.EmbeddingResponse;
import io.legate.core.provider.ProviderAdapter;
import io.legate.core.provider.ProviderHttpRequest;
import io.legate.core.provider.ProviderHttpResponse;
import io.legate.core.provider.StreamContext;
import io.legate.core.routing.ProviderCredentials;
import io.legate.core.routing.ResolvedEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Provider adapter for OpenAI and OpenAI-compatible endpoints.
 * This is the "native" format - minimal translation required.
 */
public class OpenAiProviderAdapter implements ProviderAdapter {
    private static final Logger log = LoggerFactory.getLogger(OpenAiProviderAdapter.class);
    private static final String PROVIDER_NAME = "openai";
    private static final String STREAM_TERMINATOR = "[DONE]";

    private final ObjectMapper objectMapper;

    public OpenAiProviderAdapter() {
        this.objectMapper = new ObjectMapper();
    }

    public OpenAiProviderAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }

        // OpenAI models
        if (modelName.startsWith("gpt-") ||
            modelName.startsWith("o1-") ||
            modelName.startsWith("o3-") ||
            modelName.startsWith("dall-e-") ||
            modelName.startsWith("text-embedding-") ||
            modelName.startsWith("tts-") ||
            modelName.startsWith("whisper-")) {
            return true;
        }

        return false;
    }

    @Override
    public ProviderHttpRequest translateRequest(
        ChatCompletionRequest request,
        ResolvedEndpoint endpoint
    ) throws Exception {
        // Build headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        // Add authentication header
        addAuthenticationHeader(headers, endpoint.credentials());

        // Construct URL
        String url = buildUrl(endpoint.baseUrl());

        // Serialize request body (pass through with minimal changes)
        String body = objectMapper.writeValueAsString(request);

        log.debug("Translating request for OpenAI: model={}, stream={}",
            request.model(), request.stream());

        return ProviderHttpRequest.post(url, headers, body);
    }

    @Override
    public ChatCompletionResponse translateResponse(ProviderHttpResponse response) throws Exception {
        if (!response.isSuccess()) {
            log.error("OpenAI returned error status: {} - {}",
                response.statusCode(), response.body());
            throw new UpstreamException(PROVIDER_NAME, response.statusCode(), response.body());
        }

        // OpenAI response is already in the unified format
        return objectMapper.readValue(response.body(), ChatCompletionResponse.class);
    }

    @Override
    public ChatCompletionChunk translateStreamChunk(
        String eventData,
        StreamContext context
    ) throws Exception {
        // Skip empty lines or terminator
        if (eventData == null || eventData.isBlank() || eventData.equals(STREAM_TERMINATOR)) {
            return null;
        }

        // Parse the chunk (OpenAI format is already the unified format)
        ChatCompletionChunk chunk = objectMapper.readValue(eventData, ChatCompletionChunk.class);
        context.addChunk(chunk);

        return chunk;
    }

    @Override
    public boolean isStreamTerminator(String eventData) {
        return eventData != null && eventData.trim().equals(STREAM_TERMINATOR);
    }

    @Override
    public boolean supportsEmbeddings() {
        return true;
    }

    @Override
    public ProviderHttpRequest translateEmbeddingRequest(
        EmbeddingRequest request,
        ResolvedEndpoint endpoint
    ) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        addAuthenticationHeader(headers, endpoint.credentials());
        String url = buildEmbeddingUrl(endpoint.baseUrl());
        String body = objectMapper.writeValueAsString(request);
        return ProviderHttpRequest.post(url, headers, body);
    }

    @Override
    public EmbeddingResponse translateEmbeddingResponse(ProviderHttpResponse response) throws Exception {
        if (!response.isSuccess()) {
            throw new UpstreamException(PROVIDER_NAME, response.statusCode(), response.body());
        }
        return objectMapper.readValue(response.body(), EmbeddingResponse.class);
    }

    private String buildEmbeddingUrl(String baseUrl) {
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!url.contains("/embeddings")) {
            url = url + "/v1/embeddings";
        }
        return url;
    }

    /**
     * Builds the full URL for the chat completions endpoint.
     */
    private String buildUrl(String baseUrl) {
        // Remove trailing slash if present
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        // Add the path if not already present
        if (!url.contains("/chat/completions")) {
            url = url + "/v1/chat/completions";
        }

        return url;
    }

    /**
     * Adds authentication header based on credential type.
     */
    private void addAuthenticationHeader(Map<String, String> headers, ProviderCredentials credentials) {
        switch (credentials) {
            case ProviderCredentials.BearerToken(String token) ->
                headers.put("Authorization", "Bearer " + token);
            case ProviderCredentials.ApiKeyHeader(String headerName, String apiKey) ->
                headers.put(headerName, apiKey);
            case ProviderCredentials.None none -> {
                // No authentication needed
            }
            default ->
                log.warn("Unsupported credential type for OpenAI: {}", credentials.getClass().getName());
        }
    }
}
