package io.legate.store.postgres.repository;

import io.legate.store.postgres.entity.SpendTrackingEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

/**
 * Spring Data R2DBC repository for the {@code spend_tracking} table.
 *
 * <p>Write operations (daily upsert) are handled outside this repository using
 * a native {@code INSERT … ON CONFLICT … DO UPDATE} statement executed via
 * {@link org.springframework.r2dbc.core.DatabaseClient}, because Spring Data
 * CRUD {@code save()} cannot express upsert semantics on a composite-keyed table.</p>
 */
@Repository
public interface SpendTrackingR2dbcRepository extends R2dbcRepository<SpendTrackingEntity, String> {

    /**
     * Loads all spend records for the given calendar day.
     * Used at startup to seed the in-memory spend accumulators.
     *
     * @param date the UTC calendar date
     * @return spend records for every key that had activity on {@code date}
     */
    @Query("SELECT * FROM spend_tracking WHERE date = :date")
    Flux<SpendTrackingEntity> findByDate(LocalDate date);
}
