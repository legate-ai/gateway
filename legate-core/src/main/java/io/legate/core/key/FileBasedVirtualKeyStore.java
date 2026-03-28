package io.legate.core.key;

import io.legate.core.context.VirtualKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * File-backed {@link VirtualKeyStore} that persists keys as YAML.
 *
 * <p>Keys are stored as SHA-256 hashes (prefix check) plus a bcrypt-style comparison.
 * For Phase 2 this uses SHA-256 hashing which is fast but not timing-safe for large
 * datasets. Phase 4 upgrades to bcrypt via the PostgreSQL store.</p>
 *
 * <p>Storage format ({@code legate-keys.yml}):</p>
 * <pre>{@code
 * keys:
 *   - key-id: wdn_key_abc123
 *     team-name: Team A
 *     key-hash: sha256:abc123...
 *     key-prefix: wdn_live_...
 *     created-at: 2024-01-01T00:00:00Z
 *     revoked: false
 *     allowed-models: [gpt-*, claude-*]
 *     denied-models: []
 * }</pre>
 *
 * <p>Thread-safe: all operations are guarded by a {@link ReadWriteLock}.</p>
 */
public class FileBasedVirtualKeyStore implements VirtualKeyStore {

    private static final Logger log = LoggerFactory.getLogger(FileBasedVirtualKeyStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX_LIVE = "wdn_live_";
    private static final String KEY_PREFIX_TEST = "wdn_test_";
    private static final int KEY_RANDOM_BYTES = 24; // 32 base64 chars

    private final Path storePath;
    private final VirtualKeyHasher hasher;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<KeyEntry> entries;

    /**
     * Creates or opens the store at the given path with the specified hasher.
     *
     * @param storePath path to the YAML key file (created if absent)
     * @param hasher    strategy for hashing and verifying key plaintexts
     */
    public FileBasedVirtualKeyStore(Path storePath, VirtualKeyHasher hasher) {
        this.storePath = storePath;
        this.hasher = hasher;
        this.entries = new CopyOnWriteArrayList<>(loadFromDisk());
        log.info("FileBasedVirtualKeyStore loaded {} key(s) from '{}'", entries.size(), storePath);
    }

    /**
     * Creates or opens the store at the given path using SHA-256 hashing.
     *
     * @param storePath path to the YAML key file (created if absent)
     */
    public FileBasedVirtualKeyStore(Path storePath) {
        this(storePath, new Sha256VirtualKeyHasher());
    }

    /**
     * Convenience constructor using {@code legate-keys.yml} in the working directory
     * with SHA-256 hashing.
     */
    public FileBasedVirtualKeyStore() {
        this(Path.of("legate-keys.yml"), new Sha256VirtualKeyHasher());
    }

    @Override
    public Optional<VirtualKeyInfo> resolve(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            for (KeyEntry e : entries) {
                if (!e.revoked && hasher.verify(rawKey, e.keyHash)) {
                    return Optional.of(toVirtualKeyInfo(e));
                }
            }
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public VirtualKeyCreateResult create(VirtualKeyCreateRequest request) {
        String rawKey = generateKey();
        String keyId = "wdn_key_" + randomHex(8);
        String hash = hasher.hash(rawKey);
        String prefix = rawKey.substring(0, Math.min(12, rawKey.length())) + "...";

        KeyEntry entry = new KeyEntry(
                keyId,
                request.teamName(),
                hash,
                prefix,
                Instant.now(),
                false,
                new ArrayList<>(request.allowedModels()),
                new ArrayList<>(request.deniedModels()),
                request.rateLimits() != null ? request.rateLimits().requestsPerMinute() : null,
                request.rateLimits() != null ? request.rateLimits().tokensPerDay() : null,
                request.spendLimits() != null ? request.spendLimits().dailyLimitUsd() : null,
                request.spendLimits() != null ? request.spendLimits().monthlyLimitUsd() : null
        );

        lock.writeLock().lock();
        try {
            entries.add(entry);
            saveToDisk();
        } finally {
            lock.writeLock().unlock();
        }

        log.info("Created virtual key '{}' for team '{}' (prefix: {})",
                keyId, request.teamName(), prefix);
        return new VirtualKeyCreateResult(keyId, rawKey);
    }

    @Override
    public boolean revoke(String keyId) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < entries.size(); i++) {
                KeyEntry e = entries.get(i);
                if (e.keyId.equals(keyId)) {
                    entries.set(i, e.asRevoked());
                    saveToDisk();
                    log.info("Revoked virtual key '{}'", keyId);
                    return true;
                }
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<VirtualKeySummary> list() {
        lock.readLock().lock();
        try {
            return entries.stream()
                    .map(e -> new VirtualKeySummary(
                            e.keyId, e.teamName, e.keyPrefix, e.createdAt, e.revoked,
                            List.copyOf(e.allowedModels), List.copyOf(e.deniedModels)))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private VirtualKeyInfo toVirtualKeyInfo(KeyEntry e) {
        VirtualKeyInfo.RateLimitInfo rl = (e.requestsPerMinute != null || e.tokensPerDay != null)
                ? new VirtualKeyInfo.RateLimitInfo(e.requestsPerMinute, e.tokensPerDay)
                : null;
        VirtualKeyInfo.SpendLimitInfo sl = (e.dailyLimitUsd != null || e.monthlyLimitUsd != null)
                ? new VirtualKeyInfo.SpendLimitInfo(e.dailyLimitUsd, e.monthlyLimitUsd)
                : null;
        return new VirtualKeyInfo(
                e.keyId, e.teamName,
                List.copyOf(e.allowedModels),
                List.copyOf(e.deniedModels),
                rl, sl, Map.of()
        );
    }

    private String generateKey() {
        byte[] bytes = new byte[KEY_RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX_LIVE + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private void saveToDisk() {
        try {
            StringBuilder sb = new StringBuilder("keys:\n");
            for (KeyEntry e : entries) {
                sb.append("  - key-id: ").append(e.keyId).append("\n");
                sb.append("    team-name: ").append(quote(e.teamName)).append("\n");
                sb.append("    key-hash: ").append(e.keyHash).append("\n");
                sb.append("    key-prefix: ").append(e.keyPrefix).append("\n");
                sb.append("    created-at: ").append(e.createdAt).append("\n");
                sb.append("    revoked: ").append(e.revoked).append("\n");
                sb.append("    allowed-models: ").append(yamlList(e.allowedModels)).append("\n");
                sb.append("    denied-models: ").append(yamlList(e.deniedModels)).append("\n");
                if (e.requestsPerMinute != null) {
                    sb.append("    requests-per-minute: ").append(e.requestsPerMinute).append("\n");
                }
                if (e.tokensPerDay != null) {
                    sb.append("    tokens-per-day: ").append(e.tokensPerDay).append("\n");
                }
                if (e.dailyLimitUsd != null) {
                    sb.append("    daily-limit-usd: ").append(e.dailyLimitUsd).append("\n");
                }
                if (e.monthlyLimitUsd != null) {
                    sb.append("    monthly-limit-usd: ").append(e.monthlyLimitUsd).append("\n");
                }
            }
            Files.writeString(storePath, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            log.error("Failed to persist virtual keys to '{}'", storePath, ex);
        }
    }

    private List<KeyEntry> loadFromDisk() {
        if (!Files.exists(storePath)) {
            return new ArrayList<>();
        }
        try {
            List<String> lines = Files.readAllLines(storePath);
            return parseYaml(lines);
        } catch (IOException ex) {
            log.warn("Could not load virtual keys from '{}': {}", storePath, ex.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Minimal hand-rolled YAML parser for the key file format.
     * Avoids a Jackson/SnakeYAML dependency in legate-core.
     */
    private List<KeyEntry> parseYaml(List<String> lines) {
        List<KeyEntry> result = new ArrayList<>();
        Map<String, String> current = null;
        for (String raw : lines) {
            String line = raw.stripLeading();
            if (line.startsWith("- key-id:")) {
                if (current != null) {
                    result.add(entryFrom(current));
                }
                current = new LinkedHashMap<>();
                current.put("key-id", value(line, "key-id:"));
            } else if (current != null) {
                for (String key : List.of("team-name", "key-hash", "key-prefix", "created-at",
                        "revoked", "allowed-models", "denied-models", "requests-per-minute",
                        "tokens-per-day", "daily-limit-usd", "monthly-limit-usd")) {
                    if (line.startsWith(key + ":")) {
                        current.put(key, value(line, key + ":"));
                        break;
                    }
                }
            }
        }
        if (current != null) {
            result.add(entryFrom(current));
        }
        return result;
    }

    private KeyEntry entryFrom(Map<String, String> m) {
        return new KeyEntry(
                m.getOrDefault("key-id", ""),
                m.getOrDefault("team-name", ""),
                m.getOrDefault("key-hash", ""),
                m.getOrDefault("key-prefix", ""),
                parseInstant(m.get("created-at")),
                Boolean.parseBoolean(m.getOrDefault("revoked", "false")),
                parseList(m.get("allowed-models")),
                parseList(m.get("denied-models")),
                parseInt(m.get("requests-per-minute")),
                parseInt(m.get("tokens-per-day")),
                parseDecimal(m.get("daily-limit-usd")),
                parseDecimal(m.get("monthly-limit-usd"))
        );
    }

    private static String value(String line, String prefix) {
        int idx = line.indexOf(prefix);
        if (idx < 0) {
            return "";
        }
        return line.substring(idx + prefix.length()).trim().replaceAll("^['\"]|['\"]$", "");
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse timestamp '{}', defaulting to now", s);
            return Instant.now();
        }
    }

    private static List<String> parseList(String s) {
        if (s == null || s.isBlank() || s.equals("[]")) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(s.replaceAll("[\\[\\]]", "").split(",\\s*")));
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String quote(String s) {
        return (s == null) ? "''" : "'" + s.replace("'", "''") + "'";
    }

    private static String yamlList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        return "[" + String.join(", ", list) + "]";
    }

    // -------------------------------------------------------------------------
    // Inner record for internal key representation
    // -------------------------------------------------------------------------

    private record KeyEntry(
            String keyId,
            String teamName,
            String keyHash,
            String keyPrefix,
            Instant createdAt,
            boolean revoked,
            List<String> allowedModels,
            List<String> deniedModels,
            Integer requestsPerMinute,
            Integer tokensPerDay,
            BigDecimal dailyLimitUsd,
            BigDecimal monthlyLimitUsd
    ) {
        KeyEntry asRevoked() {
            return new KeyEntry(keyId, teamName, keyHash, keyPrefix, createdAt, true,
                    allowedModels, deniedModels, requestsPerMinute, tokensPerDay,
                    dailyLimitUsd, monthlyLimitUsd);
        }
    }
}
