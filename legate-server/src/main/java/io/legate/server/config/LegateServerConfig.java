package io.legate.server.config;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.event.AsyncEventBus;
import io.legate.core.event.CompletionEvent;
import io.legate.core.event.EventBus;
import io.legate.core.event.builtin.RequestCompletionLogger;
import io.legate.core.provider.ProviderAdapterRegistry;
import io.legate.provider.anthropic.AnthropicProviderAdapter;
import io.legate.provider.azure.AzureOpenAiProviderAdapter;
import io.legate.provider.ollama.OllamaProviderAdapter;
import io.legate.provider.openai.OpenAiProviderAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Server-specific Spring configuration for the standalone Legate gateway.
 *
 * <p>Beans defined here override the auto-configured defaults from
 * {@code LegateAutoConfiguration} in {@code legate-spring-boot-starter} because
 * regular {@code @Configuration} classes are processed before auto-configurations.
 * Auto-configured beans guarded with {@code @ConditionalOnMissingBean} will be
 * skipped once these definitions are visible.</p>
 *
 * <p>Server-specific additions:</p>
 * <ul>
 *   <li>{@link WebClient.Builder} — required by {@link io.legate.server.upstream.UpstreamClient}</li>
 *   <li>{@link EventBus} — configured with the {@link ConsoleLoggerSubscriber} for Phase 1 logging</li>
 * </ul>
 */
@Configuration
public class LegateServerConfig {

    /**
     * {@link ObjectMapper} used for JSON serialization throughout the server.
     *
     * <p>Jackson 3.x registers modules via the service-loader mechanism at build time.
     * {@code findAndRegisterModules()} has been removed from the Jackson 3.x API and
     * must not be called.</p>
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * {@link EventBus} wired with the structured-logging subscriber.
     *
     * <p>The subscriber enriches the SLF4J MDC with per-request fields and emits a
     * single log line per completed request. When Spring Boot ECS structured logging
     * is active ({@code logging.structured.format.console: ecs}), the MDC fields are
     * automatically included in the JSON output under the {@code labels} namespace.</p>
     */
    @Bean
    public EventBus eventBus() {
        AsyncEventBus bus = new AsyncEventBus();
        bus.subscribe(CompletionEvent.class, new RequestCompletionLogger());
        return bus;
    }

    /**
     * {@link ProviderAdapterRegistry} with OpenAI and Anthropic adapters pre-registered.
     */
    @Bean
    public ProviderAdapterRegistry providerAdapterRegistry(ObjectMapper objectMapper) {
        ProviderAdapterRegistry registry = new ProviderAdapterRegistry();
        registry.register(new OpenAiProviderAdapter(objectMapper));
        registry.register(new AnthropicProviderAdapter(objectMapper));
        registry.register(new AzureOpenAiProviderAdapter(objectMapper));
        registry.register(new OllamaProviderAdapter(objectMapper));
        return registry;
    }

    /**
     * {@link WebClient.Builder} used by {@link io.legate.server.upstream.UpstreamClient}.
     * Pre-configured with a 10 MB max-in-memory buffer.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(10 * 1024 * 1024));
    }
}
