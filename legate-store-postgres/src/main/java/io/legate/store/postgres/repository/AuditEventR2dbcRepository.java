package io.legate.store.postgres.repository;

import io.legate.store.postgres.entity.AuditEventEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data R2DBC repository for {@code audit_events}.
 *
 * <p>Provides standard CRUD operations (primarily {@code save} and {@code saveAll})
 * inherited from {@link R2dbcRepository}. Complex filtered queries that require
 * dynamic WHERE clause construction are handled by
 * {@link AuditEventFilterRepository} and its implementation
 * {@link AuditEventFilterRepositoryImpl}.</p>
 */
@Repository
public interface AuditEventR2dbcRepository
    extends R2dbcRepository<AuditEventEntity, String>, AuditEventFilterRepository {}
