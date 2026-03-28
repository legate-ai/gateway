package io.legate.provider.anthropic;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.model.*;
import io.legate.core.provider.ProviderAdapter;
import io.legate.core.provider.ProviderHttpRequest;
import io.legate.core.provider.ProviderHttpResponse;
import io.legate.core.provider.StreamContext;
import io.legate.core.routing.ProviderCredentials;
import io.legate.core.routing.ResolvedEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provider adapter for Anthropic Claude API.
 * Handles translation between OpenAI format and Anthropic Messages API format.
 */
public class AnthropicProviderAdapter implements ProviderAdapter {
    private static final Logger log = LoggerFactory.getLogger(AnthropicProviderAdapter.class);
    private static final String PROVIDER_NAME = "anthropic";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final ObjectMapper objectMapper;

    public AnthropicProviderAdapter() {
        this.objectMapper = new ObjectMapper();
    }

    public AnthropicProviderAdapter(ObjectMapper objectMapper) {
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
        return modelName.startsWith("claude-");
    }

    @Override
    public ProviderHttpRequest translateRequest(
        ChatCompletionRequest request,
        ResolvedEndpoint endpoint
    ) throws Exception {
        // Extract system messages
        String systemPrompt = extractSystemPrompt(request.messages());

        // Filter out system messages from the messages list
        List<AnthropicMessage> anthropicMessages = request.messages().stream()
            .filter(msg -> !"system".equals(msg.role()))
            .map(this::translateMessage)
            .collect(Collectors.toList());

        // Build Anthropic request
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_TOKENS;
        if (request.maxCompletionTokens() != null) {
            maxTokens = request.maxCompletionTokens();
        }

        var anthropicRequest = new AnthropicRequest(
            request.model(),
            anthropicMessages,
            systemPrompt,
            maxTokens,
            request.temperature(),
            request.topP(),
            null, // topK not in OpenAI format
            convertStopSequences(request.stop()),
            request.stream(),
            null, // metadata
            null  // tools - TODO: implement tool translation
        );

        // Build headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        addAuthenticationHeader(headers, endpoint.credentials());

        // Build URL
        String url = buildUrl(endpoint.baseUrl());

        // Serialize request
        String body = objectMapper.writeValueAsString(anthropicRequest);

        log.debug("Translating request for Anthropic: model={}, system_length={}, messages={}",
            request.model(), systemPrompt != null ? systemPrompt.length() : 0, anthropicMessages.size());

        return ProviderHttpRequest.post(url, headers, body);
    }

    @Override
    public ChatCompletionResponse translateResponse(ProviderHttpResponse response) throws Exception {
        if (!response.isSuccess()) {
            log.error("Anthropic returned error status: {} - {}",
                response.statusCode(), response.body());
            throw new RuntimeException("Anthropic API error: " + response.statusCode());
        }

        AnthropicResponse anthropicResponse = objectMapper.readValue(
            response.body(),
            AnthropicResponse.class
        );

        // Translate to OpenAI format
        String content = anthropicResponse.getTextContent();
        Message assistantMessage = Message.assistant(content);

        Choice choice = new Choice(
            0,
            assistantMessage,
            null,
            mapStopReason(anthropicResponse.stopReason()),
            null
        );

        Usage usage = null;
        if (anthropicResponse.usage() != null) {
            usage = new Usage(
                anthropicResponse.usage().inputTokens(),
                anthropicResponse.usage().outputTokens(),
                (anthropicResponse.usage().inputTokens() != null && anthropicResponse.usage().outputTokens() != null)
                    ? anthropicResponse.usage().inputTokens() + anthropicResponse.usage().outputTokens()
                    : null
            );
        }

        return new ChatCompletionResponse(
            anthropicResponse.id(),
            System.currentTimeMillis() / 1000,
            anthropicResponse.model(),
            List.of(choice),
            usage
        );
    }

    @Override
    public ChatCompletionChunk translateStreamChunk(
        String eventData,
        StreamContext context
    ) throws Exception {
        if (eventData == null || eventData.isBlank()) {
            return null;
        }

        AnthropicStreamEvent event = objectMapper.readValue(eventData, AnthropicStreamEvent.class);

        // Handle different event types
        return switch (event.type()) {
            case "message_start" -> handleMessageStart(event, context);
            case "content_block_start" -> null; // Skip, no content yet
            case "content_block_delta" -> handleContentDelta(event, context);
            case "content_block_stop" -> null; // Skip, no new content
            case "message_delta" -> handleMessageDelta(event, context);
            case "message_stop" -> handleMessageStop(event, context);
            default -> {
                log.debug("Unknown Anthropic event type: {}", event.type());
                yield null;
            }
        };
    }

    @Override
    public boolean isStreamTerminator(String eventData) {
        if (eventData == null) {
            return false;
        }
        try {
            AnthropicStreamEvent event = objectMapper.readValue(eventData, AnthropicStreamEvent.class);
            return event.isMessageStop();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Usage extractStreamUsage(StreamContext context) {
        return context.getUsage();
    }

    /**
     * Extracts system prompt from messages (all system role messages concatenated).
     */
    private String extractSystemPrompt(List<Message> messages) {
        if (messages == null) {
            return null;
        }

        String systemPrompt = messages.stream()
            .filter(msg -> "system".equals(msg.role()))
            .map(Message::content)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n\n"));

        return systemPrompt.isEmpty() ? null : systemPrompt;
    }

    /**
     * Translates an OpenAI message to Anthropic format.
     */
    private AnthropicMessage translateMessage(Message message) {
        // Simple text message
        return AnthropicMessage.text(message.role(), message.content());
    }

    /**
     * Converts stop parameter (String or List<String>) to List<String>.
     */
    private List<String> convertStopSequences(Object stop) {
        if (stop == null) {
            return null;
        }
        if (stop instanceof String str) {
            return List.of(str);
        }
        if (stop instanceof List<?> list) {
            return list.stream()
                .filter(item -> item instanceof String)
                .map(item -> (String) item)
                .collect(Collectors.toList());
        }
        return null;
    }

    /**
     * Maps Anthropic stop_reason to OpenAI finish_reason.
     */
    private String mapStopReason(String anthropicStopReason) {
        if (anthropicStopReason == null) {
            return null;
        }
        return switch (anthropicStopReason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            default -> anthropicStopReason;
        };
    }

    /**
     * Builds the full URL for the messages endpoint.
     */
    private String buildUrl(String baseUrl) {
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        if (!url.contains("/messages")) {
            url = url + "/v1/messages";
        }

        return url;
    }

    /**
     * Adds authentication header based on credential type.
     */
    private void addAuthenticationHeader(Map<String, String> headers, ProviderCredentials credentials) {
        switch (credentials) {
            case ProviderCredentials.BearerToken(String token) ->
                headers.put("x-api-key", token);
            case ProviderCredentials.ApiKeyHeader(String headerName, String apiKey) ->
                headers.put(headerName, apiKey);
            case ProviderCredentials.None none -> {
                // No authentication
            }
            default ->
                log.warn("Unsupported credential type for Anthropic: {}", credentials.getClass().getName());
        }
    }

    // Streaming event handlers

    private ChatCompletionChunk handleMessageStart(AnthropicStreamEvent event, StreamContext context) {
        // Extract initial metadata from message_start event
        return null; // No content to emit yet
    }

    private ChatCompletionChunk handleContentDelta(AnthropicStreamEvent event, StreamContext context) {
        if (event.delta() == null || event.delta().text() == null) {
            return null;
        }

        String deltaText = event.delta().text();

        // Create OpenAI-style chunk
        Message delta = new Message("assistant", deltaText, null, null, null);
        Choice choice = new Choice(
            event.index() != null ? event.index() : 0,
            null,
            delta,
            null,
            null
        );

        ChatCompletionChunk chunk = new ChatCompletionChunk(
            "anthropic-stream",
            System.currentTimeMillis() / 1000,
            "claude",
            List.of(choice)
        );

        context.addChunk(chunk);
        return chunk;
    }

    private ChatCompletionChunk handleMessageDelta(AnthropicStreamEvent event, StreamContext context) {
        // Extract usage from message_delta event
        if (event.usage() != null) {
            Usage usage = new Usage(
                event.usage().inputTokens(),
                event.usage().outputTokens(),
                (event.usage().inputTokens() != null && event.usage().outputTokens() != null)
                    ? event.usage().inputTokens() + event.usage().outputTokens()
                    : null
            );
            context.setUsage(usage);
        }

        // Extract stop_reason if present
        if (event.delta() != null && event.delta().stopReason() != null) {
            String mappedStopReason = mapStopReason(event.delta().stopReason());

            Message delta = new Message("assistant", null, null, null, null);
            Choice choice = new Choice(
                0,
                null,
                delta,
                mappedStopReason,
                null
            );

            ChatCompletionChunk chunk = new ChatCompletionChunk(
                "anthropic-stream",
                "chat.completion.chunk",
                System.currentTimeMillis() / 1000,
                "claude",
                List.of(choice),
                context.getUsage(),
                null
            );

            context.addChunk(chunk);
            return chunk;
        }

        return null;
    }

    private ChatCompletionChunk handleMessageStop(AnthropicStreamEvent event, StreamContext context) {
        // Final chunk - already handled in message_delta
        return null;
    }
}
