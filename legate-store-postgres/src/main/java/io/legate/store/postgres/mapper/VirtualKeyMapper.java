package io.legate.store.postgres.mapper;

import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.key.VirtualKeySummary;
import io.legate.store.postgres.entity.VirtualKeyEntity;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Converts between {@link VirtualKeyEntity} persistence entities and
 * the domain objects {@link VirtualKeyInfo} and {@link VirtualKeySummary}.
 *
 * <p>Handles the PostgreSQL {@code text[]} array columns and JSONB columns
 * ({@code rate_limits}, {@code spend_limits}, {@code metadata}) that the
 * R2DBC driver cannot automatically convert to domain types.</p>
 */
@Component
public class VirtualKeyMapper {

    private static final Logger log = LoggerFactory.getLogger(VirtualKeyMapper.class);

    /**
     * Characters shown from the key ID in public summaries.
     */
    private static final int KEY_ID_PREFIX_LENGTH = 12;

    private final ObjectMapper objectMapper;

    public VirtualKeyMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Maps a {@link VirtualKeyEntity} to the full domain {@link VirtualKeyInfo}
     * used throughout the request pipeline.
     *
     * @param entity the entity from the database; must not be {@code null}
     * @return fully populated domain object
     */
    public VirtualKeyInfo toKeyInfo(VirtualKeyEntity entity) {
        return new VirtualKeyInfo(
                entity.keyId(),
                entity.teamName(),
                toList(entity.allowedModels()),
                toList(entity.deniedModels()),
                parseRateLimits(entity.rateLimitsJson()),
                parseSpendLimits(entity.spendLimitsJson()),
                parseMeta(entity.metadataJson())
        );
    }

    /**
     * Maps a {@link VirtualKeyEntity} to a {@link VirtualKeySummary} for the
     * admin list API (no secret values, only a key prefix for identification).
     *
     * @param entity the entity from the database; must not be {@code null}
     * @return summary suitable for returning in API responses
     */
    public VirtualKeySummary toSummary(VirtualKeyEntity entity) {
        String prefix = entity.keyId().length() >= KEY_ID_PREFIX_LENGTH
                ? entity.keyId().substring(0, KEY_ID_PREFIX_LENGTH) + "…"
                : entity.keyId();

        return new VirtualKeySummary(
                entity.keyId(),
                entity.teamName(),
                prefix,
                entity.createdAt(),
                entity.revoked(),
                toList(entity.allowedModels()),
                toList(entity.deniedModels())
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<String> toList(String[] array) {
        return (array != null) ? Arrays.asList(array) : List.of();
    }

    private VirtualKeyInfo.RateLimitInfo parseRateLimits(String json) {
        if (StringUtils.isBlank(json) || "null".equals(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            int requestsPerMinute = node.path("requestsPerMinute").asInt(0);
            int tokensPerDay = node.path("tokensPerDay").asInt(0);
            return (requestsPerMinute > 0 || tokensPerDay > 0)
                    ? new VirtualKeyInfo.RateLimitInfo(requestsPerMinute > 0 ? requestsPerMinute : null, tokensPerDay > 0 ? tokensPerDay : null)
                    : null;
        } catch (Exception e) {
            log.warn("VirtualKeyMapper: failed to parse rate_limits JSON — ignoring", e);
            return null;
        }
    }

    private VirtualKeyInfo.SpendLimitInfo parseSpendLimits(String json) {
        if (StringUtils.isBlank(json) || "null".equals(json) || "{}".equals(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            BigDecimal daily = node.has("dailyLimitUsd") ? new BigDecimal(node.get("dailyLimitUsd").asText()) : null;
            BigDecimal monthly = node.has("monthlyLimitUsd") ? new BigDecimal(node.get("monthlyLimitUsd").asText()) : null;
            return (daily != null || monthly != null)
                    ? new VirtualKeyInfo.SpendLimitInfo(daily, monthly)
                    : null;
        } catch (Exception e) {
            log.warn("VirtualKeyMapper: failed to parse spend_limits JSON — ignoring", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseMeta(String json) {
        if (StringUtils.isBlank(json) || "{}".equals(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("VirtualKeyMapper: failed to parse metadata JSON — defaulting to empty map", e);
            return Map.of();
        }
    }
}
