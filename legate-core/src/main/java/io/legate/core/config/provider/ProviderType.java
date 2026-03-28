package io.legate.core.config.provider;

/**
 * Supported upstream LLM provider types.
 *
 * <p>The type determines which {@code ProviderAdapter} is selected at startup and
 * which credential / URL conventions are applied.</p>
 *
 * <p>YAML usage (Spring relaxed binding accepts any case):</p>
 * <pre>{@code
 * legate:
 *   providers:
 *     - name: openai-prod
 *       type: openai          # or OPENAI
 *     - name: claude
 *       type: anthropic
 *     - name: azure-gpt4
 *       type: azure-openai    # or AZURE_OPENAI
 * }</pre>
 */
public enum ProviderType {

    /**
     * OpenAI API and any OpenAI-compatible endpoint (vLLM, LM Studio, Together AI, etc.).
     * Auth: {@code Authorization: Bearer <key>}.
     */
    OPENAI {
        @Override public String adapterName() { return "openai"; }
    },

    /**
     * Anthropic Messages API (Claude models).
     * Auth: {@code x-api-key: <key>}.
     */
    ANTHROPIC {
        @Override public String adapterName() { return "anthropic"; }
    },

    /**
     * Azure OpenAI Service.
     * URL: {@code https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version={version}}.
     * Auth: {@code api-key: <key>}.
     * Required properties: {@code deployment-id}, {@code api-version} (default: {@code 2024-10-21}).
     */
    AZURE_OPENAI {
        @Override public String adapterName() { return "azure-openai"; }
    },

    /**
     * Ollama local model server.
     * URL: {@code http://localhost:11434/api/chat} (default; override via {@code baseUrl}).
     * Auth: none.
     */
    OLLAMA {
        @Override public String adapterName() { return "ollama"; }
    },

    /**
     * AWS Bedrock via the Converse API.
     * URL: {@code https://bedrock-runtime.{region}.amazonaws.com/model/{modelId}/converse}.
     * Auth: AWS SigV4 — requires {@code aws-access-key-id-env-var} and {@code aws-secret-access-key-env-var} in properties.
     * Required properties: {@code region}, {@code aws-access-key-id-env-var}, {@code aws-secret-access-key-env-var}.
     */
    BEDROCK {
        @Override public String adapterName() { return "bedrock"; }
    },

    /**
     * Google Vertex AI (Gemini models).
     * URL: {@code https://{location}-aiplatform.googleapis.com/v1/projects/{project}/...}.
     * Auth: OAuth2 via service account.
     * Required properties: {@code project-id}, {@code location}, {@code service-account-key-env-var}.
     */
    VERTEXAI {
        @Override public String adapterName() { return "vertexai"; }
    };

    /**
     * Returns the canonical name used to register and look up the adapter in
     * {@code ProviderAdapterRegistry}. This must match the value returned by
     * {@code ProviderAdapter.getProviderName()} for the corresponding adapter.
     */
    public abstract String adapterName();
}
