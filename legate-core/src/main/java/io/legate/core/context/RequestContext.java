package io.legate.core.context;

import io.legate.core.guard.GuardDecision;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.ChatCompletionResponse;
import io.legate.core.model.Usage;
import io.legate.core.routing.RoutingDecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mutable context accumulating state throughout the request pipeline.
 * Each request gets a single RequestContext instance that is passed through
 * authentication, guards, routing, upstream calls, and telemetry.
 */
public class RequestContext {
    private final String requestId;
    private final Instant receivedAt;

    private VirtualKeyInfo virtualKeyInfo;
    private ChatCompletionRequest originalRequest;
    private ChatCompletionRequest effectiveRequest;
    private Map<String, String> requestHeaders = Map.of();
    private final List<GuardDecision> guardDecisions = new ArrayList<>();
    private RoutingDecision routingDecision;
    private boolean cacheHit;
    private int fallbackAttempts;
    private Instant upstreamCallStartedAt;
    private Instant upstreamCallCompletedAt;
    private ChatCompletionResponse response;
    private Usage usage;
    private BigDecimal estimatedCostUsd;
    private String errorCode;

    public RequestContext(String requestId) {
        this.requestId = requestId;
        this.receivedAt = Instant.now();
    }

    /**
     * Total latency from request received to completion.
     */
    public long getTotalLatencyMs() {
        Instant end = upstreamCallCompletedAt != null ? upstreamCallCompletedAt : Instant.now();
        return end.toEpochMilli() - receivedAt.toEpochMilli();
    }

    /**
     * Latency for the upstream provider call only.
     */
    public Long getUpstreamLatencyMs() {
        if (upstreamCallStartedAt == null || upstreamCallCompletedAt == null) {
            return null;
        }
        return upstreamCallCompletedAt.toEpochMilli() - upstreamCallStartedAt.toEpochMilli();
    }

    /**
     * Marks the start of an upstream call.
     */
    public void markUpstreamCallStarted() {
        this.upstreamCallStartedAt = Instant.now();
    }

    /**
     * Marks the completion of an upstream call.
     */
    public void markUpstreamCallCompleted() {
        this.upstreamCallCompletedAt = Instant.now();
    }

    /**
     * Increments the fallback attempt counter.
     */
    public void incrementFallbackAttempts() {
        this.fallbackAttempts++;
    }

    // Getters and setters

    public String getRequestId() {
        return requestId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public VirtualKeyInfo getVirtualKeyInfo() {
        return virtualKeyInfo;
    }

    public void setVirtualKeyInfo(VirtualKeyInfo virtualKeyInfo) {
        this.virtualKeyInfo = virtualKeyInfo;
    }

    public ChatCompletionRequest getOriginalRequest() {
        return originalRequest;
    }

    public void setOriginalRequest(ChatCompletionRequest originalRequest) {
        this.originalRequest = originalRequest;
        if (this.effectiveRequest == null) {
            this.effectiveRequest = originalRequest;
        }
    }

    public ChatCompletionRequest getEffectiveRequest() {
        return effectiveRequest;
    }

    public void setEffectiveRequest(ChatCompletionRequest effectiveRequest) {
        this.effectiveRequest = effectiveRequest;
    }

    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders != null ? requestHeaders : Map.of();
    }

    public List<GuardDecision> getGuardDecisions() {
        return guardDecisions;
    }

    public void addGuardDecision(GuardDecision decision) {
        this.guardDecisions.add(decision);
    }

    public RoutingDecision getRoutingDecision() {
        return routingDecision;
    }

    public void setRoutingDecision(RoutingDecision routingDecision) {
        this.routingDecision = routingDecision;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public int getFallbackAttempts() {
        return fallbackAttempts;
    }

    public ChatCompletionResponse getResponse() {
        return response;
    }

    public void setResponse(ChatCompletionResponse response) {
        this.response = response;
        if (response != null && response.usage() != null) {
            this.usage = response.usage();
        }
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public void setEstimatedCostUsd(BigDecimal estimatedCostUsd) {
        this.estimatedCostUsd = estimatedCostUsd;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
