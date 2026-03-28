package io.legate.store.postgres.repository;

import io.legate.store.postgres.entity.RequestLogEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data R2DBC repository for the {@code request_log} table.
 *
 * <p>Only {@code saveAll()} (batch insert) is used at runtime. The standard
 * {@link R2dbcRepository} contract provides it without additional declarations.</p>
 */
@Repository
public interface RequestLogR2dbcRepository extends R2dbcRepository<RequestLogEntity, String> {}
