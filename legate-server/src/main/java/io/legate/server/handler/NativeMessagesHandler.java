package io.legate.server.handler;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.config.LegateConfig;
import io.legate.core.context.RequestContext;
import io.legate.core.exception.NoEndpointAvailableException;
import io.legate.core.exception.UpstreamException;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.provider.ProviderAdapter;
import io.legate.core.provider.ProviderAdapterRegistry;
import io.legate.core.routing.CircuitBreakerRegistry;
import io.legate.core.routing.FallbackChain;
import io.legate.core.routing.ResolvedEndpoint;
import io.legate.core.routing.RoutingEngine;
import io.legate.server.filter.RequestIdWebFilter;
import io.legate.server.handler.pipeline.RequestPipeline;
import io.legate.server.upstream.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Handler for {@code POST /v1/messages} — Anthropic native Messages API passthrough.
 *
 * <p>Accepts a raw Anthropic Messages API request body, runs the standard pre-request
 * pipeline (authentication, rate limiting), then forwards the body unchanged to an
 * upstream provider whose adapter reports {@link ProviderAdapter#supportsNativeMessages()}
 * true. Only non-streaming requests are supported; streaming native messages will
 * be added in a future iteration.</p>
 */
@Component
public class NativeMessagesHandler {

    private static final Logger log = LoggerFactory.getLogger(NativeMessagesHandler.class);

    private static final Scheduler VIRTUAL = Schedulers.fromExecutor(
        Executors.newVirtualThreadPerTaskExecutor());

    private final RequestPipeline requestPipeline;
    private final ProviderAdapterRegistry providerRegistry;
    private final RoutingEngine routingEngine;
    private final UpstreamClient upstreamClient;
    private final LegateConfig legateConfig;
    private final ObjectMapper objectMapper;

    public NativeMessagesHandler(
        RequestPipeline requestPipeline,
        ProviderAdapterRegistry providerRegistry,
        RoutingEngine routingEngine,
        UpstreamClient upstreamClient,
        LegateConfig legateConfig,
        ObjectMapper objectMapper
    ) {
        this.requestPipeline = requestPipeline;
        this.providerRegistry = providerRegistry;
        this.routingEngine = routingEngine;
        this.upstreamClient = upstreamClient;
        this.legateConfig = legateConfig;
        this.objectMapper = objectMapper;
    }

    public Mono<ServerResponse> handleMessages(ServerRequest request) {
        String requestId = RequestIdWebFilter.getRequestId(request.exchange());

        return request.bodyToMono(String.class)
            .flatMap(rawBody -> {
                String model = extractModel(rawBody);
                RequestContext context = new RequestContext(requestId);
                context.setOriginalRequest(ChatCompletionRequest.builder().model(model).build());
                context.setRequestHeaders(
                    request.headers().asHttpHeaders().toSingleValueMap()
                        .entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().toLowerCase(),
                            java.util.Map.Entry::getValue,
                            (a, b) -> a)));

                return Mono.fromCallable(() -> {
                        requestPipeline.execute(request, context);
                        return rawBody;
                    })
                    .subscribeOn(VIRTUAL)
                    .flatMap(body -> executeNativeMessages(body, model, context));
            })
            .flatMap(responseBody -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(responseBody))
            .onErrorResume(e -> handleError(requestId, e));
    }

    private Mono<String> executeNativeMessages(String rawBody, String model, RequestContext context) {
        FallbackChain chain = routingEngine.resolveChain(model);
        CircuitBreakerRegistry cb = routingEngine.getCircuitBreakerRegistry();
        int maxAttempts = legateConfig.routing().retry().maxAttempts();
        return tryNativeMessages(rawBody, model, chain, cb, 0, maxAttempts);
    }

    private Mono<String> tryNativeMessages(
        String rawBody,
        String model,
        FallbackChain chain,
        CircuitBreakerRegistry cb,
        int attempt,
        int maxAttempts
    ) {
        Optional<FallbackChain.IndexedEndpoint> candidate = chain.getNextAvailable(attempt, cb);
        if (candidate.isEmpty()) {
            return Mono.error(new NoEndpointAvailableException(model,
                "No endpoint available for native messages after " + attempt + " attempt(s)"));
        }

        FallbackChain.IndexedEndpoint indexed = candidate.get();
        ResolvedEndpoint endpoint = indexed.endpoint();
        ProviderAdapter adapter = providerRegistry.getByName(endpoint.providerName())
            .orElseThrow(() -> new NoEndpointAvailableException(model,
                "No adapter for provider " + endpoint.providerName()));

        if (!adapter.supportsNativeMessages()) {
            if (attempt + 1 < maxAttempts) {
                return tryNativeMessages(rawBody, model, chain, cb, indexed.index() + 1, maxAttempts);
            }
            return Mono.error(new NoEndpointAvailableException(model,
                "No endpoint in chain supports the native messages API"));
        }

        return Mono.fromCallable(() -> adapter.translateNativeMessagesRequest(rawBody, endpoint))
            .subscribeOn(VIRTUAL)
            .flatMap(httpReq -> upstreamClient.sendRequest(httpReq, endpoint))
            .flatMap(httpResp -> {
                if (!httpResp.isSuccess()) {
                    return Mono.error(new UpstreamException(
                        endpoint.providerName(), httpResp.statusCode(), httpResp.body()));
                }
                return Mono.just(httpResp.body());
            })
            .onErrorResume(err -> {
                cb.recordFailure(endpoint);
                if (attempt + 1 < maxAttempts) {
                    return tryNativeMessages(rawBody, model, chain, cb, indexed.index() + 1, maxAttempts);
                }
                return Mono.error(err);
            });
    }

    private String extractModel(String rawBody) {
        try {
            return objectMapper.readTree(rawBody).path("model").asText("unknown");
        } catch (Exception e) {
            log.warn("Could not extract model from native messages body: {}", e.getMessage());
            return "unknown";
        }
    }

    private Mono<ServerResponse> handleError(String requestId, Throwable error) {
        log.error("Native messages request {} failed: {}", requestId, error.getMessage(), error);
        return Mono.error(error);
    }
}
