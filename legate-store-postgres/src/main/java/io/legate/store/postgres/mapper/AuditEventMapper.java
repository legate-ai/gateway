package io.legate.store.postgres.mapper;

import io.legate.core.audit.AuditEvent;
import io.legate.core.audit.AuditEventType;
import io.legate.store.postgres.entity.AuditEventEntity;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Converts between {@link AuditEvent} domain objects and {@link AuditEventEntity}
 * persistence entities.
 *
 * <p>Handles the JSONB ↔ {@code Map} conversion for the {@code details} column
 * and guards against {@code null} or malformed data from the database without
 * throwing — malformed rows fall back to safe empty defaults and are logged.</p>
 */
@Component
public class AuditEventMapper {

    private static final Logger log = LoggerFactory.getLogger(AuditEventMapper.class);

    private final ObjectMapper objectMapper;

    public AuditEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts a domain {@link AuditEvent} to its persistence entity for storage.
     *
     * @param event the domain object; must not be {@code null}
     * @return a fully populated entity ready for {@code save()} or {@code saveAll()}
     */
    public AuditEventEntity toEntity(AuditEvent event) {
        return new AuditEventEntity(
                event.eventId(),
                event.timestamp(),
                event.requestId(),
                event.virtualKeyId(),
                event.type().name(),
                event.description(),
                toJson(event.details())
        );
    }

    /**
     * Converts a persistence entity to the domain {@link AuditEvent}.
     *
     * @param entity the entity read from the database; must not be {@code null}
     * @return the domain object
     */
    public AuditEvent toDomain(AuditEventEntity entity) {
        return new AuditEvent(
                entity.eventId(),
                entity.timestamp(),
                StringUtils.defaultIfBlank(entity.requestId(), null),
                StringUtils.defaultIfBlank(entity.virtualKeyId(), null),
                parseEventType(entity.eventType()),
                StringUtils.defaultString(entity.description()),
                parseDetails(entity.detailsJson())
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("AuditEventMapper: failed to serialise details to JSON", e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDetails(String json) {
        if (StringUtils.isBlank(json) || "{}".equals(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("AuditEventMapper: failed to parse details JSON '{}' — defaulting to empty map", json);
            return Map.of();
        }
    }

    private AuditEventType parseEventType(String raw) {
        try {
            return AuditEventType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("AuditEventMapper: unknown event_type '{}' — defaulting to CONFIG_RELOADED", raw);
            return AuditEventType.CONFIG_RELOADED;
        }
    }
}
