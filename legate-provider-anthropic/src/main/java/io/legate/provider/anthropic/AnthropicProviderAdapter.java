package io.legate.provider.anthropic;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.exception.UpstreamException;
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
    private static final String ANTHROPIC_BETA_CACHE = "prompt-caching-2024-07-31";
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
        // Extract system messages, preserving cache_control hints from extra map
        Object system = buildSystemParam(request.messages(), request.extra());

        // Filter out system messages; translate each message, preserving cache_control
        List<AnthropicMessage> anthropicMessages = request.messages().stream()
            .filter(msg -> !"system".equals(msg.role()))
            .map(this::translateMessage)
            .collect(Collectors.toList());

        int maxTokens = request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_TOKENS;
        if (request.maxCompletionTokens() != null) {
            maxTokens = request.maxCompletionTokens();
        }

        var anthropicRequest = new AnthropicRequest(
            request.model(),
            anthropicMessages,
            system,
            maxTokens,
            request.temperature(),
            request.topP(),
            null,
            convertStopSequences(request.stop()),
            request.stream(),
            null,
            null
        );

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        addAuthenticationHeader(headers, endpoint.credentials());

        // Enable prompt-caching beta header when any cache_control blocks are present
        if (hasCacheControl(system, anthropicMessages)) {
            headers.put("anthropic-beta", ANTHROPIC_BETA_CACHE);
        }

        // Build URL
        String url = buildUrl(endpoint.baseUrl());

        // Serialize request
        String body = objectMapper.writeValueAsString(anthropicRequest);

        log.debug("Translating request for Anthropic: model={}, has_system={}, messages={}",
            request.model(), system != null, anthropicMessages.size());

        return ProviderHttpRequest.post(url, headers, body);
    }

    @Override
    public ChatCompletionResponse translateResponse(ProviderHttpResponse response) throws Exception {
        if (!response.isSuccess()) {
            log.error("Anthropic returned error status: {} - {}",
                response.statusCode(), response.body());
            throw new UpstreamException(PROVIDER_NAME, response.statusCode(), response.body());
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
     * Builds the system parameter. When the extra map contains {@code "cache_system": true},
     * wraps the system prompt in a {@link AnthropicMessage.SystemBlock} list with
     * {@code cache_control: {type: ephemeral}} so the prefix is eligible for caching.
     */
    @SuppressWarnings("unchecked")
    private Object buildSystemParam(List<Message> messages, java.util.Map<String, Object> extra) {
        if (messages == null) {
            return null;
        }
        String systemText = messages.stream()
            .filter(msg -> "system".equals(msg.role()))
            .map(Message::content)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n\n"));

        if (systemText.isEmpty()) {
            return null;
        }
        // If the caller signals caching intent via extra, emit a block list
        boolean cacheSystem = extra != null && Boolean.TRUE.equals(extra.get("cache_system"));
        if (cacheSystem) {
            return List.of(AnthropicMessage.SystemBlock.cachedText(systemText));
        }
        return systemText;
    }

    /**
     * Returns true if any system block or message content block carries a cache_control directive.
     */
    @SuppressWarnings("unchecked")
    private boolean hasCacheControl(Object system, List<AnthropicMessage> messages) {
        if (system instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof AnthropicMessage.SystemBlock sb && sb.hasCacheControl()) {
                    return true;
                }
            }
        }
        for (AnthropicMessage msg : messages) {
            if (msg.content() instanceof List<?> contentBlocks) {
                for (Object b : contentBlocks) {
                    if (b instanceof AnthropicMessage.ContentBlock cb && cb.cacheControl() != null) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    @Override
    public boolean supportsNativeMessages() {
        return true;
    }

    @Override
    public ProviderHttpRequest translateNativeMessagesRequest(
        String rawBody, ResolvedEndpoint endpoint
    ) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        addAuthenticationHeader(headers, endpoint.credentials());
        if (rawBody != null && rawBody.contains("cache_control")) {
            headers.put("anthropic-beta", ANTHROPIC_BETA_CACHE);
        }
        return ProviderHttpRequest.post(buildUrl(endpoint.baseUrl()), headers, rawBody);
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
