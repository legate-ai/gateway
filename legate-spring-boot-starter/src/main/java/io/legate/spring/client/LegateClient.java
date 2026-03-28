package io.legate.spring.client;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.cache.CacheKey;
import io.legate.core.cache.CachedResponse;
import io.legate.core.cache.ResponseCache;
import io.legate.core.context.RequestContext;
import io.legate.core.event.CompletionEvent;
import io.legate.core.event.EventBus;
import io.legate.core.exception.GuardBlockedException;
import io.legate.core.guard.GuardPipeline;
import io.legate.core.guard.GuardPipelineResult;
import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
import io.legate.core.provider.ProviderAdapter;
import io.legate.core.provider.ProviderAdapterRegistry;
import io.legate.core.provider.ProviderHttpRequest;
import io.legate.core.provider.ProviderHttpResponse;
import io.legate.core.provider.StreamContext;
import io.legate.core.routing.ResolvedEndpoint;
import io.legate.core.routing.RoutingDecision;
import io.legate.core.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Embedded Legate client for in-process LLM routing.
 *
 * <p>Runs the full Legate pipeline — guards, cache, routing, provider call,
 * and telemetry — without an HTTP hop. Use this as a library inside a Spring
 * Boot application instead of running Legate as a sidecar.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Autowired LegateClient legate;
 *
 * // Non-streaming
 * ChatCompletionResponse response = legate
 *     .chatCompletion(new ChatCompletionRequest("gpt-4o", messages, ...))
 *     .block();
 *
 * // Streaming
 * legate.chatCompletionStream(request)
 *     .doOnNext(chunk -> System.out.print(chunk.choices().get(0).delta().content()))
 *     .blockLast();
 * }</pre>
 */
public class LegateClient {

    private static final Logger log = LoggerFactory.getLogger(LegateClient.class);

    private static final java.util.concurrent.Executor VT_EXECUTOR =
        Executors.newVirtualThreadPerTaskExecutor();

    private final RoutingEngine           routingEngine;
    private final ProviderAdapterRegistry adapterRegistry;
    private final GuardPipeline           guardPipeline;
    private final ResponseCache           responseCache;
    private final EventBus                eventBus;
    private final ObjectMapper            objectMapper;
    private final WebClient               webClient;

    public LegateClient(
        RoutingEngine routingEngine,
        ProviderAdapterRegistry adapterRegistry,
        GuardPipeline guardPipeline,
        ResponseCache responseCache,
        EventBus eventBus,
        ObjectMapper objectMapper,
        WebClient.Builder webClientBuilder
    ) {
        this.routingEngine   = routingEngine;
        this.adapterRegistry = adapterRegistry;
        this.guardPipeline   = guardPipeline;
        this.responseCache   = responseCache;
        this.eventBus        = eventBus;
        this.objectMapper    = objectMapper;
        this.webClient       = webClientBuilder.build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends a non-streaming chat completion request through the Legate pipeline.
     *
     * @param request the completion request
     * @return {@link Mono} that emits the response
     */
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        String requestId = "lgt_emb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        RequestContext ctx = buildContext(requestId, request);

        return Mono.fromCallable(() -> runPipeline(ctx))
            .subscribeOn(Schedulers.fromExecutor(VT_EXECUTOR));
    }

    /**
     * Sends a streaming chat completion request through the Legate pipeline.
     *
     * @param request the completion request (should have {@code stream=true})
     * @return {@link Flux} of parsed SSE chunks
     */
    public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
        String requestId = "lgt_emb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        RequestContext ctx = buildContext(requestId, request);

        return Mono.fromCallable(() -> {
                runGuards(ctx);
                return resolveAdapterAndEndpoint(ctx);
            })
            .subscribeOn(Schedulers.fromExecutor(VT_EXECUTOR))
            .flatMapMany(pair -> streamFromProvider(ctx, pair));
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private RequestContext buildContext(String requestId, ChatCompletionRequest request) {
        RequestContext ctx = new RequestContext(requestId);
        ctx.setOriginalRequest(request);
        return ctx;
    }

    private ChatCompletionResponse runPipeline(RequestContext ctx) throws Exception {
        Instant start = Instant.now();

        // 1. Guard pipeline
        runGuards(ctx);

        // 2. Cache lookup
        ChatCompletionRequest effectiveReq = ctx.getEffectiveRequest();
        if (responseCache != null && CacheKey.isCacheable(effectiveReq)) {
            CacheKey cacheKey = CacheKey.from(effectiveReq);
            Optional<CachedResponse> cached = responseCache.get(cacheKey);
            if (cached.isPresent()) {
                ctx.setCacheHit(true);
                emitCompletionEvent(ctx, cached.get().response(), start, true);
                return cached.get().response();
            }
        }

        // 3. Route + call upstream
        AdapterEndpointPair pair = resolveAdapterAndEndpoint(ctx);
        ctx.markUpstreamCallStarted();
        ProviderHttpResponse httpResp = sendToProvider(pair.providerRequest());
        ctx.markUpstreamCallCompleted();

        ChatCompletionResponse response = pair.adapter().translateResponse(httpResp);
        ctx.setResponse(response);

        // 4. Cache the response
        if (responseCache != null && CacheKey.isCacheable(effectiveReq) && response != null) {
            responseCache.put(CacheKey.from(effectiveReq), new CachedResponse(response));
        }

        emitCompletionEvent(ctx, response, start, false);
        return response;
    }

    private void runGuards(RequestContext ctx) {
        if (guardPipeline != null) {
            GuardPipelineResult result = guardPipeline.execute(ctx, Map.of());
            if (result instanceof GuardPipelineResult.Rejected r) {
                throw new GuardBlockedException(r.guardName(), r.reason());
            }
        }
    }

    private AdapterEndpointPair resolveAdapterAndEndpoint(RequestContext ctx) {
        String model = ctx.getEffectiveRequest().model();
        RoutingDecision decision = routingEngine.route(model);
        ctx.setRoutingDecision(decision);

        ResolvedEndpoint endpoint = decision.endpoint();
        ProviderAdapter adapter = adapterRegistry.resolve(endpoint.providerName(), model);
        ProviderHttpRequest providerReq;
        try {
            providerReq = adapter.translateRequest(ctx.getEffectiveRequest(), endpoint);
        } catch (Exception e) {
            throw new RuntimeException("Failed to translate request for provider " + endpoint.providerName(), e);
        }
        return new AdapterEndpointPair(adapter, endpoint, providerReq);
    }

    private ProviderHttpResponse sendToProvider(ProviderHttpRequest req) {
        var response = webClient
            .method(org.springframework.http.HttpMethod.valueOf(req.method()))
            .uri(req.url())
            .headers(h -> req.headers().forEach(h::add))
            .bodyValue(req.body() != null ? req.body() : "")
            .retrieve()
            .toEntity(String.class)
            .timeout(Duration.ofSeconds(120))
            .block(Duration.ofSeconds(125));

        if (response == null) throw new RuntimeException("No response from provider");
        return new ProviderHttpResponse(
            response.getStatusCode().value(),
            response.getHeaders().toSingleValueMap(),
            response.getBody()
        );
    }

    private Flux<ChatCompletionChunk> streamFromProvider(RequestContext ctx, AdapterEndpointPair pair) {
        StreamContext streamCtx = new StreamContext();

        return webClient
            .method(org.springframework.http.HttpMethod.valueOf(pair.providerRequest().method()))
            .uri(pair.providerRequest().url())
            .headers(h -> {
                pair.providerRequest().headers().forEach(h::add);
                h.set(HttpHeaders.ACCEPT, "text/event-stream");
            })
            .bodyValue(pair.providerRequest().body() != null ? pair.providerRequest().body() : "")
            .retrieve()
            .bodyToFlux(String.class)
            .timeout(Duration.ofSeconds(300))
            .map(line -> line.startsWith("data: ") ? line.substring(6).trim() : line.trim())
            .filter(data -> !data.isBlank())
            .takeUntil(data -> pair.adapter().isStreamTerminator(data))
            .flatMap(data -> {
                try {
                    ChatCompletionChunk chunk = pair.adapter().translateStreamChunk(data, streamCtx);
                    if (chunk != null) {
                        streamCtx.addChunk(chunk);
                        return Flux.just(chunk);
                    }
                    return Flux.empty();
                } catch (Exception e) {
                    return Flux.empty();
                }
            })
            .doOnComplete(() -> emitStreamCompletionEvent(ctx, pair.endpoint(), streamCtx));
    }

    private void emitCompletionEvent(
        RequestContext ctx,
        ChatCompletionResponse response,
        Instant start,
        boolean cacheHit
    ) {
        try {
            long latency = Duration.between(start, Instant.now()).toMillis();
            Integer inputTokens  = response != null && response.usage() != null ? response.usage().promptTokens() : null;
            Integer outputTokens = response != null && response.usage() != null ? response.usage().completionTokens() : null;
            String provider = ctx.getRoutingDecision() != null
                ? ctx.getRoutingDecision().endpoint().providerName() : "unknown";

            eventBus.publish(CompletionEvent.success(
                ctx.getRequestId(), null, null,
                ctx.getEffectiveRequest().model(),
                response != null ? response.model() : ctx.getEffectiveRequest().model(),
                provider, inputTokens, outputTokens, null,
                latency, null, cacheHit, 0
            ));
        } catch (Exception e) {
            log.debug("Could not emit completion event: {}", e.getMessage());
        }
    }

    private void emitStreamCompletionEvent(
        RequestContext ctx,
        ResolvedEndpoint endpoint,
        StreamContext streamCtx
    ) {
        try {
            long latency = Duration.between(ctx.getReceivedAt(), Instant.now()).toMillis();
            int inputTokens  = streamCtx.getUsage() != null ? streamCtx.getUsage().promptTokens()     : 0;
            int outputTokens = streamCtx.getUsage() != null ? streamCtx.getUsage().completionTokens() : 0;
            eventBus.publish(CompletionEvent.success(
                ctx.getRequestId(), null, null,
                ctx.getEffectiveRequest().model(), ctx.getEffectiveRequest().model(),
                endpoint.providerName(), inputTokens, outputTokens, null,
                latency, null, false, 0
            ));
        } catch (Exception e) {
            log.debug("Could not emit stream completion event: {}", e.getMessage());
        }
    }

    // ── Helper record ─────────────────────────────────────────────────────────

    private record AdapterEndpointPair(
        ProviderAdapter adapter,
        ResolvedEndpoint endpoint,
        ProviderHttpRequest providerRequest
    ) {}
}
