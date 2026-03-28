package io.legate.core.cache;

import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyTest {

    // ── isCacheable ────────────────────────────────────────────────────────────

    @Test
    void isCacheable_whenTemperatureZeroAndNotStreaming() {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.0)
            .stream(false)
            .build();

        assertThat(CacheKey.isCacheable(request)).isTrue();
    }

    @Test
    void isCacheable_whenTemperatureNullAndStreamNull() {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        assertThat(CacheKey.isCacheable(request)).isTrue();
    }

    @Test
    void notCacheable_whenStreamingEnabled() {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.0)
            .stream(true)
            .build();

        assertThat(CacheKey.isCacheable(request)).isFalse();
    }

    @Test
    void notCacheable_whenNonZeroTemperature() {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.7)
            .build();

        assertThat(CacheKey.isCacheable(request)).isFalse();
    }

    @Test
    void notCacheable_whenNIsGreaterThanOne() {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.0)
            .n(2)
            .build();

        assertThat(CacheKey.isCacheable(request)).isFalse();
    }

    @Test
    void notCacheable_whenNull() {
        assertThat(CacheKey.isCacheable(null)).isFalse();
    }

    // ── Hash consistency ───────────────────────────────────────────────────────

    @Test
    void sameRequest_producesSameHash() {
        var request1 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.0)
            .build();

        var request2 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.0)
            .build();

        assertThat(CacheKey.from(request1)).isEqualTo(CacheKey.from(request2));
    }

    @Test
    void differentModel_producesDifferentHash() {
        var request1 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        var request2 = ChatCompletionRequest.builder()
            .model("gpt-4o-mini")
            .messages(List.of(Message.user("Hello")))
            .build();

        assertThat(CacheKey.from(request1)).isNotEqualTo(CacheKey.from(request2));
    }

    @Test
    void differentContent_producesDifferentHash() {
        var request1 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        var request2 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Goodbye")))
            .build();

        assertThat(CacheKey.from(request1)).isNotEqualTo(CacheKey.from(request2));
    }

    @Test
    void differentMaxTokens_producesDifferentHash() {
        var request1 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(100)
            .build();

        var request2 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(200)
            .build();

        assertThat(CacheKey.from(request1)).isNotEqualTo(CacheKey.from(request2));
    }

    @Test
    void multipleMessages_hashIsStable() {
        var messages = List.of(
            Message.system("You are helpful"),
            Message.user("What is 2+2?")
        );

        var request1 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(messages)
            .build();

        var request2 = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(messages)
            .build();

        assertThat(CacheKey.from(request1)).isEqualTo(CacheKey.from(request2));
    }

    // ── Hash format ─────────────────────────────────────────────────────────────

    @Test
    void cacheKey_hashIsSha256Hex() {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        var key = CacheKey.from(request);

        assertThat(key.hash())
            .hasSize(64)  // SHA-256 = 32 bytes = 64 hex chars
            .matches("[0-9a-f]+");
    }

    // ── Equality and hashCode ─────────────────────────────────────────────────

    @Test
    void cacheKeys_withSameHash_areEqual() {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        var key1 = CacheKey.from(request);
        var key2 = CacheKey.from(request);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
    }
}
