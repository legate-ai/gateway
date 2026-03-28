package io.legate.store.postgres;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.key.VirtualKeyCreateRequest;
import io.legate.core.key.VirtualKeyCreateResult;
import io.legate.core.key.VirtualKeySummary;
import io.legate.core.key.VirtualKeyStore;
import io.legate.store.postgres.entity.VirtualKeyEntity;
import io.legate.store.postgres.mapper.VirtualKeyMapper;
import io.legate.store.postgres.repository.VirtualKeyR2dbcRepository;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed {@link VirtualKeyStore} using Spring Data R2DBC.
 *
 * <h3>Key security</h3>
 * <p>Plaintext keys are never stored. The raw bearer token is hashed with
 * SHA-256 and only the hex digest is persisted. On each {@link #resolve} call
 * the incoming token is hashed and matched against stored digests.</p>
 *
 * <h3>Performance</h3>
 * <p>A Caffeine cache (60-second TTL, 10,000 entries) holds the verified
 * {@link VirtualKeyInfo} objects keyed by the SHA-256 digest. This avoids a DB
 * round-trip on every authenticated request while keeping the cache small and
 * self-expiring.</p>
 *
 * <h3>Threading</h3>
 * <p>All {@code .block()} calls are invoked from Virtual Threads (the handler
 * pipeline executes synchronous code on the Virtual Thread scheduler).</p>
 */
public class PostgresVirtualKeyStore implements VirtualKeyStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresVirtualKeyStore.class);

    private static final String KEY_PREFIX = "wdn_";
    private static final int KEY_RANDOM_BYTES = 32;
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX_SIZE = 10_000;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final VirtualKeyR2dbcRepository virtualKeyR2dbcRepository;
    private final VirtualKeyMapper virtualKeyMapper;
    private final ObjectMapper objectMapper;

    /**
     * SHA-256(rawKey) → VirtualKeyInfo cache to skip DB on hot path.
     */
    private final Cache<String, VirtualKeyInfo> keyCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(CACHE_MAX_SIZE)
            .build();

    public PostgresVirtualKeyStore(
            VirtualKeyR2dbcRepository virtualKeyR2dbcRepository,
            VirtualKeyMapper virtualKeyMapper,
            ObjectMapper objectMapper
    ) {
        this.virtualKeyR2dbcRepository = Validate.notNull(virtualKeyR2dbcRepository, "repository must not be null");
        this.virtualKeyMapper = Validate.notNull(virtualKeyMapper, "mapper must not be null");
        this.objectMapper = Validate.notNull(objectMapper, "objectMapper must not be null");
    }

    // ── VirtualKeyStore ───────────────────────────────────────────────────────

    @Override
    public Optional<VirtualKeyInfo> resolve(String rawKey) {
        if (StringUtils.isBlank(rawKey)) {
            return Optional.empty();
        }

        String hash = sha256(rawKey);
        VirtualKeyInfo cached = keyCache.getIfPresent(hash);
        if (cached != null) {
            return Optional.of(cached);
        }

        try {
            VirtualKeyEntity entity = virtualKeyR2dbcRepository
                    .findByKeyHashAndRevokedFalse(hash)
                    .block(BLOCK_TIMEOUT);

            if (entity == null) {
                return Optional.empty();
            }

            VirtualKeyInfo info = virtualKeyMapper.toKeyInfo(entity);
            keyCache.put(hash, info);
            return Optional.of(info);
        } catch (Exception e) {
            log.error("PostgresVirtualKeyStore.resolve failed", e);
            return Optional.empty();
        }
    }

    @Override
    public VirtualKeyCreateResult create(VirtualKeyCreateRequest request) {
        Validate.notNull(request, "request must not be null");
        Validate.notBlank(request.teamName(), "teamName must not be blank");

        String keyId = "wdn_key_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String rawKey = KEY_PREFIX + generateSecureToken();
        String hash = sha256(rawKey);

        VirtualKeyEntity entity = new VirtualKeyEntity(
                keyId,
                hash,
                request.teamName(),
                toArray(request.allowedModels()),
                toArray(request.deniedModels()),
                toJson(request.rateLimits()),
                toJson(request.spendLimits()),
                toJson(request.metadata()),
                false,
                Instant.now(),
                null
        );

        try {
            virtualKeyR2dbcRepository.save(entity).block(BLOCK_TIMEOUT);
            return new VirtualKeyCreateResult(keyId, rawKey);
        } catch (Exception e) {
            log.error("Failed to create virtual key for team '{}'", request.teamName(), e);
            throw new RuntimeException("Virtual key creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean revoke(String keyId) {
        Validate.notBlank(keyId, "keyId must not be blank");
        try {
            Long rowsUpdated = virtualKeyR2dbcRepository.revokeById(keyId).block(BLOCK_TIMEOUT);
            boolean revoked = rowsUpdated != null && rowsUpdated > 0;
            if (revoked) {
                invalidateCacheForKey(keyId);
            }
            return revoked;
        } catch (Exception e) {
            log.error("Failed to revoke virtual key '{}'", keyId, e);
            return false;
        }
    }

    @Override
    public List<VirtualKeySummary> list() {
        try {
            return virtualKeyR2dbcRepository.findAll()
                    .map(virtualKeyMapper::toSummary)
                    .collectList()
                    .block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.error("Failed to list virtual keys", e);
            return List.of();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void invalidateCacheForKey(String keyId) {
        keyCache.asMap().entrySet().removeIf(e -> e.getValue().keyId().equals(keyId));
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[KEY_RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String[] toArray(List<String> list) {
        return (list != null) ? list.toArray(String[]::new) : new String[0];
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable on this JVM", e);
        }
    }
}
