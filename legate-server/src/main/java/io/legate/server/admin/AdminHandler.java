package io.legate.server.admin;

import io.micrometer.core.instrument.MeterRegistry;
import io.legate.core.audit.AuditEventType;
import io.legate.core.audit.AuditLogger;
import io.legate.core.audit.AuditQuery;
import io.legate.core.cache.LegateCacheStats;
import io.legate.core.cache.ResponseCache;
import io.legate.core.config.LegateConfig;
import io.legate.core.key.VirtualKeyCreateRequest;
import io.legate.core.key.VirtualKeyStore;
import io.legate.server.config.FileWatcherConfig;
import io.legate.server.metrics.MetricNames;
import io.legate.server.metrics.MetricTags;
import io.legate.server.response.ApiResponseFactory;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Handler for admin API endpoints.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST   /admin/keys}               — create a virtual key (returns plaintext once)</li>
 *   <li>{@code GET    /admin/keys}               — list all keys (summaries only, no secret values)</li>
 *   <li>{@code DELETE /admin/keys/{keyId}}       — revoke a key</li>
 *   <li>{@code POST   /admin/config/reload}      — trigger hot-reload of configuration</li>
 *   <li>{@code GET    /admin/audit}              — query the audit log</li>
 *   <li>{@code GET    /admin/stats}              — real-time stats from Micrometer</li>
 *   <li>{@code DELETE /admin/cache}              — clear the response cache</li>
 * </ul>
 *
 * <p>All responses are built via {@link ApiResponseFactory}, which guarantees a consistent
 * error envelope structure and eliminates manual JSON string construction.</p>
 */
@Component
public class AdminHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminHandler.class);

    private final VirtualKeyStore    virtualKeyStore;
    private final FileWatcherConfig  fileWatcherConfig;
    private final AuditLogger auditLog;
    private final ResponseCache      responseCache;
    private final MeterRegistry      meterRegistry;
    private final ApiResponseFactory responseFactory;
    private final LegateConfig       legateConfig;

    public AdminHandler(
        VirtualKeyStore virtualKeyStore,
        FileWatcherConfig fileWatcherConfig,
        AuditLogger auditLog,
        ResponseCache responseCache,
        MeterRegistry meterRegistry,
        ApiResponseFactory responseFactory,
        LegateConfig legateConfig
    ) {
        this.virtualKeyStore   = virtualKeyStore;
        this.fileWatcherConfig = fileWatcherConfig;
        this.auditLog          = auditLog;
        this.responseCache     = responseCache;
        this.meterRegistry     = meterRegistry;
        this.responseFactory   = responseFactory;
        this.legateConfig      = legateConfig;
    }

    // ── Virtual key management ────────────────────────────────────────────────

    /** {@code POST /admin/keys} — create a new virtual key. */
    public Mono<ServerResponse> createKey(ServerRequest request) {
        return request.bodyToMono(VirtualKeyCreateRequest.class)
            .flatMap(createReq -> Mono.fromCallable(() -> virtualKeyStore.create(createReq))
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMap(responseFactory::ok)
            .onErrorResume(e -> {
                log.error("Failed to create virtual key", e);
                return responseFactory.badRequest("KEY_CREATION_FAILED",
                    "Virtual key creation failed: " + e.getMessage(), null);
            });
    }

    /** {@code GET /admin/keys} — list all virtual keys (summaries, no secret values). */
    public Mono<ServerResponse> listKeys(ServerRequest request) {
        return Mono.fromCallable(() -> virtualKeyStore.list())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(keys -> responseFactory.ok(Map.of("object", "list", "data", keys)));
    }

    /** {@code DELETE /admin/keys/{keyId}} — revoke a virtual key. */
    public Mono<ServerResponse> revokeKey(ServerRequest request) {
        String keyId = request.pathVariable("keyId");
        return Mono.fromCallable(() -> virtualKeyStore.revoke(keyId))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(revoked -> {
                if (revoked) {
                    return responseFactory.ok(Map.of("revoked", true, "key_id", keyId));
                }
                return responseFactory.notFound("KEY_NOT_FOUND",
                    "Virtual key '%s' not found or already revoked".formatted(keyId), null);
            });
    }

    // ── Configuration management ──────────────────────────────────────────────

    /** {@code POST /admin/config/reload} — trigger hot-reload of the configuration. */
    public Mono<ServerResponse> reloadConfig(ServerRequest request) {
        return Mono.fromCallable(() -> fileWatcherConfig.reloadNow())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(success -> {
                if (success) {
                    return responseFactory.ok(Map.of("status", "reloaded"));
                }
                return responseFactory.unprocessableEntity(
                    "CONFIG_VALIDATION_FAILED",
                    "Configuration validation failed — check server logs for details", null);
            });
    }

    // ── Audit log ─────────────────────────────────────────────────────────────

    /**
     * {@code GET /admin/audit?from=...&to=...&type=...&key=...&limit=50&offset=0}
     * — query the audit log.
     */
    public Mono<ServerResponse> queryAudit(ServerRequest request) {
        AuditQuery query = buildAuditQuery(request);
        return Mono.fromFuture(() -> auditLog.query(query))
            .flatMap(events -> responseFactory.ok(
                Map.of("object", "list", "data", events, "count", events.size())))
            .onErrorResume(e -> {
                log.error("Failed to query audit log", e);
                return responseFactory.badRequest("AUDIT_QUERY_FAILED",
                    "Audit query failed: " + e.getMessage(), null);
            });
    }

    private AuditQuery buildAuditQuery(ServerRequest request) {
        Instant from   = request.queryParam("from").map(Instant::parse).orElse(null);
        Instant to     = request.queryParam("to").map(Instant::parse).orElse(null);
        String  keyId  = request.queryParam("key").orElse(null);
        int     limit  = request.queryParam("limit").map(Integer::parseInt).orElse(50);
        int     offset = request.queryParam("offset").map(Integer::parseInt).orElse(0);
        AuditEventType type = request.queryParam("type")
            .map(t -> {
                try {
                    return AuditEventType.valueOf(t.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            })
            .orElse(null);
        return new AuditQuery(from, to, keyId, type, limit, offset);
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    /** {@code GET /admin/stats?window=1h} — real-time statistics summary. */
    public Mono<ServerResponse> getStats(ServerRequest request) {
        return Mono.fromCallable(this::collectStats)
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(responseFactory::ok);
    }

    private Map<String, Object> collectStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // ── Request counters ───────────────────────────────────────────────────
        double totalRequests = sumCounters(MetricNames.REQUESTS_TOTAL, null, null);
        double errorRequests = meterRegistry.find(MetricNames.REQUESTS_TOTAL).counters().stream()
            .filter(c -> c.getId().getTag(MetricTags.ERROR_TYPE) != null)
            .mapToDouble(c -> c.count()).sum();
        double successRequests = totalRequests - errorRequests;

        stats.put("total_requests",   (long) totalRequests);
        stats.put("success_requests", (long) successRequests);
        stats.put("error_requests",   (long) errorRequests);
        stats.put("error_rate", totalRequests > 0 ? errorRequests / totalRequests : 0.0);

        // ── Token counters ─────────────────────────────────────────────────────
        double inputTokens = sumCounters(MetricNames.TOKENS_TOTAL, MetricTags.GEN_AI_TOKEN_TYPE, MetricTags.TOKEN_TYPE_INPUT);
        double outputTokens = sumCounters(MetricNames.TOKENS_TOTAL, MetricTags.GEN_AI_TOKEN_TYPE, MetricTags.TOKEN_TYPE_OUTPUT);
        stats.put("total_input_tokens",  (long) inputTokens);
        stats.put("total_output_tokens", (long) outputTokens);

        // ── Cost ───────────────────────────────────────────────────────────────
        double cost = sumCounters(MetricNames.ESTIMATED_COST_USD_TOTAL, null, null);
        stats.put("estimated_cost_usd", Math.round(cost * 100_000.0) / 100_000.0);

        // ── Cache ──────────────────────────────────────────────────────────────
        LegateCacheStats legateCacheStats = responseCache.getStats();
        stats.put("cache", Map.of(
            "hits",     legateCacheStats.hits(),
            "misses",   legateCacheStats.misses(),
            "hit_rate", Math.round(legateCacheStats.hitRate() * 1000.0) / 1000.0,
            "size",     legateCacheStats.size()
        ));

        // ── Concurrency ────────────────────────────────────────────────────────
        stats.put("active_requests", activeRequests());
        stats.put("audit_log_entries", auditLog.count());

        // ── By provider ────────────────────────────────────────────────────────
        stats.put("by_provider", collectByProvider());

        // ── By virtual key ─────────────────────────────────────────────────────
        stats.put("by_virtual_key", collectByVirtualKey());

        // ── Top models ─────────────────────────────────────────────────────────
        stats.put("top_models", collectTopModels(10));

        return stats;
    }

    private Map<String, Object> collectByProvider() {
        // Group legate_requests_total counters by "provider" tag
        Map<String, Map<String, Object>> byProvider = new TreeMap<>();
        meterRegistry.find(MetricNames.REQUESTS_TOTAL).counters().forEach(c -> {
            String provider = c.getId().getTag(MetricTags.GEN_AI_SYSTEM);
            if (provider == null || provider.isBlank()) return;
            Map<String, Object> entry = byProvider.computeIfAbsent(provider, k -> new LinkedHashMap<>());
            long cur = ((Number) entry.getOrDefault("requests", 0L)).longValue();
            entry.put("requests", cur + (long) c.count());
        });
        // Add avg latency from the duration timer per provider
        meterRegistry.find(MetricNames.REQUEST_DURATION_SECONDS).timers().forEach(t -> {
            String provider = t.getId().getTag(MetricTags.GEN_AI_SYSTEM);
            if (provider == null || provider.isBlank()) return;
            Map<String, Object> entry = byProvider.computeIfAbsent(provider, k -> new LinkedHashMap<>());
            if (t.count() > 0) {
                entry.put("avg_latency_ms", Math.round(t.mean(java.util.concurrent.TimeUnit.MILLISECONDS)));
            }
        });
        // Add estimated cost per provider
        meterRegistry.find(MetricNames.ESTIMATED_COST_USD_TOTAL).counters().forEach(c -> {
            String provider = c.getId().getTag(MetricTags.GEN_AI_SYSTEM);
            if (provider == null || provider.isBlank()) return;
            Map<String, Object> entry = byProvider.computeIfAbsent(provider, k -> new LinkedHashMap<>());
            double cur = ((Number) entry.getOrDefault("estimated_cost_usd", 0.0)).doubleValue();
            entry.put("estimated_cost_usd", Math.round((cur + c.count()) * 100_000.0) / 100_000.0);
        });
        return Map.copyOf(byProvider);
    }

    private Map<String, Object> collectByVirtualKey() {
        Map<String, Map<String, Object>> byKey = new TreeMap<>();
        meterRegistry.find(MetricNames.REQUESTS_TOTAL).counters().forEach(c -> {
            String key = c.getId().getTag(MetricTags.VIRTUAL_KEY);
            if (key == null || key.isBlank() || MetricTags.NONE.equals(key)) return;
            Map<String, Object> entry = byKey.computeIfAbsent(key, k -> new LinkedHashMap<>());
            long cur = ((Number) entry.getOrDefault("requests", 0L)).longValue();
            entry.put("requests", cur + (long) c.count());
        });
        meterRegistry.find(MetricNames.ESTIMATED_COST_USD_TOTAL).counters().forEach(c -> {
            String key = c.getId().getTag(MetricTags.VIRTUAL_KEY);
            if (key == null || key.isBlank() || MetricTags.NONE.equals(key)) return;
            Map<String, Object> entry = byKey.computeIfAbsent(key, k -> new LinkedHashMap<>());
            double cur = ((Number) entry.getOrDefault("estimated_cost_usd", 0.0)).doubleValue();
            entry.put("estimated_cost_usd", Math.round((cur + c.count()) * 100_000.0) / 100_000.0);
        });
        return Map.copyOf(byKey);
    }

    private List<Map<String, Object>> collectTopModels(int limit) {
        Map<String, Long> modelCounts = new HashMap<>();
        meterRegistry.find(MetricNames.REQUESTS_TOTAL).counters().forEach(c -> {
            String model = c.getId().getTag(MetricTags.GEN_AI_REQUEST_MODEL);
            if (model == null || model.isBlank()) return;
            modelCounts.merge(model, (long) c.count(), Long::sum);
        });
        return modelCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .map(e -> Map.<String, Object>of("model", e.getKey(), "requests", e.getValue()))
            .toList();
    }

    // ── Configuration read ────────────────────────────────────────────────────

    /**
     * {@code GET /admin/config} — returns the current running configuration with all
     * credential values redacted. Env-var names (e.g., {@code api-key-env-var}) are
     * preserved so operators can diagnose misconfiguration without exposing secrets.
     */
    public Mono<ServerResponse> getConfig(ServerRequest request) {
        return Mono.fromCallable(this::buildSanitizedConfig)
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(responseFactory::ok)
            .onErrorResume(e -> {
                log.error("Failed to serialize config", e);
                return responseFactory.internalServerError(
                    "CONFIG_SERIALIZATION_ERROR", "Failed to retrieve configuration", null);
            });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSanitizedConfig() {
        // Build a sanitized representation of the running config
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", "0.1.0");
        config.put("provider_count", legateConfig.providers() != null ? legateConfig.providers().size() : 0);

        // Providers — include names/models/types but redact credentials
        if (legateConfig.providers() != null) {
            List<Map<String, Object>> providers = new ArrayList<>();
            for (var p : legateConfig.providers()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name",       p.name());
                entry.put("type",       p.type());
                entry.put("base_url",   p.baseUrl());
                entry.put("models",     p.models());
                entry.put("api_key_env_var", p.apiKeyEnvVar()); // env var NAME, not the value
                entry.put("weight",     p.weight());
                providers.add(entry);
            }
            config.put("providers", providers);
        }

        // Routing — safe to expose fully
        if (legateConfig.routing() != null) {
            Map<String, Object> routing = new LinkedHashMap<>();
            routing.put("default_chain",    legateConfig.routing().defaultChain());
            routing.put("aliases",          legateConfig.routing().aliases());
            routing.put("fallback_chains",  legateConfig.routing().fallbackChains() != null
                ? legateConfig.routing().fallbackChains().keySet() : Set.of());
            routing.put("load_balancer_strategy",
                legateConfig.routing().loadBalancer() != null
                    ? legateConfig.routing().loadBalancer().strategy() : "ROUND_ROBIN");
            config.put("routing", routing);
        }

        // Guards — config only, no credentials
        config.put("guards_enabled", legateConfig.guards() != null && legateConfig.guards().hasGuards());

        // Cache config
        if (legateConfig.cache() != null) {
            config.put("cache", Map.of(
                "enabled",  legateConfig.cache().enabled(),
                "max_size", legateConfig.cache().maxSize(),
                "ttl",      String.valueOf(legateConfig.cache().ttl())
            ));
        }

        // Admin — expose auth requirement but REDACT the token
        if (legateConfig.admin() != null) {
            config.put("admin", Map.of(
                "require_auth", legateConfig.admin().requireAuth(),
                "token",        "****"
            ));
        }

        return config;
    }

    // ── Cache management ──────────────────────────────────────────────────────

    /** {@code DELETE /admin/cache} — clears the response cache. */
    public Mono<ServerResponse> clearCache(ServerRequest request) {
        return Mono.fromCallable(() -> {
                responseCache.clear();
                return Map.of("status", "cleared");
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(responseFactory::ok);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Sums all counter values for the given metric name, optionally filtered by a tag.
     *
     * @param metricName the Micrometer metric name (see {@link MetricNames})
     * @param tagKey     tag key to filter by, or {@code null} for no tag filter
     * @param tagValue   tag value to filter by, or {@code null} for no tag filter
     */
    private double sumCounters(String metricName, String tagKey, String tagValue) {
        var search = meterRegistry.find(metricName);
        if (StringUtils.isNotBlank(tagKey) && StringUtils.isNotBlank(tagValue)) {
            search = search.tag(tagKey, tagValue);
        }
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private long activeRequests() {
        return Optional.ofNullable(meterRegistry.find(MetricNames.ACTIVE_REQUESTS).gauge())
            .map(g -> (long) g.value())
            .orElse(0L);
    }
}
