package io.legate.core.guard;

import io.legate.core.config.guard.PiiAction;
import io.legate.core.config.guard.PiiDetectorConfig;
import io.legate.core.config.guard.PiiPattern;
import io.legate.core.guard.builtin.PiiDetectorGuard;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiDetectorGuardTest {

    private GuardContext ctx(String... messages) {
        List<Message> msgs = List.of(messages).stream().map(Message::user).toList();
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(msgs)
            .build();
        return new GuardContext(request, null, Map.of(), "req_test");
    }

    // ── Email detection ────────────────────────────────────────────────────────

    @Test
    void detectsEmail_andBlocks() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.blockAll(), 100);
        var ctx = ctx("My email is user@example.com please help me");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
        assertThat(((GuardDecision.Block) decision).reason()).contains("email");
    }

    @Test
    void detectsEmail_andRedacts() {
        var config = new PiiDetectorConfig(PiiAction.REDACT, List.of(PiiPattern.EMAIL), List.of());
        var guard = new PiiDetectorGuard(config, 100);
        var ctx = ctx("Contact me at user@example.com");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Modify.class);
        var modify = (GuardDecision.Modify) decision;
        String redactedContent = modify.modifiedRequest().messages().get(0).content();
        assertThat(redactedContent).contains("[EMAIL]");
        assertThat(redactedContent).doesNotContain("user@example.com");
    }

    @Test
    void detectsEmail_andWarns() {
        var config = new PiiDetectorConfig(PiiAction.WARN, List.of(PiiPattern.EMAIL), List.of());
        var guard = new PiiDetectorGuard(config, 100);
        var ctx = ctx("My email: test@test.com");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Warn.class);
    }

    // ── Phone detection ────────────────────────────────────────────────────────

    @Test
    void detectsPhone_andBlocks() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.blockAll(), 100);
        var ctx = ctx("Call me at 555-123-4567");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
        assertThat(((GuardDecision.Block) decision).reason()).contains("phone");
    }

    @Test
    void detectsPhone_andRedacts() {
        var config = new PiiDetectorConfig(PiiAction.REDACT, List.of(PiiPattern.PHONE), List.of());
        var guard = new PiiDetectorGuard(config, 100);
        var ctx = ctx("My number is (555) 123-4567");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Modify.class);
        String content = ((GuardDecision.Modify) decision).modifiedRequest().messages().get(0).content();
        assertThat(content).contains("[PHONE]");
        assertThat(content).doesNotContain("555");
    }

    // ── SSN detection ──────────────────────────────────────────────────────────

    @Test
    void detectsSSN_andBlocks() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.blockAll(), 100);
        var ctx = ctx("My SSN is 123-45-6789");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
        assertThat(((GuardDecision.Block) decision).reason()).contains("ssn");
    }

    // ── Credit card detection ──────────────────────────────────────────────────

    @Test
    void detectsCreditCard_andBlocks() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.blockAll(), 100);
        var ctx = ctx("Card number 4111111111111111"); // Visa test number

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
        assertThat(((GuardDecision.Block) decision).reason()).contains("credit-card");
    }

    // ── No PII ─────────────────────────────────────────────────────────────────

    @Test
    void noPhiDetected_allowsRequest() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.blockAll(), 100);
        var ctx = ctx("What is the capital of France?");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Allow.class);
    }

    // ── Multiple PII types ─────────────────────────────────────────────────────

    @Test
    void multiplePiiTypes_detectedTogether() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.blockAll(), 100);
        var ctx = ctx("Email: test@test.com, Phone: 555-123-4567");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
        String reason = ((GuardDecision.Block) decision).reason();
        assertThat(reason).contains("email");
        assertThat(reason).contains("phone");
    }

    @Test
    void multiplePiiTypes_allRedacted() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.redactAll(), 100);
        var ctx = ctx("Email: test@test.com, SSN: 123-45-6789");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Modify.class);
        String content = ((GuardDecision.Modify) decision).modifiedRequest().messages().get(0).content();
        assertThat(content).contains("[EMAIL]");
        assertThat(content).contains("[SSN]");
    }

    // ── Empty messages ─────────────────────────────────────────────────────────

    @Test
    void emptyMessages_allowedAutomatically() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.blockAll(), 100);
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of())
            .build();
        var ctx = new GuardContext(request, null, Map.of(), "req_test");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Allow.class);
    }

    // ── Guard metadata ─────────────────────────────────────────────────────────

    @Test
    void guardName_isPiiDetector() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.defaults(), 100);
        assertThat(guard.getName()).isEqualTo("pii-detector");
    }

    @Test
    void guardOrder_isConfigurable() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.defaults(), 50);
        assertThat(guard.getOrder()).isEqualTo(50);
    }

    // ── Custom patterns ────────────────────────────────────────────────────────

    @Test
    void customPatternDetected_andRedacted() {
        var config = new PiiDetectorConfig(PiiAction.REDACT, List.of(), List.of("\\bACCT-\\d{8}\\b"));
        var guard = new PiiDetectorGuard(config, 100);
        var ctx = ctx("Account number is ACCT-12345678");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Modify.class);
        String content = ((GuardDecision.Modify) decision).modifiedRequest().messages().get(0).content();
        assertThat(content).contains("[REDACTED]");
        assertThat(content).doesNotContain("ACCT-12345678");
    }

    // ── PII in different roles ─────────────────────────────────────────────────

    @Test
    void piiInAssistantMessage_alsoDetected() {
        var guard = new PiiDetectorGuard(PiiDetectorConfig.blockAll(), 100);
        List<Message> messages = List.of(
            Message.user("Hello"),
            new Message("assistant", "Reply to user@example.com", null, null, null)
        );
        var request = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(messages)
            .build();
        var ctx = new GuardContext(request, null, Map.of(), "req_test");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Block.class);
    }

    // ── Redaction preserves non-PII content ────────────────────────────────────

    @Test
    void redaction_preservesNonPiiContent() {
        var config = new PiiDetectorConfig(PiiAction.REDACT, List.of(PiiPattern.EMAIL), List.of());
        var guard = new PiiDetectorGuard(config, 100);
        var ctx = ctx("My name is John. Email: john@example.com. I like coffee.");

        var decision = guard.inspect(ctx);

        assertThat(decision).isInstanceOf(GuardDecision.Modify.class);
        String content = ((GuardDecision.Modify) decision).modifiedRequest().messages().get(0).content();
        assertThat(content).contains("My name is John");
        assertThat(content).contains("I like coffee");
        assertThat(content).contains("[EMAIL]");
    }
}
