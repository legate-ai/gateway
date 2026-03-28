package io.legate.store.postgres.repository;

import io.legate.core.audit.AuditQuery;
import io.legate.store.postgres.entity.AuditEventEntity;
import org.jspecify.annotations.NonNull;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Custom implementation of {@link AuditEventFilterRepository} using
 * {@link DatabaseClient} for dynamic, parameterized WHERE clause construction.
 *
 * <p>Spring Data discovers this class automatically by convention
 * ({@code AuditEventR2dbcRepository} + {@code Impl} suffix on the fragment name).
 * It is not a {@code @Component} — Spring Data wires it as part of the
 * repository infrastructure.</p>
 *
 * <p>All bind variables use named parameters ({@code :name}) rather than
 * positional placeholders to keep the query readable when optional clauses
 * are added or removed at runtime.</p>
 */
public class AuditEventFilterRepositoryImpl implements AuditEventFilterRepository {

    private static final String BASE_QUERY =
            "SELECT event_id, timestamp, request_id, virtual_key_id, event_type, description, details " +
                    "FROM audit_events " +
                    "WHERE 1 = 1";

    private static final String FILTER_FROM = " AND timestamp >= :from";
    private static final String FILTER_TO = " AND timestamp <= :to";
    private static final String FILTER_VIRTUAL_KEY = " AND virtual_key_id = :virtualKeyId";
    private static final String FILTER_EVENT_TYPE = " AND event_type = :eventType";
    private static final String ORDER_AND_PAGINATION = " ORDER BY timestamp DESC LIMIT :limit OFFSET :offset";

    private final DatabaseClient databaseClient;

    public AuditEventFilterRepositoryImpl(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Flux<AuditEventEntity> findByFilters(AuditQuery query) {
        StringBuilder baseQuery = getBaseQuery(query);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(baseQuery.toString());

        if (query.from() != null) {
            spec = spec.bind("from", query.from());
        }
        if (query.to() != null) {
            spec = spec.bind("to", query.to());
        }
        if (query.virtualKeyId() != null) {
            spec = spec.bind("virtualKeyId", query.virtualKeyId());
        }
        if (query.type() != null) {
            spec = spec.bind("eventType", query.type().name());
        }

        spec = spec.bind("limit", query.limit())
                .bind("offset", query.offset());

        return spec.fetch()
                .all()
                .map(this::mapRow);
    }

    private static @NonNull StringBuilder getBaseQuery(AuditQuery query) {
        StringBuilder stringBuilder = new StringBuilder(BASE_QUERY);

        if (query.from() != null) {
            stringBuilder.append(FILTER_FROM);
        }
        if (query.to() != null) {
            stringBuilder.append(FILTER_TO);
        }
        if (query.virtualKeyId() != null) {
            stringBuilder.append(FILTER_VIRTUAL_KEY);
        }
        if (query.type() != null) {
            stringBuilder.append(FILTER_EVENT_TYPE);
        }
        stringBuilder.append(ORDER_AND_PAGINATION);
        return stringBuilder;
    }

    private AuditEventEntity mapRow(Map<String, Object> row) {
        return new AuditEventEntity(
                (String) row.get("event_id"),
                toInstant(row.get("timestamp")),
                (String) row.get("request_id"),
                (String) row.get("virtual_key_id"),
                (String) row.get("event_type"),
                (String) row.getOrDefault("description", ""),
                (String) row.getOrDefault("details", "{}")
        );
    }

    private static Instant toInstant(Object val) {
        return switch (val) {
            case Instant instant -> instant;
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case LocalDateTime localDateTime -> localDateTime.toInstant(java.time.ZoneOffset.UTC);
            case null, default -> Instant.now();
        };
    }
}
