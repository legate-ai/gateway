package io.legate.store.postgres.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Spring Data R2DBC entity mapping the {@code virtual_keys} table.
 *
 * <p>PostgreSQL {@code text[]} array columns ({@code allowed_models},
 * {@code denied_models}) are mapped as {@code String[]} — the r2dbc-postgresql
 * driver natively returns text arrays as {@code String[]}. Conversion to
 * {@code List<String>} is handled by
 * {@link io.legate.store.postgres.mapper.VirtualKeyMapper}.</p>
 *
 * <p>JSONB columns ({@code rate_limits}, {@code spend_limits}, {@code metadata})
 * are mapped as {@code String} and deserialised by the mapper.</p>
 */
@Table("wdn_virtual_keys")
public record VirtualKeyEntity(
        @Id
        @Column("key_id")
        String keyId,
        @Column("key_hash")
        String keyHash,
        @Column("team_name")
        String teamName,
        @Column("allowed_models")
        String[] allowedModels,
        @Column("denied_models")
        String[] deniedModels,
        @Column("rate_limits")
        String rateLimitsJson,
        @Column("spend_limits")
        String spendLimitsJson,
        @Column("metadata")
        String metadataJson,
        @Column("revoked")
        boolean revoked,
        @Column("created_at")
        Instant createdAt,
        @Column("revoked_at")
        Instant revokedAt
) {
}
