package io.legate.server.handler;

import io.legate.core.context.RequestContext;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.LegacyCompletionRequest;
import io.legate.core.model.LegacyCompletionResponse;
import io.legate.core.model.Message;
import io.legate.server.constants.LegateHeaders;
import io.legate.server.filter.RequestIdWebFilter;
import io.legate.server.handler.pipeline.PostResponsePipeline;
import io.legate.server.handler.pipeline.RequestPipeline;
import io.legate.server.upstream.RoutingExecutor;
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
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Handler for {@code POST /v1/completions} — legacy text completions endpoint.
 *
 * <p>Converts a {@link LegacyCompletionRequest} into a {@link ChatCompletionRequest}
 * by wrapping the prompt as a user message, then delegates to {@link RoutingExecutor}
 * for routing, fallback, and upstream execution. The response is converted back to
 * the legacy {@link LegacyCompletionResponse} format before returning.</p>
 */
@Component
public class LegacyCompletionsHandler {

    private static final Logger log = LoggerFactory.getLogger(LegacyCompletionsHandler.class);

    private static final Scheduler VIRTUAL = Schedulers.fromExecutor(
        Executors.newVirtualThreadPerTaskExecutor());

    private final RequestPipeline requestPipeline;
    private final PostResponsePipeline postResponsePipeline;
    private final RoutingExecutor routingExecutor;

    public LegacyCompletionsHandler(
        RequestPipeline requestPipeline,
        PostResponsePipeline postResponsePipeline,
        RoutingExecutor routingExecutor
    ) {
        this.requestPipeline = requestPipeline;
        this.postResponsePipeline = postResponsePipeline;
        this.routingExecutor = routingExecutor;
    }

    public Mono<ServerResponse> handleCompletions(ServerRequest request) {
        return request.bodyToMono(LegacyCompletionRequest.class)
            .flatMap(legacyReq -> Boolean.TRUE.equals(legacyReq.stream())
                ? handleStreaming(request, legacyReq)
                : handleNonStreaming(request, legacyReq))
            .onErrorResume(e -> handleError(
                RequestIdWebFilter.getRequestId(request.exchange()), e));
    }

    // ── Non-streaming ─────────────────────────────────────────────────────────

    private Mono<ServerResponse> handleNonStreaming(
        ServerRequest request, LegacyCompletionRequest legacyReq
    ) {
        String requestId = RequestIdWebFilter.getRequestId(request.exchange());
        ChatCompletionRequest chatReq = toChatRequest(legacyReq);
        RequestContext context = buildContext(requestId, chatReq, request);

        return Mono.fromCallable(() -> { requestPipeline.execute(request, context); return context; })
            .subscribeOn(VIRTUAL)
            .flatMap(ctx -> routingExecutor.execute(ctx)
                .doOnNext(resp -> {
                    ctx.setResponse(resp);
                    ctx.markUpstreamCallCompleted();
                    postResponsePipeline.execute(ctx);
                })
                .map(LegacyCompletionResponse::from))
            .flatMap(legacyResp -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(LegateHeaders.REQUEST_ID, requestId)
                .bodyValue(legacyResp));
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private Mono<ServerResponse> handleStreaming(
        ServerRequest request, LegacyCompletionRequest legacyReq
    ) {
        String requestId = RequestIdWebFilter.getRequestId(request.exchange());
        ChatCompletionRequest chatReq = toChatRequest(legacyReq);
        RequestContext context = buildContext(requestId, chatReq, request);

        return Mono.fromCallable(() -> { requestPipeline.execute(request, context); return context; })
            .subscribeOn(VIRTUAL)
            .flatMapMany(ctx -> routingExecutor.stream(ctx)
                .doOnComplete(() -> {
                    ctx.markUpstreamCallCompleted();
                    postResponsePipeline.execute(ctx);
                })
                .doOnError(err -> {
                    log.error("Streaming error for legacy request {}", requestId, err);
                    ctx.setErrorCode(err.getClass().getSimpleName());
                    postResponsePipeline.execute(ctx);
                }))
            .as(flux -> ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(LegateHeaders.REQUEST_ID, requestId)
                .body(flux, ServerSentEvent.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChatCompletionRequest toChatRequest(LegacyCompletionRequest req) {
        return ChatCompletionRequest.builder()
            .model(req.model())
            .messages(List.of(Message.user(req.promptText())))
            .maxTokens(req.maxTokens())
            .temperature(req.temperature())
            .stream(req.stream())
            .build();
    }

    private RequestContext buildContext(
        String requestId, ChatCompletionRequest chatReq, ServerRequest httpRequest
    ) {
        RequestContext context = new RequestContext(requestId);
        context.setOriginalRequest(chatReq);
        context.setRequestHeaders(httpRequest.headers().asHttpHeaders().toSingleValueMap()
            .entrySet().stream()
            .collect(Collectors.toMap(
                e -> e.getKey().toLowerCase(),
                java.util.Map.Entry::getValue,
                (a, b) -> a)));
        return context;
    }

    private Mono<ServerResponse> handleError(String requestId, Throwable error) {
        log.error("Legacy completions request {} failed: {}", requestId, error.getMessage(), error);
        return Mono.error(error);
    }
}
