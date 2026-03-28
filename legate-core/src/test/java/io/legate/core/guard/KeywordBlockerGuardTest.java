package io.legate.core.guard;

import io.legate.core.guard.builtin.KeywordBlockerGuard;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordBlockerGuardTest {

    private GuardContext ctx(String content) {
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user(content)))
            .build();
        return new GuardContext(request, null, Map.of(), "req_test");
    }

    // ── Blocking mode ──────────────────────────────────────────────────────────

    @Test
    void detectsKeyword_andBlocks() {
        var guard = new KeywordBlockerGuard(List.of("badword", "forbidden"), true, 200);
        var ctx = ctx("This contains a badword in the message");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
        assertThat(((GuardDecision.Block) decision).reason()).contains("badword");
    }

    @Test
    void detectsKeyword_caseInsensitive() {
        var guard = new KeywordBlockerGuard(List.of("badword"), true, 200);
        var ctx = ctx("This contains BADWORD in uppercase");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
    }

    @Test
    void detectsKeyword_mixedCase() {
        var guard = new KeywordBlockerGuard(List.of("BadWord"), true, 200);
        var ctx = ctx("The BaDwOrD is here");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
    }

    @Test
    void noKeywordMatch_allowsRequest() {
        var guard = new KeywordBlockerGuard(List.of("forbidden"), true, 200);
        var ctx = ctx("This is a completely clean message");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Allow.class);
    }

    // ── Warning mode ───────────────────────────────────────────────────────────

    @Test
    void detectsKeyword_andWarns() {
        var guard = new KeywordBlockerGuard(List.of("suspicious"), false, 200);
        var ctx = ctx("This is suspicious activity");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Warn.class);
        assertThat(((GuardDecision.Warn) decision).reason()).contains("suspicious");
    }

    // ── Empty keyword list ─────────────────────────────────────────────────────

    @Test
    void emptyKeywords_allowsAll() {
        var guard = new KeywordBlockerGuard(List.of(), true, 200);
        var ctx = ctx("Anything goes here");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Allow.class);
    }

    @Test
    void nullKeywords_allowsAll() {
        var guard = new KeywordBlockerGuard(null, true, 200);
        var ctx = ctx("Some content");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Allow.class);
    }

    // ── Multiple messages ──────────────────────────────────────────────────────

    @Test
    void detectsKeywordInAnyMessage() {
        var guard = new KeywordBlockerGuard(List.of("secret"), true, 200);
        List<Message> messages = List.of(
            Message.user("First message is clean"),
            Message.user("Second has a secret in it")
        );
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(messages)
            .build();
        var ctx = new GuardContext(request, null, Map.of(), "req_test");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
    }

    @Test
    void allMessagesClean_allowsRequest() {
        var guard = new KeywordBlockerGuard(List.of("secret"), true, 200);
        List<Message> messages = List.of(
            Message.user("First clean message"),
            Message.user("Second clean message")
        );
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(messages)
            .build();
        var ctx = new GuardContext(request, null, Map.of(), "req_test");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Allow.class);
    }

    // ── Null messages ──────────────────────────────────────────────────────────

    @Test
    void nullMessages_allowsRequest() {
        var guard = new KeywordBlockerGuard(List.of("badword"), true, 200);
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(null)
            .build();
        var ctx = new GuardContext(request, null, Map.of(), "req_test");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Allow.class);
    }

    // ── Guard metadata ─────────────────────────────────────────────────────────

    @Test
    void guardName_isKeywordBlocker() {
        var guard = new KeywordBlockerGuard(List.of("test"), true, 200);
        assertThat(guard.getName()).isEqualTo("keyword-blocker");
    }

    @Test
    void guardOrder_isConfigurable() {
        var guard = new KeywordBlockerGuard(List.of("test"), true, 150);
        assertThat(guard.getOrder()).isEqualTo(150);
    }

    // ── Substring match ────────────────────────────────────────────────────────

    @Test
    void keywordAsSubstring_isDetected() {
        var guard = new KeywordBlockerGuard(List.of("bomb"), true, 200);
        var ctx = ctx("I need to defuse the time bomb quickly");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
    }

    @Test
    void firstMatchingKeyword_triggersBlock() {
        var guard = new KeywordBlockerGuard(List.of("first", "second"), true, 200);
        var ctx = ctx("Contains the first keyword");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
        assertThat(((GuardDecision.Block) decision).reason()).contains("first");
    }
}
