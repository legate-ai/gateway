package io.legate.server.handler;

import io.legate.core.cache.CacheKey;
import io.legate.core.cache.CachedResponse;
import io.legate.core.cache.ResponseCache;
import io.legate.core.config.LegateConfig;
import io.legate.core.context.RequestContext;
import io.legate.core.event.CacheHitEvent;
import io.legate.core.event.CacheMissEvent;
import io.legate.core.event.EventBus;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.server.constants.LegateHeaders;
import io.legate.server.filter.RequestIdWebFilter;
import io.legate.server.handler.pipeline.PostResponsePipeline;
import io.legate.server.handler.pipeline.RequestPipeline;
import io.legate.server.upstream.RoutingExecutor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * HTTP orchestration handler for chat completions.
 *
 * <p>Responsibilities (only):</p>
 * <ol>
 *   <li>Parse {@link ChatCompletionRequest} from the HTTP body.</li>
 *   <li>Run the pre-request {@link RequestPipeline}.</li>
 *   <li>Check the response cache (non-streaming only).</li>
 *   <li>Delegate routing and upstream execution to {@link RoutingExecutor}.</li>
 *   <li>Run the post-response {@link PostResponsePipeline}.</li>
 *   <li>Build and return the HTTP response.</li>
 * </ol>
 *
 * <p>All business concerns (cost, spend, events, cache write, routing, retry)
 * live in dedicated pipeline steps or {@link RoutingExecutor}.</p>
 */
@Component
public class ChatCompletionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionHandler.class);

    private static final Scheduler VIRTUAL = Schedulers.fromExecutor(
        Executors.newVirtualThreadPerTaskExecutor());

    private final RequestPipeline requestPipeline;
    private final PostResponsePipeline postResponsePipeline;
    private final RoutingExecutor routingExecutor;
    private final ResponseCache responseCache;
    private final LegateConfig legateConfig;
    private final EventBus eventBus;

    public ChatCompletionHandler(
        RequestPipeline requestPipeline,
        PostResponsePipeline postResponsePipeline,
        RoutingExecutor routingExecutor,
        ResponseCache responseCache,
        LegateConfig legateConfig,
        EventBus eventBus
    ) {
        this.requestPipeline = requestPipeline;
        this.postResponsePipeline = postResponsePipeline;
        this.routingExecutor = routingExecutor;
        this.responseCache = responseCache;
        this.legateConfig = legateConfig;
        this.eventBus = eventBus;
    }

    public Mono<ServerResponse> handleRequest(ServerRequest request) {
        return request.bodyToMono(ChatCompletionRequest.class)
            .flatMap(chatRequest -> Boolean.TRUE.equals(chatRequest.stream())
                ? handleStreamingInternal(request, chatRequest)
                : handleCompletionInternal(request, chatRequest))
            .onErrorResume(error -> handleError(
                RequestIdWebFilter.getRequestId(request.exchange()), error));
    }

    // ── Non-streaming ─────────────────────────────────────────────────────────

    private Mono<ServerResponse> handleCompletionInternal(
        ServerRequest request, ChatCompletionRequest chatRequest
    ) {
        String requestId = RequestIdWebFilter.getRequestId(request.exchange());
        RequestContext context = buildContext(requestId, chatRequest, request);

        return Mono.fromCallable(() -> { requestPipeline.execute(request, context); return context; })
            .subscribeOn(VIRTUAL)
            .flatMap(ctx -> lookupCacheThenExecute(ctx))
            .flatMap(ctx -> {
                postResponsePipeline.execute(ctx);
                return buildHttpResponse(ctx);
            });
    }

    private Mono<RequestContext> lookupCacheThenExecute(RequestContext context) {
        boolean cacheEnabled = legateConfig.cache() != null && legateConfig.cache().enabled();
        if (!cacheEnabled || !CacheKey.isCacheable(context.getEffectiveRequest())) {
            return routingExecutor.execute(context)
                .doOnNext(context::setResponse)
                .thenReturn(context);
        }

        String directive = StringUtils.defaultString(
            context.getRequestHeaders().get(LegateHeaders.CACHE_STATUS.toLowerCase()));
        boolean skipLookup = LegateHeaders.CACHE_SKIP.equalsIgnoreCase(directive);
        boolean refresh    = LegateHeaders.CACHE_REFRESH.equalsIgnoreCase(directive);

        if (!skipLookup && !refresh) {
            CacheKey key = CacheKey.from(context.getEffectiveRequest());
            Optional<CachedResponse> cached = responseCache.get(key);
            if (cached.isPresent()) {
                context.setCacheHit(true);
                context.setResponse(cached.get().response());
                eventBus.publish(new CacheHitEvent(
                    context.getRequestId(), key.hash(), context.getTotalLatencyMs()));
                return Mono.just(context);
            }
        }

        eventBus.publish(new CacheMissEvent(
            context.getRequestId(),
            CacheKey.from(context.getEffectiveRequest()).hash()));

        return routingExecutor.execute(context)
            .doOnNext(context::setResponse)
            .thenReturn(context);
    }

    private Mono<ServerResponse> buildHttpResponse(RequestContext context) {
        context.markUpstreamCallCompleted();
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
        return builder.bodyValue(context.getResponse());
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private Mono<ServerResponse> handleStreamingInternal(
        ServerRequest request, ChatCompletionRequest chatRequest
    ) {
        String requestId = RequestIdWebFilter.getRequestId(request.exchange());
        RequestContext context = buildContext(requestId, chatRequest, request);

        return Mono.fromCallable(() -> { requestPipeline.execute(request, context); return context; })
            .subscribeOn(VIRTUAL)
            .flatMapMany(ctx -> routingExecutor.stream(ctx)
                .doOnComplete(() -> {
                    ctx.markUpstreamCallCompleted();
                    postResponsePipeline.execute(ctx);
                })
                .doOnError(err -> {
                    log.error("Streaming error for request {}", requestId, err);
                    ctx.setErrorCode(err.getClass().getSimpleName());
                    postResponsePipeline.execute(ctx);
                }))
            .as(flux -> ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(LegateHeaders.REQUEST_ID, requestId)
                .body(flux, ServerSentEvent.class));
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private Mono<ServerResponse> handleError(String requestId, Throwable error) {
        log.error("Request {} failed: {}", requestId, error.getMessage(), error);
        return Mono.error(error);
    }

    // ── Context construction ──────────────────────────────────────────────────

    private RequestContext buildContext(
        String requestId, ChatCompletionRequest chatRequest, ServerRequest httpRequest
    ) {
        RequestContext context = new RequestContext(requestId);
        context.setOriginalRequest(chatRequest);
        context.setRequestHeaders(httpRequest.headers().asHttpHeaders().toSingleValueMap()
            .entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                e -> e.getKey().toLowerCase(), java.util.Map.Entry::getValue,
                (a, b) -> a)));
        return context;
    }
}
