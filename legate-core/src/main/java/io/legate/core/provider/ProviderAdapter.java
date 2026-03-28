package io.legate.core.provider;

import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.Usage;
import io.legate.core.routing.ResolvedEndpoint;

/**
 * Provider adapter interface for translating between the unified Legate format
 * and provider-specific formats.
 * <p>
 * Each LLM provider (OpenAI, Anthropic, Azure, Bedrock, etc.) implements this
 * interface to handle request/response translation, authentication, and streaming.
 */
public interface ProviderAdapter {

    /**
     * Returns the unique name of this provider (e.g., "openai", "anthropic").
     */
    String getProviderName();

    /**
     * Returns true if this adapter supports the given model name.
     * Used for automatic provider selection when model name alone is specified.
     *
     * @param modelName the model name to check
     * @return true if this adapter can handle the model
     */
    boolean supports(String modelName);

    /**
     * Translates a unified ChatCompletionRequest into a provider-specific HTTP request.
     *
     * @param request  the unified request
     * @param endpoint the resolved endpoint with credentials and URLs
     * @return the provider-specific HTTP request
     * @throws Exception if translation fails
     */
    ProviderHttpRequest translateRequest(
            ChatCompletionRequest request,
            ResolvedEndpoint endpoint
    ) throws Exception;

    /**
     * Translates a provider-specific HTTP response into a unified ChatCompletionResponse.
     *
     * @param response the provider HTTP response
     * @return the unified response
     * @throws Exception if translation fails
     */
    ChatCompletionResponse translateResponse(
            ProviderHttpResponse response
    ) throws Exception;

    /**
     * Translates a single SSE event line into a unified ChatCompletionChunk.
     * Called for each data line received from the streaming endpoint.
     *
     * @param eventData the raw SSE event data (after "data: " prefix)
     * @param context   the streaming context for accumulating state
     * @return the translated chunk, or null if the line should be skipped
     * @throws Exception if translation fails
     */
    ChatCompletionChunk translateStreamChunk(
            String eventData,
            StreamContext context
    ) throws Exception;

    /**
     * Returns true if the given event data indicates the end of the stream.
     * For OpenAI, this is "data: [DONE]".
     *
     * @param eventData the raw SSE event data
     * @return true if this is the stream terminator
     */
    boolean isStreamTerminator(String eventData);

    /**
     * Extracts usage information from the completed stream context.
     * Called after the stream finishes to get token counts.
     *
     * @param context the completed stream context
     * @return the usage information, or null if not available
     */
    default Usage extractStreamUsage(StreamContext context) {
        return context.getUsage();
    }
}
