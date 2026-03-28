package io.legate.store.postgres.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Spring Data R2DBC entity mapping the {@code spend_tracking} table.
 *
 * <p>The {@code spend_tracking} table has a composite primary key
 * ({@code virtual_key_id}, {@code date}). Spring Data R2DBC requires a single
 * {@code @Id} column; this entity uses {@code virtual_key_id} as the surrogate
 * identity for read operations. Write operations (upsert) bypass this entity
 * and use {@link org.springframework.r2dbc.core.DatabaseClient} with a
 * native {@code ON CONFLICT} statement.</p>
 */
@Table("wdn_spend_tracking")
public record SpendTrackingEntity(
        @Id
        @Column("virtual_key_id")
        String virtualKeyId,
        @Column("date")
        LocalDate date,
        @Column("daily_spend_usd")
        BigDecimal dailySpendUsd,
        @Column("updated_at")
        Instant updatedAt
) {
}
