package io.legate.store.postgres.repository;

import io.legate.core.audit.AuditQuery;
import io.legate.store.postgres.entity.AuditEventEntity;
import reactor.core.publisher.Flux;

/**
 * Custom repository fragment for dynamic audit event filtering.
 *
 * <p>Implemented by {@link AuditEventFilterRepositoryImpl} using
 * {@link org.springframework.r2dbc.core.DatabaseClient} to build
 * parameterized queries with optional WHERE clauses. Spring Data
 * discovers the implementation via the {@code Impl} naming convention.</p>
 */
public interface AuditEventFilterRepository {

    /**
     * Finds audit events matching the supplied query filters.
     *
     * <p>All filter fields on {@link AuditQuery} are optional; {@code null}
     * values are excluded from the WHERE clause rather than matching {@code NULL}
     * rows. Results are ordered by {@code timestamp DESC} and paginated via
     * {@code limit}/{@code offset}.</p>
     *
     * @param query the filter and pagination parameters
     * @return a reactive stream of matching entities
     */
    Flux<AuditEventEntity> findByFilters(AuditQuery query);
}
