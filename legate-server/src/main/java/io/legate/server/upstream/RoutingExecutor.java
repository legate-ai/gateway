package io.legate.server.upstream;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.config.LegateConfig;
import io.legate.core.context.RequestContext;
import io.legate.core.event.EventBus;
import io.legate.core.event.FallbackTriggeredEvent;
import io.legate.core.event.UpstreamCallCompletedEvent;
import io.legate.core.event.UpstreamCallStartedEvent;
import io.legate.core.exception.NoEndpointAvailableException;
import io.legate.core.model.ChatCompletionChunk;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
import io.legate.core.provider.ProviderAdapter;
import io.legate.core.provider.ProviderAdapterRegistry;
import io.legate.core.provider.StreamContext;
import io.legate.core.routing.CircuitBreakerRegistry;
import io.legate.core.routing.FallbackChain;
import io.legate.core.routing.ResolvedEndpoint;
import io.legate.core.routing.RouteRuleMatcher;
import io.legate.core.routing.RoutingDecision;
import io.legate.core.routing.RoutingEngine;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates routing, retry, fallback, and upstream execution for both
 * streaming and non-streaming chat completion requests.
 *
 * <p>Extracts all routing logic from {@code ChatCompletionHandler} so the
 * handler retains only HTTP orchestration concerns. Also owns GenAI span
 * tagging using OTel semantic conventions (gen_ai.*).</p>
 */
@Component
public class RoutingExecutor {

    private static final Logger log = LoggerFactory.getLogger(RoutingExecutor.class);

    private static final Scheduler VIRTUAL = Schedulers.fromExecutor(
        Executors.newVirtualThreadPerTaskExecutor());

    // OTel GenAI semantic convention attribute names
    private static final String GEN_AI_SYSTEM           = "gen_ai.system";
    private static final String GEN_AI_OPERATION        = "gen_ai.operation.name";
    private static final String GEN_AI_REQUEST_MODEL    = "gen_ai.request.model";
    private static final String GEN_AI_RESPONSE_MODEL   = "gen_ai.response.model";
    private static final String GEN_AI_INPUT_TOKENS     = "gen_ai.usage.input_tokens";
    private static final String GEN_AI_OUTPUT_TOKENS    = "gen_ai.usage.output_tokens";
    private static final String GEN_AI_FINISH_REASONS   = "gen_ai.response.finish_reasons";
    private static final String GEN_AI_REQUEST_ID       = "gen_ai.request.id";

    private final ProviderAdapterRegistry providerRegistry;
    private final RoutingEngine routingEngine;
    private final UpstreamClient upstreamClient;
    private final EventBus eventBus;
    private final LegateConfig legateConfig;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;

    public RoutingExecutor(
        ProviderAdapterRegistry providerRegistry,
        RoutingEngine routingEngine,
        UpstreamClient upstreamClient,
        EventBus eventBus,
        LegateConfig legateConfig,
        Tracer tracer,
        ObjectMapper objectMapper
    ) {
        this.providerRegistry = providerRegistry;
        this.routingEngine = routingEngine;
        this.upstreamClient = upstreamClient;
        this.eventBus = eventBus;
        this.legateConfig = legateConfig;
        this.tracer = tracer;
        this.objectMapper = objectMapper;
    }

    // ── Non-streaming ─────────────────────────────────────────────────────────

    public Mono<ChatCompletionResponse> execute(RequestContext context) {
        tagRequestSpan(context, "chat");
        FallbackChain chain = resolveChain(context);
        CircuitBreakerRegistry cb = routingEngine.getCircuitBreakerRegistry();
        int maxAttempts = legateConfig.routing().retry().maxAttempts();
        return attemptWithChain(context, chain, cb, 0, maxAttempts)
            .doOnSuccess(resp -> tagResponseSpan(context, resp));
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    public Flux<ServerSentEvent<String>> stream(RequestContext context) {
        tagRequestSpan(context, "chat");
        FallbackChain chain = resolveChain(context);
        CircuitBreakerRegistry cb = routingEngine.getCircuitBreakerRegistry();
        int maxAttempts = legateConfig.routing().retry().maxAttempts();
        return streamWithChain(context, chain, cb, 0, maxAttempts);
    }

    // ── Chain resolution ──────────────────────────────────────────────────────

    public FallbackChain resolveChain(RequestContext context) {
        Optional<RouteRuleMatcher.RuleMatch> ruleMatch = routingEngine.matchRule(
            context.getEffectiveRequest(),
            context.getVirtualKeyInfo(),
            context.getRequestHeaders());

        if (ruleMatch.isPresent()) {
            RouteRuleMatcher.RuleMatch match = ruleMatch.get();
            if (match.stickyKey() != null) {
                context.setStickyKey(match.stickyKey());
            }
            if (match.targetChain() != null) {
                log.debug("Request {}: rule '{}' → chain '{}'",
                    context.getRequestId(), match.ruleName(), match.targetChain());
                return routingEngine.resolveChainByName(match.targetChain());
            }
            if (match.targetModel() != null) {
                log.debug("Request {}: rule '{}' → model '{}'",
                    context.getRequestId(), match.ruleName(), match.targetModel());
                context.setEffectiveRequest(
                    context.getEffectiveRequest().withModel(match.targetModel()));
            }
        }
        return routingEngine.resolveChain(context.getEffectiveRequest().model());
    }

    // ── Non-streaming retry loop ──────────────────────────────────────────────

    private Mono<ChatCompletionResponse> attemptWithChain(
        RequestContext context,
        FallbackChain chain,
        CircuitBreakerRegistry cb,
        int attempt,
        int maxAttempts
    ) {
        Optional<FallbackChain.IndexedEndpoint> candidate = attempt == 0 && context.getStickyKey() != null
            ? chain.getSticky(context.getStickyKey(), cb)
            : chain.getNextAvailable(attempt, cb);
        if (candidate.isEmpty()) {
            return Mono.error(new NoEndpointAvailableException(
                context.getEffectiveRequest().model(),
                "All %d endpoint(s) in chain '%s' unavailable after %d attempt(s)."
                    .formatted(chain.size(), chain.name(), attempt)));
        }

        FallbackChain.IndexedEndpoint indexed = candidate.get();
        ResolvedEndpoint endpoint = indexed.endpoint();

        if (attempt > 0) {
            publishFallback(context, chain, endpoint, attempt);
            context.incrementFallbackAttempts();
        }

        context.setRoutingDecision(
            RoutingDecision.fallback(endpoint, chain.name(), attempt + 1, "chain:" + chain.name()));

        return executeUpstream(context, endpoint)
            .doOnSuccess(resp -> {
                cb.recordSuccess(endpoint);
                recordLatency(context, endpoint);
            })
            .onErrorResume(error -> {
                cb.recordFailure(endpoint);
                log.warn("Request {} attempt {} failed (provider='{}'): {}",
                    context.getRequestId(), attempt + 1, endpoint.providerName(), error.getMessage());
                if (attempt + 1 < maxAttempts) {
                    return attemptWithChain(context, chain, cb, indexed.index() + 1, maxAttempts);
                }
                return Mono.error(error);
            });
    }

    private Mono<ChatCompletionResponse> executeUpstream(
        RequestContext context, ResolvedEndpoint endpoint
    ) {
        ChatCompletionRequest req = context.getEffectiveRequest();
        String model = req.model();
        ProviderAdapter adapter = resolveAdapter(endpoint, model);

        return Mono.fromCallable(() -> adapter.translateRequest(req, endpoint))
            .subscribeOn(VIRTUAL)
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
                            return Mono.error(new io.legate.core.exception.UpstreamException(
                                adapter.getProviderName(), httpResponse.statusCode(),
                                httpResponse.body()));
                        }
                        return Mono.fromCallable(() -> adapter.translateResponse(httpResponse))
                            .subscribeOn(VIRTUAL);
                    });
            });
    }

    // ── Streaming retry loop ──────────────────────────────────────────────────

    private Flux<ServerSentEvent<String>> streamWithChain(
        RequestContext context,
        FallbackChain chain,
        CircuitBreakerRegistry cb,
        int attempt,
        int maxAttempts
    ) {
        Optional<FallbackChain.IndexedEndpoint> candidate = attempt == 0 && context.getStickyKey() != null
            ? chain.getSticky(context.getStickyKey(), cb)
            : chain.getNextAvailable(attempt, cb);
        if (candidate.isEmpty()) {
            return Flux.error(new NoEndpointAvailableException(
                context.getEffectiveRequest().model(),
                "All %d endpoint(s) in chain '%s' exhausted after %d attempt(s)."
                    .formatted(chain.size(), chain.name(), attempt)));
        }

        FallbackChain.IndexedEndpoint indexed = candidate.get();
        ResolvedEndpoint endpoint = indexed.endpoint();
        ChatCompletionRequest req = context.getEffectiveRequest();
        String model = req.model();

        if (attempt > 0) {
            publishFallback(context, chain, endpoint, attempt);
            context.incrementFallbackAttempts();
        }

        context.setRoutingDecision(attempt == 0
            ? RoutingDecision.primary(endpoint, "chain:" + chain.name())
            : RoutingDecision.fallback(endpoint, chain.name(), attempt + 1, "chain:" + chain.name()));

        ProviderAdapter adapter = resolveAdapter(endpoint, model);
        StreamContext streamCtx = new StreamContext();
        AtomicBoolean firstTokenSeen = new AtomicBoolean(false);

        return Mono.fromCallable(() -> adapter.translateRequest(req, endpoint))
            .subscribeOn(VIRTUAL)
            .flatMapMany(httpRequest -> {
                context.markUpstreamCallStarted();
                eventBus.publish(new UpstreamCallStartedEvent(
                    context.getRequestId(), adapter.getProviderName(), model, endpoint.baseUrl()));

                return upstreamClient.streamRequest(httpRequest, endpoint)
                    .concatMap(line -> translateChunk(adapter, line, streamCtx))
                    .mapNotNull(chunk -> chunk)
                    .doOnNext(chunk -> firstTokenSeen.set(true))
                    .concatMap(chunk -> serializeChunk(chunk))
                    .doOnComplete(() -> {
                        context.setUsage(streamCtx.getUsage());
                        cb.recordSuccess(endpoint);
                        recordLatency(context, endpoint);
                        eventBus.publish(new UpstreamCallCompletedEvent(
                            context.getRequestId(), adapter.getProviderName(), model,
                            200, context.getUpstreamLatencyMs(), true));
                        tagStreamUsageSpan(context);
                    })
                    .onErrorResume(error -> {
                        cb.recordFailure(endpoint);
                        if (!firstTokenSeen.get() && attempt + 1 < maxAttempts) {
                            log.warn("Stream attempt {} failed before first token (provider='{}'), retrying: {}",
                                attempt + 1, endpoint.providerName(), error.getMessage());
                            return streamWithChain(context, chain, cb, indexed.index() + 1, maxAttempts);
                        }
                        return Flux.error(error);
                    });
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ProviderAdapter resolveAdapter(ResolvedEndpoint endpoint, String model) {
        return providerRegistry.getByName(endpoint.providerName())
            .or(() -> providerRegistry.findByModel(model))
            .orElseThrow(() -> new NoEndpointAvailableException(model,
                "No adapter for provider '%s'".formatted(endpoint.providerName())));
    }

    private Mono<ChatCompletionChunk> translateChunk(
        ProviderAdapter adapter, String line, StreamContext ctx
    ) {
        if (adapter.isStreamTerminator(line)) return Mono.empty();
        return Mono.fromCallable(() -> adapter.translateStreamChunk(line, ctx))
            .subscribeOn(VIRTUAL);
    }

    private Mono<ServerSentEvent<String>> serializeChunk(ChatCompletionChunk chunk) {
        try {
            String json = objectMapper.writeValueAsString(chunk);
            return Mono.just(ServerSentEvent.<String>builder().data(json).build());
        } catch (Exception e) {
            log.error("Failed to serialize streaming chunk — skipping", e);
            return Mono.empty();
        }
    }

    private void publishFallback(
        RequestContext context, FallbackChain chain, ResolvedEndpoint to, int attempt
    ) {
        String from = chain.get(attempt - 1).map(ResolvedEndpoint::providerName).orElse("unknown");
        eventBus.publish(new FallbackTriggeredEvent(
            context.getRequestId(), from, to.providerName(), "UpstreamFailure", attempt));
    }

    private void recordLatency(RequestContext context, ResolvedEndpoint endpoint) {
        Long ms = context.getUpstreamLatencyMs();
        if (ms != null) routingEngine.getLatencyTracker().record(endpoint, ms);
    }

    // ── OTel GenAI span tagging ───────────────────────────────────────────────

    private void tagRequestSpan(RequestContext context, String operation) {
        Span span = currentSpan();
        if (span == null) return;
        span.name("gen_ai." + operation);
        span.tag(GEN_AI_OPERATION,     operation);
        span.tag(GEN_AI_REQUEST_MODEL, context.getEffectiveRequest().model());
        span.tag(GEN_AI_REQUEST_ID,    context.getRequestId());
    }

    private void tagResponseSpan(RequestContext context, ChatCompletionResponse resp) {
        Span span = currentSpan();
        if (span == null || resp == null) return;
        if (context.getRoutingDecision() != null) {
            span.tag(GEN_AI_SYSTEM, context.getRoutingDecision().endpoint().providerName());
        }
        if (resp.model() != null) {
            span.tag(GEN_AI_RESPONSE_MODEL, resp.model());
        }
        if (resp.usage() != null) {
            if (resp.usage().promptTokens()     != null) span.tag(GEN_AI_INPUT_TOKENS,  String.valueOf(resp.usage().promptTokens()));
            if (resp.usage().completionTokens() != null) span.tag(GEN_AI_OUTPUT_TOKENS, String.valueOf(resp.usage().completionTokens()));
        }
        if (resp.choices() != null && !resp.choices().isEmpty()) {
            String reasons = resp.choices().stream()
                .map(c -> c.finishReason() != null ? c.finishReason() : "null")
                .distinct()
                .reduce((a, b) -> a + "," + b).orElse("");
            span.tag(GEN_AI_FINISH_REASONS, reasons);
        }
    }

    private void tagStreamUsageSpan(RequestContext context) {
        Span span = currentSpan();
        if (span == null) return;
        if (context.getRoutingDecision() != null) {
            span.tag(GEN_AI_SYSTEM, context.getRoutingDecision().endpoint().providerName());
        }
        if (context.getUsage() != null) {
            if (context.getUsage().promptTokens()     != null) span.tag(GEN_AI_INPUT_TOKENS,  String.valueOf(context.getUsage().promptTokens()));
            if (context.getUsage().completionTokens() != null) span.tag(GEN_AI_OUTPUT_TOKENS, String.valueOf(context.getUsage().completionTokens()));
        }
    }

    private Span currentSpan() {
        if (tracer == null) return null;
        return tracer.currentSpan();
    }
}
