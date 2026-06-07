package io.legate.server.handler;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.cache.CacheKey;
import io.legate.core.cache.CachedResponse;
import io.legate.core.cache.ResponseCache;
import io.legate.core.config.LegateConfig;
import io.legate.core.context.RequestContext;
import io.legate.core.event.CacheHitEvent;
import io.legate.core.event.CacheMissEvent;
import io.legate.core.event.CompletionEvent;
import io.legate.core.event.EventBus;
import io.legate.core.event.FallbackTriggeredEvent;
import io.legate.core.event.RequestReceivedEvent;
import io.legate.core.event.UpstreamCallCompletedEvent;
import io.legate.core.event.UpstreamCallStartedEvent;
import io.legate.core.exception.NoEndpointAvailableException;
import io.legate.core.exception.UpstreamException;
import io.legate.core.meter.CostCalculator;
import io.legate.core.meter.SpendTracker;
import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
import io.legate.core.provider.ProviderAdapter;
import io.legate.core.provider.ProviderAdapterRegistry;
import io.legate.core.provider.StreamContext;
import io.legate.core.guard.GuardPipeline;
import io.legate.core.guard.ResponseGuardContext;
import io.legate.core.guard.ResponseGuardDecision;
import io.legate.core.ratelimit.RateLimiter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.legate.core.routing.CircuitBreakerRegistry;
import io.legate.core.routing.FallbackChain;
import io.legate.core.routing.ResolvedEndpoint;
import io.legate.core.routing.RouteRuleMatcher;
import io.legate.core.routing.RoutingDecision;
import io.legate.core.routing.RoutingEngine;
import io.legate.server.constants.LegateHeaders;
import io.legate.server.filter.RequestIdWebFilter;
import io.legate.server.handler.pipeline.RequestPipeline;
import io.legate.server.upstream.UpstreamClient;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Handler for chat completion requests (streaming and non-streaming).
 *
 * <p>Responsibilities of this handler are limited to HTTP orchestration:</p>
 * <ol>
 *   <li>Parse {@link ChatCompletionRequest} from the HTTP body.</li>
 *   <li>Run the pre-request {@link RequestPipeline} (auth, spend, access, guards).</li>
 *   <li>Consult the {@link ResponseCache} (non-streaming only).</li>
 *   <li>Route to an upstream provider with fallback retry.</li>
 *   <li>Record post-response usage, cost, and telemetry.</li>
 *   <li>Return the HTTP response (buffered or streaming SSE).</li>
 * </ol>
 *
 * <p>All governance concerns (authentication, rate limiting, spend control, guard
 * pipeline) live in dedicated {@link io.legate.server.handler.pipeline.RequestPipelineStep}
 * implementations — this class does not duplicate them.</p>
 */
@Component
public class ChatCompletionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionHandler.class);

    private static final Scheduler VIRTUAL_THREAD_SCHEDULER =
            Schedulers.fromExecutor(Executors.newVirtualThreadPerTaskExecutor());

    private final RequestPipeline requestPipeline;
    private final ObjectMapper objectMapper;
    private final ProviderAdapterRegistry providerRegistry;
    private final RoutingEngine routingEngine;
    private final UpstreamClient upstreamClient;
    private final EventBus eventBus;
    private final RateLimiter rateLimiter;
    private volatile LegateConfig legateConfig;
    private final ResponseCache responseCache;
    private final CostCalculator costCalculator;
    private final SpendTracker spendTracker;
    private final Tracer tracer;
    private final GuardPipeline guardPipeline;

    public ChatCompletionHandler(
            RequestPipeline requestPipeline,
            ObjectMapper objectMapper,
            ProviderAdapterRegistry providerRegistry,
            RoutingEngine routingEngine,
            UpstreamClient upstreamClient,
            EventBus eventBus,
            RateLimiter rateLimiter,
            LegateConfig legateConfig,
            ResponseCache responseCache,
            CostCalculator costCalculator,
            SpendTracker spendTracker,
            Tracer tracer,
            GuardPipeline guardPipeline
    ) {
        this.requestPipeline = requestPipeline;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
        this.routingEngine = routingEngine;
        this.upstreamClient = upstreamClient;
        this.eventBus = eventBus;
        this.rateLimiter = rateLimiter;
        this.legateConfig = legateConfig;
        this.responseCache = responseCache;
        this.costCalculator = costCalculator;
        this.spendTracker = spendTracker;
        this.tracer = tracer;
        this.guardPipeline = guardPipeline;
    }

    /**
     * Updates the running configuration for hot-reload. Called by {@link io.legate.server.config.FileWatcherConfig}.
     */
    public void reload(LegateConfig newConfig) {
        this.legateConfig = newConfig;
    }

    /**
     * Dispatches to streaming or non-streaming handler based on the {@code stream} field.
     */
    public Mono<ServerResponse> handleRequest(ServerRequest request) {
        return request.bodyToMono(ChatCompletionRequest.class)
                .flatMap(chatRequest -> {
                    if (Boolean.TRUE.equals(chatRequest.stream())) {
                        return handleStreamingCompletionInternal(request, chatRequest);
                    } else {
                        return handleCompletionInternal(request, chatRequest);
                    }
                })
                .onErrorResume(error -> handleError(
                        RequestIdWebFilter.getRequestId(request.exchange()), error));
    }

    public Mono<ServerResponse> handleCompletion(ServerRequest request) {
        return request.bodyToMono(ChatCompletionRequest.class)
                .flatMap(chatRequest -> handleCompletionInternal(request, chatRequest))
                .onErrorResume(error -> handleError(
                        RequestIdWebFilter.getRequestId(request.exchange()), error));
    }

    public Mono<ServerResponse> handleStreamingCompletion(ServerRequest request) {
        return request.bodyToMono(ChatCompletionRequest.class)
                .flatMap(chatRequest -> handleStreamingCompletionInternal(request, chatRequest))
                .onErrorResume(error -> handleError(
                        RequestIdWebFilter.getRequestId(request.exchange()), error));
    }

    // ── Non-streaming ─────────────────────────────────────────────────────────

    private Mono<ServerResponse> handleCompletionInternal(
            ServerRequest request, ChatCompletionRequest chatRequest
    ) {
        String requestId = RequestIdWebFilter.getRequestId(request.exchange());
        RequestContext context = new RequestContext(requestId);
        context.setOriginalRequest(chatRequest);

        tagCurrentSpan(chatRequest.model(), requestId);

        return Mono.fromCallable(() -> {
                    requestPipeline.execute(request, context);
                    return context;
                })
                .subscribeOn(VIRTUAL_THREAD_SCHEDULER)
                .doOnNext(ctx -> publishRequestReceived(ctx, false))
                .flatMap(ctx -> executeWithCacheSupport(ctx, request));
    }

    /**
     * Handles cache lookup (skip/refresh/hit/miss) then routes to upstream if needed.
     */
    private Mono<ServerResponse> executeWithCacheSupport(
            RequestContext context, ServerRequest request
    ) {
        boolean cacheEnabled = legateConfig.cache() != null && legateConfig.cache().enabled();
        if (!cacheEnabled || !CacheKey.isCacheable(context.getEffectiveRequest())) {
            return routeAndExecuteWithRetry(context)
                    .flatMap(resp -> finishWithResponse(context, resp));
        }

        String cacheDirective = StringUtils.defaultString(
                context.getRequestHeaders().get(LegateHeaders.CACHE_STATUS.toLowerCase()));
        boolean skipLookup = LegateHeaders.CACHE_SKIP.equalsIgnoreCase(cacheDirective);
        boolean refresh = LegateHeaders.CACHE_REFRESH.equalsIgnoreCase(cacheDirective);

        CacheKey key = CacheKey.from(context.getEffectiveRequest());

        if (!skipLookup && !refresh) {
            Optional<CachedResponse> cached = responseCache.get(key);
            if (cached.isPresent()) {
                context.setCacheHit(true);
                context.setResponse(cached.get().response());
                eventBus.publish(new CacheHitEvent(
                        context.getRequestId(), key.hash(), context.getTotalLatencyMs()));
                return finishWithResponse(context, cached.get().response());
            }
        }

        eventBus.publish(new CacheMissEvent(context.getRequestId(), key.hash()));

        return routeAndExecuteWithRetry(context)
                .doOnNext(resp -> {
                    if (!skipLookup) {
                        responseCache.put(key, new CachedResponse(resp));
                    }
                })
                .flatMap(resp -> finishWithResponse(context, resp));
    }

    private Mono<ServerResponse> finishWithResponse(
            RequestContext context, ChatCompletionResponse response
    ) {
        // Run response guards (short-circuits on Block, cascades Modify)
        if (!guardPipeline.isEmpty()) {
            ResponseGuardContext guardCtx = new ResponseGuardContext(
                    response, context.getEffectiveRequest(),
                    context.getVirtualKeyInfo(), context.getRequestId());
            List<ResponseGuardDecision> decisions = guardPipeline.executeResponse(guardCtx);
            for (ResponseGuardDecision decision : decisions) {
                if (decision instanceof ResponseGuardDecision.Block b) {
                    return Mono.error(new io.legate.core.exception.GuardBlockedException(
                            b.guardName(), b.reason()));
                }
                if (decision instanceof ResponseGuardDecision.Modify m) {
                    response = m.modifiedResponse();
                }
            }
        }

        context.setResponse(response);
        context.markUpstreamCallCompleted();
        recordPostResponseMetrics(context, response);
        emitCompletionEvent(context);

        var builder = ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(LegateHeaders.REQUEST_ID, context.getRequestId());

        if (context.isCacheHit()) {
            builder = builder.header(LegateHeaders.CACHE_STATUS, LegateHeaders.CACHE_HIT);
        }
        if (context.getFallbackAttempts() > 0 && context.getRoutingDecision() != null) {
            builder = builder.header(LegateHeaders.FALLBACK_PROVIDER,
                    context.getRoutingDecision().endpoint().providerName());
        }

        return builder.bodyValue(response);
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private Mono<ServerResponse> handleStreamingCompletionInternal(
            ServerRequest request, ChatCompletionRequest chatRequest
    ) {
        String requestId = RequestIdWebFilter.getRequestId(request.exchange());
        RequestContext context = new RequestContext(requestId);
        context.setOriginalRequest(chatRequest);

        tagCurrentSpan(chatRequest.model(), requestId);

        return Mono.fromCallable(() -> {
                    requestPipeline.execute(request, context);
                    return context;
                })
                .subscribeOn(VIRTUAL_THREAD_SCHEDULER)
                .doOnNext(ctx -> publishRequestReceived(ctx, true))
                .flatMapMany(this::routeAndStream)
                .doOnComplete(() -> {
                    context.markUpstreamCallCompleted();
                    costCalculator.calculate(context);
                    recordSpend(context);
                    emitCompletionEvent(context);
                })
                .doOnError(error -> {
                    log.error("Streaming error for request {}", requestId, error);
                    context.setErrorCode(error.getClass().getSimpleName());
                    emitCompletionEvent(context);
                })
                .as(eventStream -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .header(LegateHeaders.REQUEST_ID, requestId)
                        .body(eventStream, ServerSentEvent.class));
    }

    // ── Routing + retry with fallback chain ───────────────────────────────────

    private Mono<ChatCompletionResponse> routeAndExecuteWithRetry(RequestContext context) {
        FallbackChain chain = resolveChain(context);
        CircuitBreakerRegistry cb = routingEngine.getCircuitBreakerRegistry();
        int maxAttempts = legateConfig.routing().retry().maxAttempts();
        return attemptWithChain(context, chain, cb, 0, maxAttempts);
    }

    /**
     * Resolves the fallback chain, applying any matching routing rules first.
     * Rule matches may override the target chain or substitute the model.
     */
    private FallbackChain resolveChain(RequestContext context) {
        Optional<RouteRuleMatcher.RuleMatch> ruleMatch = routingEngine.matchRule(
                context.getEffectiveRequest(),
                context.getVirtualKeyInfo(),
                context.getRequestHeaders()
        );

        if (ruleMatch.isPresent()) {
            RouteRuleMatcher.RuleMatch match = ruleMatch.get();
            if (match.targetChain() != null) {
                log.debug("Request {}: routing rule '{}' → chain '{}'",
                        context.getRequestId(), match.ruleName(), match.targetChain());
                return routingEngine.resolveChainByName(match.targetChain());
            }
            if (match.targetModel() != null) {
                log.debug("Request {}: routing rule '{}' → model '{}'",
                        context.getRequestId(), match.ruleName(), match.targetModel());
                context.setEffectiveRequest(
                        context.getEffectiveRequest().withModel(match.targetModel()));
            }
        }

        return routingEngine.resolveChain(context.getEffectiveRequest().model());
    }

    private Mono<ChatCompletionResponse> attemptWithChain(
            RequestContext context,
            FallbackChain chain,
            CircuitBreakerRegistry circuitBreakerRegistry,
            int attempt,
            int maxAttempts
    ) {
        Optional<FallbackChain.IndexedEndpoint> candidate = chain.getNextAvailable(attempt, circuitBreakerRegistry);
        if (candidate.isEmpty()) {
            return Mono.error(new NoEndpointAvailableException(
                    context.getEffectiveRequest().model(),
                    "All %d endpoint(s) in chain '%s' are unavailable after %d attempt(s)."
                            .formatted(chain.size(), chain.name(), attempt)));
        }

        FallbackChain.IndexedEndpoint indexed = candidate.get();
        ResolvedEndpoint endpoint = indexed.endpoint();

        if (attempt > 0) {
            String fromProvider = chain.get(attempt - 1)
                    .map(ResolvedEndpoint::providerName).orElse("unknown");
            eventBus.publish(new FallbackTriggeredEvent(
                    context.getRequestId(), fromProvider, endpoint.providerName(),
                    "UpstreamFailure", attempt));
            context.incrementFallbackAttempts();
        }

        context.setRoutingDecision(
                RoutingDecision.fallback(endpoint, chain.name(), attempt + 1, "chain:" + chain.name()));

        return executeUpstream(context, endpoint)
                .doOnSuccess(resp -> {
                    circuitBreakerRegistry.recordSuccess(endpoint);
                    Long latencyMs = context.getUpstreamLatencyMs();
                    if (latencyMs != null) {
                        routingEngine.getLatencyTracker().record(endpoint, latencyMs);
                    }
                })
                .onErrorResume(error -> {
                    circuitBreakerRegistry.recordFailure(endpoint);
                    log.warn("Request {} attempt {} failed (provider='{}'): {}",
                            context.getRequestId(), attempt + 1, endpoint.providerName(),
                            error.getMessage());
                    if (attempt + 1 < maxAttempts) {
                        return attemptWithChain(context, chain, circuitBreakerRegistry, indexed.index() + 1, maxAttempts);
                    }
                    return Mono.error(error);
                });
    }

    private Mono<ChatCompletionResponse> executeUpstream(
            RequestContext context, ResolvedEndpoint endpoint
    ) {
        ChatCompletionRequest effectiveRequest = context.getEffectiveRequest();
        String model = effectiveRequest.model();
        ProviderAdapter adapter = providerRegistry.getByName(endpoint.providerName())
                .or(() -> providerRegistry.findByModel(model))
                .orElseThrow(() -> new NoEndpointAvailableException(model,
                        "No adapter for provider '%s'".formatted(endpoint.providerName())));

        return Mono.fromCallable(() -> adapter.translateRequest(effectiveRequest, endpoint))
                .subscribeOn(VIRTUAL_THREAD_SCHEDULER)
                .flatMap(httpRequest -> {
                    context.markUpstreamCallStarted();
                    eventBus.publish(new UpstreamCallStartedEvent(
                            context.getRequestId(), adapter.getProviderName(), model, endpoint.baseUrl()));

                    return upstreamClient.sendRequest(httpRequest, endpoint)
                            .flatMap(httpResponse -> {
                                eventBus.publish(new UpstreamCallCompletedEvent(
                                        context.getRequestId(), adapter.getProviderName(), model,
                                        httpResponse.statusCode(), context.getUpstreamLatencyMs(),
                                        httpResponse.isSuccess()));

                                if (!httpResponse.isSuccess()) {
                                    return Mono.error(new UpstreamException(
                                            adapter.getProviderName(), httpResponse.statusCode(),
                                            httpResponse.body()));
                                }
                                return Mono.fromCallable(() -> adapter.translateResponse(httpResponse))
                                        .subscribeOn(VIRTUAL_THREAD_SCHEDULER);
                            });
                });
    }

    // ── Streaming internals ───────────────────────────────────────────────────

    private Flux<ServerSentEvent<String>> routeAndStream(RequestContext context) {
        FallbackChain chain = resolveChain(context);
        CircuitBreakerRegistry cbRegistry = routingEngine.getCircuitBreakerRegistry();

        Optional<FallbackChain.IndexedEndpoint> candidate = chain.getNextAvailable(0, cbRegistry);
        if (candidate.isEmpty()) {
            return Flux.error(new NoEndpointAvailableException(
                    context.getEffectiveRequest().model(), "No available endpoint in chain"));
        }

        ResolvedEndpoint endpoint = candidate.get().endpoint();
        ChatCompletionRequest effectiveRequest = context.getEffectiveRequest();
        String model = effectiveRequest.model();
        ProviderAdapter adapter = providerRegistry.getByName(endpoint.providerName())
                .or(() -> providerRegistry.findByModel(model))
                .orElseThrow(() -> new NoEndpointAvailableException(model,
                        "No adapter for provider '%s'".formatted(endpoint.providerName())));

        context.setRoutingDecision(RoutingDecision.primary(endpoint, "chain:" + chain.name()));
        StreamContext streamContext = new StreamContext();

        return Mono.fromCallable(() -> adapter.translateRequest(effectiveRequest, endpoint))
                .subscribeOn(VIRTUAL_THREAD_SCHEDULER)
                .flatMapMany(httpRequest -> {
                    context.markUpstreamCallStarted();
                    eventBus.publish(new UpstreamCallStartedEvent(
                            context.getRequestId(), adapter.getProviderName(), model, endpoint.baseUrl()));

                    return upstreamClient.streamRequest(httpRequest, endpoint)
                            .concatMap(dataLine -> translateStreamChunk(adapter, dataLine, streamContext))
                            .mapNotNull(chunk -> chunk)   // remove nulls from non-content lines
                            .concatMap(chunk -> serializeToSse(chunk))
                            .doOnComplete(() -> {
                                context.setUsage(streamContext.getUsage());
                                cbRegistry.recordSuccess(endpoint);
                                Long latencyMs = context.getUpstreamLatencyMs();
                                if (latencyMs != null) {
                                    routingEngine.getLatencyTracker().record(endpoint, latencyMs);
                                }
                                eventBus.publish(new UpstreamCallCompletedEvent(
                                        context.getRequestId(), adapter.getProviderName(), model,
                                        200, latencyMs, true));
                            })
                            .doOnError(error -> cbRegistry.recordFailure(endpoint));
                });
    }

    /**
     * Translates a single SSE data line to a {@link ChatCompletionChunk}.
     * Returns {@link Mono#empty()} for stream terminators.
     */
    private Mono<ChatCompletionChunk> translateStreamChunk(
            ProviderAdapter adapter, String dataLine, StreamContext streamContext
    ) {
        if (adapter.isStreamTerminator(dataLine)) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> adapter.translateStreamChunk(dataLine, streamContext))
                .subscribeOn(VIRTUAL_THREAD_SCHEDULER);
    }

    /**
     * Serialises a {@link ChatCompletionChunk} to a {@link ServerSentEvent}.
     * Returns {@link Mono#empty()} if serialisation fails.
     */
    private Mono<ServerSentEvent<String>> serializeToSse(ChatCompletionChunk chunk) {
        try {
            String json = objectMapper.writeValueAsString(chunk);
            return Mono.just(ServerSentEvent.<String>builder().data(json).build());
        } catch (Exception e) {
            log.error("Failed to serialize streaming chunk — skipping", e);
            return Mono.empty();
        }
    }

    // ── Post-response ─────────────────────────────────────────────────────────

    private void recordPostResponseMetrics(RequestContext context, ChatCompletionResponse response) {
        if (context.getVirtualKeyInfo() != null) {
            int tokens = response.usage() != null ? response.usage().totalTokens() : 0;
            rateLimiter.reportUsage(context.getVirtualKeyInfo().keyId(), tokens);
        }
        costCalculator.calculate(context);
        recordSpend(context);
    }

    private void recordSpend(RequestContext context) {
        if (context.getEstimatedCostUsd() != null && context.getVirtualKeyInfo() != null) {
            spendTracker.recordSpend(
                    context.getVirtualKeyInfo().keyId(), context.getEstimatedCostUsd());
        }
    }

    private void publishRequestReceived(RequestContext context, boolean streaming) {
        String keyId = context.getVirtualKeyInfo() != null
                ? context.getVirtualKeyInfo().keyId() : null;
        eventBus.publish(new RequestReceivedEvent(
                context.getRequestId(),
                context.getOriginalRequest().model(),
                keyId,
                streaming));
    }

    private void emitCompletionEvent(RequestContext context) {
        String keyId = context.getVirtualKeyInfo() != null ? context.getVirtualKeyInfo().keyId() : null;
        String team = context.getVirtualKeyInfo() != null ? context.getVirtualKeyInfo().teamName() : null;
        String provider = context.getRoutingDecision() != null
                ? context.getRoutingDecision().endpoint().providerName() : null;

        if (context.getResponse() != null || context.getUsage() != null) {
            eventBus.publish(CompletionEvent.success(
                    context.getRequestId(), keyId, team,
                    context.getOriginalRequest().model(),
                    context.getResponse() != null ? context.getResponse().model() : null,
                    provider,
                    context.getUsage() != null ? context.getUsage().promptTokens() : null,
                    context.getUsage() != null ? context.getUsage().completionTokens() : null,
                    context.getEstimatedCostUsd(),
                    context.getTotalLatencyMs(),
                    context.getUpstreamLatencyMs(),
                    context.isCacheHit(),
                    context.getFallbackAttempts()
            ));
        } else {
            eventBus.publish(CompletionEvent.failure(
                    context.getRequestId(), keyId, team,
                    context.getOriginalRequest().model(),
                    context.getTotalLatencyMs(),
                    StringUtils.defaultIfBlank(context.getErrorCode(), "UNKNOWN_ERROR")
            ));
        }
    }

    private Mono<ServerResponse> handleError(String requestId, Throwable error) {
        log.error("Request {} failed: {}", requestId, error.getMessage(), error);
        return Mono.error(error);
    }

    /**
     * Tags the current Micrometer Tracing span with Legate-specific attributes.
     * No-op when tracing is not configured or the span is null.
     */
    private void tagCurrentSpan(String model, String requestId) {
        if (tracer == null) {
            return;
        }
        Span current = tracer.currentSpan();
        if (current == null) {
            return;
        }
        current.tag("legate.model", model);
        current.tag("legate.request_id", requestId);
        current.name("legate.llm.chat_completion");
    }
}
