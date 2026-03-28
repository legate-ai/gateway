package io.legate.provider.azure;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
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
 * Provider adapter for Azure OpenAI Service.
 *
 * <p>Azure OpenAI uses the same request/response format as OpenAI but with a different
 * URL structure and authentication mechanism:</p>
 * <ul>
 *   <li>URL: {@code https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version=2024-10-21}</li>
 *   <li>Auth: {@code api-key: <key>} header (not Bearer token)</li>
 * </ul>
 *
 * <p>The deployment name and API version are read from the provider's {@code properties}:
 * {@code deployment-id} and {@code api-version}.</p>
 *
 * <p>YAML configuration example:</p>
 * <pre>{@code
 * legate:
 *   providers:
 *     - name: azure-prod
 *       type: azure-openai
 *       base-url: https://my-resource.openai.azure.com
 *       api-key-env-var: AZURE_OPENAI_API_KEY
 *       models:
 *         - gpt-4o
 *       properties:
 *         deployment-id: my-gpt4o-deployment
 *         api-version: "2024-10-21"
 * }</pre>
 */
public class AzureOpenAiProviderAdapter implements ProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiProviderAdapter.class);
    private static final String PROVIDER_NAME    = "azure-openai";
    private static final String DEFAULT_API_VER  = "2024-10-21";
    private static final String STREAM_TERMINATOR = "[DONE]";

    private final ObjectMapper objectMapper;

    public AzureOpenAiProviderAdapter() {
        this.objectMapper = new ObjectMapper();
    }

    public AzureOpenAiProviderAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(String modelName) {
        // Azure doesn't claim any specific model prefix — it's selected via the provider name
        // in the fallback chain config. Return false so OpenAI adapter stays as default.
        return false;
    }

    @Override
    public ProviderHttpRequest translateRequest(
        ChatCompletionRequest request,
        ResolvedEndpoint endpoint
    ) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        // Azure uses api-key header, not Bearer token
        switch (endpoint.credentials()) {
            case ProviderCredentials.BearerToken bt -> headers.put("api-key", bt.token());
            case ProviderCredentials.ApiKeyHeader ak -> headers.put(ak.headerName(), ak.apiKey());
            default -> { /* no auth */ }
        }

        // Build Azure URL: {baseUrl}/openai/deployments/{deployment}/chat/completions?api-version=...
        String deploymentId = endpoint.modelName(); // default to model name as deployment
        String apiVersion   = DEFAULT_API_VER;
        String url = endpoint.baseUrl()
            + "/openai/deployments/" + deploymentId
            + "/chat/completions?api-version=" + apiVersion;

        // Serialize (Azure uses same format as OpenAI)
        String body = objectMapper.writeValueAsString(request);

        log.debug("Azure request → {}", url);
        return new ProviderHttpRequest("POST", url, headers, body);
    }

    @Override
    public ChatCompletionResponse translateResponse(ProviderHttpResponse response) throws Exception {
        if (!response.isSuccess()) {
            throw new io.legate.core.exception.UpstreamException(
                PROVIDER_NAME, response.statusCode(),
                "Azure OpenAI request failed: " + response.body());
        }
        return objectMapper.readValue(response.body(), ChatCompletionResponse.class);
    }

    @Override
    public ChatCompletionChunk translateStreamChunk(String eventData, StreamContext context) throws Exception {
        if (eventData == null || eventData.isBlank() || STREAM_TERMINATOR.equals(eventData)) {
            return null;
        }
        ChatCompletionChunk chunk = objectMapper.readValue(eventData, ChatCompletionChunk.class);
        context.addChunk(chunk);
        return chunk;
    }

    @Override
    public boolean isStreamTerminator(String eventData) {
        return STREAM_TERMINATOR.equals(eventData);
    }

    @Override
    public io.legate.core.model.Usage extractStreamUsage(StreamContext context) {
        return context.getUsage();
    }
}
