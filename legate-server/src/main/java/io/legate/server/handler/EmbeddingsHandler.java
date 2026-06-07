package io.legate.server.handler;

import io.legate.core.config.LegateConfig;
import io.legate.core.context.RequestContext;
import io.legate.core.exception.NoEndpointAvailableException;
import io.legate.core.exception.UpstreamException;
import io.legate.core.model.EmbeddingRequest;
import io.legate.core.model.EmbeddingResponse;
import io.legate.core.provider.ProviderAdapter;
import io.legate.core.provider.ProviderAdapterRegistry;
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
 * Handler for {@code POST /v1/embeddings}.
 *
 * <p>Routes the request to the first chain endpoint whose adapter reports
 * {@link ProviderAdapter#supportsEmbeddings()} true.</p>
 */
@Component
public class EmbeddingsHandler {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingsHandler.class);

    private static final Scheduler VIRTUAL = Schedulers.fromExecutor(
        Executors.newVirtualThreadPerTaskExecutor());

    private final RequestPipeline requestPipeline;
    private final ProviderAdapterRegistry providerRegistry;
    private final RoutingEngine routingEngine;
    private final UpstreamClient upstreamClient;
    private final LegateConfig legateConfig;

    public EmbeddingsHandler(
        RequestPipeline requestPipeline,
        ProviderAdapterRegistry providerRegistry,
        RoutingEngine routingEngine,
        UpstreamClient upstreamClient,
        LegateConfig legateConfig
    ) {
        this.requestPipeline = requestPipeline;
        this.providerRegistry = providerRegistry;
        this.routingEngine = routingEngine;
        this.upstreamClient = upstreamClient;
        this.legateConfig = legateConfig;
    }

    public Mono<ServerResponse> handleEmbeddings(ServerRequest request) {
        String requestId = RequestIdWebFilter.getRequestId(request.exchange());

        return request.bodyToMono(EmbeddingRequest.class)
            .flatMap(embReq -> {
                RequestContext context = new RequestContext(requestId);
                // Use a minimal ChatCompletionRequest-compatible context for pipeline steps
                io.legate.core.model.ChatCompletionRequest fakeReq =
                    io.legate.core.model.ChatCompletionRequest.builder()
                        .model(embReq.model()).build();
                context.setOriginalRequest(fakeReq);

                return Mono.fromCallable(() -> {
                        requestPipeline.execute(request, context);
                        return embReq;
                    })
                    .subscribeOn(VIRTUAL)
                    .flatMap(req -> executeEmbeddings(req, context));
            })
            .flatMap(resp -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(resp))
            .onErrorResume(e -> handleError(requestId, e));
    }

    private Mono<EmbeddingResponse> executeEmbeddings(EmbeddingRequest request, RequestContext context) {
        FallbackChain chain = routingEngine.resolveChain(request.model());
        var cb = routingEngine.getCircuitBreakerRegistry();
        int maxAttempts = legateConfig.routing().retry().maxAttempts();

        return tryEmbeddings(request, chain, cb, 0, maxAttempts);
    }

    private Mono<EmbeddingResponse> tryEmbeddings(
        EmbeddingRequest request,
        FallbackChain chain,
        io.legate.core.routing.CircuitBreakerRegistry cb,
        int attempt,
        int maxAttempts
    ) {
        Optional<FallbackChain.IndexedEndpoint> candidate = chain.getNextAvailable(attempt, cb);
        if (candidate.isEmpty()) {
            return Mono.error(new NoEndpointAvailableException(request.model(),
                "No endpoint available for embeddings after " + attempt + " attempt(s)"));
        }

        FallbackChain.IndexedEndpoint indexed = candidate.get();
        ResolvedEndpoint endpoint = indexed.endpoint();
        ProviderAdapter adapter = providerRegistry.getByName(endpoint.providerName())
            .orElseThrow(() -> new NoEndpointAvailableException(request.model(),
                "No adapter for provider " + endpoint.providerName()));

        if (!adapter.supportsEmbeddings()) {
            if (attempt + 1 < maxAttempts) {
                return tryEmbeddings(request, chain, cb, indexed.index() + 1, maxAttempts);
            }
            return Mono.error(new NoEndpointAvailableException(request.model(),
                "No endpoint in chain supports embeddings"));
        }

        return Mono.fromCallable(() -> adapter.translateEmbeddingRequest(request, endpoint))
            .subscribeOn(VIRTUAL)
            .flatMap(httpReq -> upstreamClient.sendRequest(httpReq, endpoint))
            .flatMap(httpResp -> {
                if (!httpResp.isSuccess()) {
                    return Mono.error(new UpstreamException(
                        endpoint.providerName(), httpResp.statusCode(), httpResp.body()));
                }
                return Mono.fromCallable(() -> adapter.translateEmbeddingResponse(httpResp))
                    .subscribeOn(VIRTUAL);
            })
            .onErrorResume(err -> {
                cb.recordFailure(endpoint);
                if (attempt + 1 < maxAttempts) {
                    return tryEmbeddings(request, chain, cb, indexed.index() + 1, maxAttempts);
                }
                return Mono.error(err);
            });
    }

    private Mono<ServerResponse> handleError(String requestId, Throwable error) {
        log.error("Embeddings request {} failed: {}", requestId, error.getMessage(), error);
        return Mono.error(error);
    }
}
