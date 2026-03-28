package io.legate.store.postgres.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Spring Data R2DBC entity mapping the {@code audit_events} table.
 *
 * <p>JSONB columns ({@code details}) are mapped as {@code String} because
 * R2DBC does not natively deserialise PostgreSQL JSONB; conversion to
 * {@code Map<String, Object>} is handled by
 * {@link io.legate.store.postgres.mapper.AuditEventMapper}.</p>
 */
@Table("wdn_audit_events")
public record AuditEventEntity(
        @Id
        @Column("event_id")
        String eventId,
        @Column("timestamp")
        Instant timestamp,
        @Column("request_id")
        String requestId,
        @Column("virtual_key_id")
        String virtualKeyId,
        @Column("event_type")
        String eventType,
        @Column("description")
        String description,
        @Column("details")
        String detailsJson
) {
}
