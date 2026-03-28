package io.legate.store.postgres.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Spring Data R2DBC entity mapping the {@code request_log} table.
 */
@Table("wdn_request_log")
public record RequestLogEntity(
        @Id
        @Column("request_id")
        String requestId,
        @Column("timestamp")
        Instant timestamp,
        @Column("virtual_key_id")
        String virtualKeyId,
        @Column("team_name")
        String teamName,
        @Column("requested_model")
        String requestedModel,
        @Column("actual_model")
        String actualModel,
        @Column("provider")
        String provider,
        @Column("input_tokens")
        Integer inputTokens,
        @Column("output_tokens")
        Integer outputTokens,
        @Column("estimated_cost_usd")
        BigDecimal estimatedCostUsd,
        @Column("total_latency_ms")
        Long totalLatencyMs,
        @Column("upstream_latency_ms")
        Long upstreamLatencyMs,
        @Column("cache_hit")
        boolean cacheHit,
        @Column("fallback_attempts")
        int fallbackAttempts,
        @Column("success")
        boolean success,
        @Column("error_code")
        String errorCode
) {
}
