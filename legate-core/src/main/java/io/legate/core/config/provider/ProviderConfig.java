package io.legate.core.config.provider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Configuration for one upstream LLM provider endpoint.
 *
 * <p>Multiple entries of the same {@link ProviderType} are supported — for example,
 * two OpenAI accounts or a primary and a backup Anthropic endpoint.</p>
 *
 * <h3>Minimal example (OpenAI)</h3>
 * <pre>{@code
 * legate:
 *   providers:
 *     - name: openai-prod
 *       type: openai
 *       base-url: https://api.openai.com
 *       api-key-env-var: OPENAI_API_KEY
 *       models:
 *         - gpt-4o
 *         - gpt-4o-mini
 * }</pre>
 *
 * <h3>Azure OpenAI example</h3>
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
 *
 * <h3>AWS Bedrock example</h3>
 * <pre>{@code
 * legate:
 *   providers:
 *     - name: bedrock-us-east
 *       type: bedrock
 *       models:
 *         - anthropic.claude-3-5-sonnet-20241022-v2:0
 *       properties:
 *         region: us-east-1
 *         aws-access-key-id-env-var: AWS_ACCESS_KEY_ID
 *         aws-secret-access-key-env-var: AWS_SECRET_ACCESS_KEY
 * }</pre>
 *
 * <h3>Ollama (local, no auth) example</h3>
 * <pre>{@code
 * legate:
 *   providers:
 *     - name: ollama-local
 *       type: ollama
 *       base-url: http://localhost:11434
 *       models:
 *         - llama3.2
 *         - qwen2.5-coder
 * }</pre>
 */
public record ProviderConfig(

    /**
     * Unique logical name for this provider entry.
     * Referenced by {@link io.legate.core.config.routing.ChainEndpointConfig#provider()}.
     * Must be unique across all provider entries.
     */
    String name,

    /**
     * Provider type — determines which {@code ProviderAdapter} is loaded and
     * which URL / authentication conventions are applied.
     */
    ProviderType type,

    /**
     * Base URL of the provider API (no trailing slash).
     * <ul>
     *   <li>OpenAI: {@code https://api.openai.com}</li>
     *   <li>Anthropic: {@code https://api.anthropic.com}</li>
     *   <li>Azure: {@code https://{resource}.openai.azure.com}</li>
     *   <li>Ollama: {@code http://localhost:11434}</li>
     *   <li>Bedrock / VertexAI: computed from properties; may be left blank.</li>
     * </ul>
     */
    String baseUrl,

    /**
     * Name of the environment variable that holds the primary API key or bearer token.
     * Legate reads this at startup and on hot-reload.
     * Not required for {@link ProviderType#OLLAMA} (no auth) or when using multi-credential
     * providers configured entirely via {@link #properties()}.
     */
    String apiKeyEnvVar,

    /**
     * Model names served by this provider.
     * Use exact model IDs as accepted by the provider API (e.g., {@code gpt-4o},
     * not an alias). The routing engine matches incoming request models against this list.
     */
    List<String> models,

    /**
     * Relative weight for load-balancing when multiple providers are eligible for
     * the same request. Higher values receive proportionally more traffic.
     * Default: {@code 100}.
     */
    int weight,

    /**
     * Health-check settings specific to this provider.
     * When {@code null}, the global health-check defaults are applied.
     */
    HealthCheckConfig healthCheck,

    /**
     * Maximum time to wait when establishing a TCP connection to this provider.
     * Default: {@code 10s}.
     */
    Duration connectTimeout,

    /**
     * Maximum time to wait for the first response byte (or the full response for
     * non-streaming requests). Default: {@code 60s}.
     */
    Duration readTimeout,

    /**
     * Provider-specific properties passed directly to the {@code ProviderAdapter}.
     * Consult the Javadoc on {@link ProviderType} for the keys each type accepts.
     *
     * <p>Common keys:</p>
     * <ul>
     *   <li>Azure: {@code deployment-id}, {@code api-version}</li>
     *   <li>Bedrock: {@code region}, {@code aws-access-key-id-env-var}, {@code aws-secret-access-key-env-var}</li>
     *   <li>VertexAI: {@code project-id}, {@code location}, {@code service-account-key-env-var}</li>
     * </ul>
     */
    Map<String, String> properties

) {
    public ProviderConfig {
        if (models == null) {
            models = List.of();
        }
        if (properties == null) {
            properties = Map.of();
        }
        if (weight <= 0) {
            weight = 100;
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(60);
        }
    }

    /**
     * Resolves the primary API key from the configured environment variable.
     *
     * @return the API key value, or {@code null} if {@code apiKeyEnvVar} is not set
     *         or the environment variable is absent
     */
    public String resolveApiKey() {
        if (apiKeyEnvVar == null || apiKeyEnvVar.isBlank()) {
            return null;
        }
        return System.getenv(apiKeyEnvVar);
    }

    /**
     * Resolves an environment variable name stored under a properties key and
     * returns its value from the process environment.
     *
     * <p>Example — resolving the Bedrock access key:</p>
     * <pre>{@code
     * String accessKeyId = provider.resolvePropertyEnvVar("aws-access-key-id-env-var");
     * }</pre>
     *
     * @param propertyKey the key in {@link #properties()} whose value is an env-var name
     * @return the environment variable value, or {@code null} if not configured or unset
     */
    public String resolvePropertyEnvVar(String propertyKey) {
        String envVarName = properties.get(propertyKey);
        if (envVarName == null || envVarName.isBlank()) {
            return null;
        }
        return System.getenv(envVarName);
    }
}
