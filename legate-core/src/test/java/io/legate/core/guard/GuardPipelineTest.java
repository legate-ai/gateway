package io.legate.core.guard;

import io.legate.core.context.RequestContext;
import io.legate.core.guard.builtin.KeywordBlockerGuard;
import io.legate.core.guard.builtin.SystemPromptInjectorGuard;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardPipelineTest {

    private RequestContext buildContext(String... userMessages) {
        var ctx = new RequestContext("req_test_123");
        List<Message> messages = List.of(userMessages).stream()
            .map(Message::user)
            .toList();
        ctx.setOriginalRequest(ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(messages)
            .build());
        return ctx;
    }

    // ── Empty pipeline ─────────────────────────────────────────────────────────

    @Test
    void emptyPipeline_approvesAllRequests() {
        var pipeline = new GuardPipeline();
        var ctx = buildContext("Hello world");

        var result = pipeline.execute(ctx, Map.of());

        assertThat(result).isInstanceOf(GuardPipelineResult.Approved.class);
        assertThat(pipeline.isEmpty()).isTrue();
    }

    // ── Allow decision ─────────────────────────────────────────────────────────

    @Test
    void allowingGuard_passesThrough() {
        RequestGuard allowAll = new RequestGuard() {
            @Override public String getName() { return "allow-all"; }
            @Override public int getOrder() { return 100; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                return new GuardDecision.Allow(getName());
            }
        };

        var pipeline = new GuardPipeline(List.of(allowAll));
        var ctx = buildContext("Hello");

        var result = pipeline.execute(ctx, Map.of());

        assertThat(result).isInstanceOf(GuardPipelineResult.Approved.class);
    }

    // ── Block decision (short-circuit) ─────────────────────────────────────────

    @Test
    void blockingGuard_shortCircuitsPipeline() {
        RequestGuard blocker = new RequestGuard() {
            @Override public String getName() { return "always-block"; }
            @Override public int getOrder() { return 100; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                return new GuardDecision.Block(getName(), "Test block");
            }
        };

        // This guard should NEVER be reached
        RequestGuard secondGuard = new RequestGuard() {
            @Override public String getName() { return "second"; }
            @Override public int getOrder() { return 200; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                throw new RuntimeException("Should not be called!");
            }
        };

        var pipeline = new GuardPipeline(List.of(blocker, secondGuard));
        var ctx = buildContext("Hello");

        var result = pipeline.execute(ctx, Map.of());

        assertThat(result).isInstanceOf(GuardPipelineResult.Rejected.class);
        var rejected = (GuardPipelineResult.Rejected) result;
        assertThat(rejected.guardName()).isEqualTo("always-block");
        assertThat(rejected.reason()).isEqualTo("Test block");
    }

    // ── Modify decision (cascade) ──────────────────────────────────────────────

    @Test
    void modifyingGuard_cascadesModifiedRequest() {
        RequestGuard modifier = new RequestGuard() {
            @Override public String getName() { return "modifier"; }
            @Override public int getOrder() { return 100; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                var modified = ctx.request().withModel("gpt-4o-mini");
                return new GuardDecision.Modify(getName(), modified, "Downgraded model");
            }
        };

        // Second guard should see the modified request
        RequestGuard verifier = new RequestGuard() {
            @Override public String getName() { return "verifier"; }
            @Override public int getOrder() { return 200; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                assertThat(ctx.request().model()).isEqualTo("gpt-4o-mini");
                return new GuardDecision.Allow(getName());
            }
        };

        var pipeline = new GuardPipeline(List.of(modifier, verifier));
        var ctx = buildContext("Hello");

        var result = pipeline.execute(ctx, Map.of());

        assertThat(result).isInstanceOf(GuardPipelineResult.Approved.class);
        // Effective request in context should be modified
        assertThat(ctx.getEffectiveRequest().model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void multipleModifyGuards_cascadeCumulatively() {
        RequestGuard modifier1 = new RequestGuard() {
            @Override public String getName() { return "modifier1"; }
            @Override public int getOrder() { return 100; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                var modified = ctx.request().withModel("gpt-4o-mini");
                return new GuardDecision.Modify(getName(), modified, "Step 1");
            }
        };

        RequestGuard modifier2 = new RequestGuard() {
            @Override public String getName() { return "modifier2"; }
            @Override public int getOrder() { return 200; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                // Should see the model already modified to gpt-4o-mini by modifier1
                assertThat(ctx.request().model()).isEqualTo("gpt-4o-mini");
                var modified = ctx.request().withMessages(List.of(Message.system("Added"), Message.user("Original")));
                return new GuardDecision.Modify(getName(), modified, "Step 2");
            }
        };

        var pipeline = new GuardPipeline(List.of(modifier1, modifier2));
        var ctx = buildContext("Original");

        pipeline.execute(ctx, Map.of());

        assertThat(ctx.getEffectiveRequest().model()).isEqualTo("gpt-4o-mini");
        assertThat(ctx.getEffectiveRequest().messages()).hasSize(2);
    }

    // ── Warn decision ──────────────────────────────────────────────────────────

    @Test
    void warnDecision_continuesPipeline() {
        RequestGuard warner = new RequestGuard() {
            @Override public String getName() { return "warner"; }
            @Override public int getOrder() { return 100; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                return new GuardDecision.Warn(getName(), "Just a warning");
            }
        };

        RequestGuard allowNext = new RequestGuard() {
            @Override public String getName() { return "allow-next"; }
            @Override public int getOrder() { return 200; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                return new GuardDecision.Allow(getName());
            }
        };

        var pipeline = new GuardPipeline(List.of(warner, allowNext));
        var ctx = buildContext("Hello");

        var result = pipeline.execute(ctx, Map.of());

        assertThat(result).isInstanceOf(GuardPipelineResult.Approved.class);
    }

    // ── Execution order ────────────────────────────────────────────────────────

    @Test
    void guards_executedInAscendingOrder() {
        var executionOrder = new java.util.ArrayList<Integer>();

        for (int order : new int[]{300, 100, 200}) {
            final int o = order;
            // Just add them in non-sorted order
        }

        RequestGuard g100 = new RequestGuard() {
            @Override public String getName() { return "g100"; }
            @Override public int getOrder() { return 100; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                executionOrder.add(100);
                return new GuardDecision.Allow(getName());
            }
        };

        RequestGuard g300 = new RequestGuard() {
            @Override public String getName() { return "g300"; }
            @Override public int getOrder() { return 300; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                executionOrder.add(300);
                return new GuardDecision.Allow(getName());
            }
        };

        RequestGuard g200 = new RequestGuard() {
            @Override public String getName() { return "g200"; }
            @Override public int getOrder() { return 200; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                executionOrder.add(200);
                return new GuardDecision.Allow(getName());
            }
        };

        // Pass in wrong order — pipeline should sort
        var pipeline = new GuardPipeline(List.of(g300, g100, g200));
        var ctx = buildContext("Hello");

        pipeline.execute(ctx, Map.of());

        assertThat(executionOrder).containsExactly(100, 200, 300);
    }

    // ── Guard exceptions ───────────────────────────────────────────────────────

    @Test
    void guardException_blocksRequest_failClosed() {
        RequestGuard buggy = new RequestGuard() {
            @Override public String getName() { return "buggy"; }
            @Override public int getOrder() { return 100; }
            @Override public GuardDecision inspect(GuardContext ctx) {
                throw new RuntimeException("Unexpected error in guard");
            }
        };

        var pipeline = new GuardPipeline(List.of(buggy));
        var ctx = buildContext("Hello");

        // A crashing guard must BLOCK — fail-closed for security
        var result = pipeline.execute(ctx, Map.of());

        assertThat(result).isInstanceOf(GuardPipelineResult.Rejected.class);
        var rejected = (GuardPipelineResult.Rejected) result;
        assertThat(rejected.guardName()).isEqualTo("buggy");
        assertThat(rejected.reason()).contains("Guard threw exception");
    }

    // ── Guard decisions are recorded ──────────────────────────────────────────

    @Test
    void allDecisions_recordedInContext() {
        var pipeline = new GuardPipeline(List.of(
            new KeywordBlockerGuard(List.of("badword"), false, 100) // WARN
        ));
        var ctx = buildContext("this has a badword in it");

        pipeline.execute(ctx, Map.of());

        assertThat(ctx.getGuardDecisions()).hasSize(1);
        assertThat(ctx.getGuardDecisions().get(0)).isInstanceOf(GuardDecision.Warn.class);
    }

    // ── System prompt injector ─────────────────────────────────────────────────

    @Test
    void systemPromptInjector_prependsSystemMessage() {
        var injector = new SystemPromptInjectorGuard("You are a helpful assistant.", 50);
        var pipeline = new GuardPipeline(List.of(injector));
        var ctx = buildContext("Hello!");

        pipeline.execute(ctx, Map.of());

        var messages = ctx.getEffectiveRequest().messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).isEqualTo("You are a helpful assistant.");
        assertThat(messages.get(1).content()).isEqualTo("Hello!");
    }
}
