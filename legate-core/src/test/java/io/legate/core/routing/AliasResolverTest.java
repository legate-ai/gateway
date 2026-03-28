package io.legate.core.routing;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AliasResolverTest {

    @Test
    void resolve_whenAliasExists_returnsCanonicalModel() {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o", "fast", "gpt-4o-mini"));

        assertThat(resolver.resolve("smart")).isEqualTo("gpt-4o");
        assertThat(resolver.resolve("fast")).isEqualTo("gpt-4o-mini");
    }

    @Test
    void resolve_whenNoAliasExists_returnsInput() {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o"));

        assertThat(resolver.resolve("gpt-4o")).isEqualTo("gpt-4o");
        assertThat(resolver.resolve("claude-3-5-sonnet-20241022")).isEqualTo("claude-3-5-sonnet-20241022");
    }

    @Test
    void resolve_whenNull_returnsNull() {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o"));

        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    void resolve_withNoAliasMap_returnsInputUnchanged() {
        var resolver = new AliasResolver();

        assertThat(resolver.resolve("gpt-4o")).isEqualTo("gpt-4o");
        assertThat(resolver.resolve("some-model")).isEqualTo("some-model");
    }

    @Test
    void resolve_withNullAliasMap_treatedAsEmpty() {
        var resolver = new AliasResolver(null);

        assertThat(resolver.resolve("gpt-4o")).isEqualTo("gpt-4o");
    }

    @Test
    void isAlias_returnsTrueForConfiguredAlias() {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o", "claude", "claude-3-5-sonnet-20241022"));

        assertThat(resolver.isAlias("smart")).isTrue();
        assertThat(resolver.isAlias("claude")).isTrue();
    }

    @Test
    void isAlias_returnsFalseForNonAlias() {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o"));

        assertThat(resolver.isAlias("gpt-4o")).isFalse();
        assertThat(resolver.isAlias("unknown")).isFalse();
    }

    @Test
    void reload_atomicallyUpdatesAliases() {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o"));
        assertThat(resolver.resolve("smart")).isEqualTo("gpt-4o");

        resolver.reload(Map.of("smart", "claude-3-5-sonnet-20241022", "fast", "gpt-4o-mini"));

        assertThat(resolver.resolve("smart")).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(resolver.resolve("fast")).isEqualTo("gpt-4o-mini");
    }

    @Test
    void reload_withNull_clearsAllAliases() {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o"));

        resolver.reload(null);

        assertThat(resolver.resolve("smart")).isEqualTo("smart"); // no longer an alias
        assertThat(resolver.getAliases()).isEmpty();
    }

    @Test
    void getAliases_returnsImmutableSnapshot() {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o"));
        var aliases = resolver.getAliases();

        assertThat(aliases).containsEntry("smart", "gpt-4o");
    }

    @Test
    void resolve_isThreadSafe() throws InterruptedException {
        var resolver = new AliasResolver(Map.of("smart", "gpt-4o"));
        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        // Mix resolves and reloads
                        if (idx % 5 == 0) {
                            resolver.reload(Map.of("smart", "gpt-4o-mini"));
                        } else {
                            String result = resolver.resolve("smart");
                            // Should be either gpt-4o or gpt-4o-mini — both valid during concurrent reload
                            if (result == null) errors.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        assertThat(errors.get()).isEqualTo(0);
    }
}
