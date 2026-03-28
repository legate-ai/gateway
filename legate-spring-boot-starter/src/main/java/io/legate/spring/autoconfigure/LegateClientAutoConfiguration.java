package io.legate.spring.autoconfigure;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.cache.ResponseCache;
import io.legate.core.event.EventBus;
import io.legate.core.guard.GuardPipeline;
import io.legate.core.provider.ProviderAdapterRegistry;
import io.legate.core.routing.RoutingEngine;
import io.legate.spring.client.LegateClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration for the embedded {@link LegateClient}.
 *
 * <p>Provides an in-process {@link LegateClient} bean that runs the full Legate
 * pipeline without an HTTP hop. Useful for embedding Legate directly inside a
 * Spring Boot application.</p>
 *
 * <p>All dependencies are resolved from the application context, so the same
 * guards, cache, routing, and telemetry configuration applies as in standalone
 * server mode.</p>
 */
@AutoConfiguration(after = {LegateAutoConfiguration.class, LegateGuardAutoConfiguration.class})
public class LegateClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LegateClient.class)
    public LegateClient legateClient(
        RoutingEngine routingEngine,
        ProviderAdapterRegistry adapterRegistry,
        GuardPipeline guardPipeline,
        ResponseCache responseCache,
        EventBus eventBus,
        ObjectMapper objectMapper,
        WebClient.Builder webClientBuilder
    ) {
        return new LegateClient(
            routingEngine,
            adapterRegistry,
            guardPipeline,
            responseCache,
            eventBus,
            objectMapper,
            webClientBuilder
        );
    }
}
