package io.legate.store.postgres.repository;

import io.legate.store.postgres.entity.VirtualKeyEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Spring Data R2DBC repository for the {@code virtual_keys} table.
 */
@Repository
public interface VirtualKeyR2dbcRepository extends R2dbcRepository<VirtualKeyEntity, String> {

    /**
     * Looks up an active (non-revoked) virtual key by its SHA-256 hash.
     *
     * <p>This is the hot path for every authenticated request. Callers should
     * wrap the result with a local Caffeine cache to avoid a DB round-trip per
     * request.</p>
     *
     * @param keyHash SHA-256 hex digest of the raw bearer token
     * @return the matching entity, or empty if not found or revoked
     */
    Mono<VirtualKeyEntity> findByKeyHashAndRevokedFalse(String keyHash);

    /**
     * Soft-deletes a virtual key by setting {@code revoked = TRUE} and
     * recording the revocation timestamp.
     *
     * @param keyId the key ID to revoke
     * @return the number of rows updated (0 if not found or already revoked)
     */
    @Modifying
    @Query("""
        UPDATE virtual_keys
           SET revoked    = TRUE,
               revoked_at = NOW()
         WHERE key_id  = :keyId
           AND revoked = FALSE
        """)
    Mono<Long> revokeById(String keyId);
}
