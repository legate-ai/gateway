package io.legate.server.handler.pipeline.step;

import io.legate.core.audit.AuditEvent;
import io.legate.core.audit.AuditEventType;
import io.legate.core.audit.AuditLogger;
import io.legate.core.context.RequestContext;
import io.legate.core.event.EventBus;
import io.legate.core.event.GuardDecisionEvent;
import io.legate.core.exception.GuardBlockedException;
import io.legate.core.guard.GuardDecision;
import io.legate.core.guard.GuardPipeline;
import io.legate.core.guard.GuardPipelineResult;
import io.legate.server.handler.pipeline.RequestPipelineStep;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pipeline step that runs the request through the configured guard chain.
 *
 * <p>Bridges the HTTP pipeline to the core {@link GuardPipeline}. Guards inspect
 * request content for PII, blocked keywords, token limits, and any custom rules.
 * The outcome is recorded in the {@link RequestContext} and published to the
 * {@link EventBus} for audit and telemetry.</p>
 *
 * <p>A {@link GuardPipelineResult.Rejected} result throws
 * {@link GuardBlockedException}, which the global error handler maps to a
 * {@code 400 Bad Request} response with the blocking guard name and reason.</p>
 *
 * <p>Execution order: {@code 40} — runs last in the pre-request pipeline,
 * after access control, so guards see the access-controlled effective request.</p>
 */
@Component
public class GuardPipelineStep implements RequestPipelineStep {

    private final GuardPipeline guardPipeline;
    private final EventBus eventBus;
    private final AuditLogger auditLog;

    public GuardPipelineStep(GuardPipeline guardPipeline, EventBus eventBus, AuditLogger auditLog) {
        this.guardPipeline = guardPipeline;
        this.eventBus = eventBus;
        this.auditLog = auditLog;
    }

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public void execute(ServerRequest httpRequest, RequestContext context) {
        Map<String, String> headers = extractHeaders(httpRequest);
        context.setRequestHeaders(headers);

        GuardPipelineResult result = guardPipeline.execute(context, headers);
        publishGuardEvents(context, result.decisions());

        if (result instanceof GuardPipelineResult.Rejected rejected) {
            recordBlockAuditEvent(context, rejected);
            throw new GuardBlockedException(rejected.guardName(), rejected.reason());
        }

        recordModifyWarnAuditEvents(context, result.decisions());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, String> extractHeaders(ServerRequest httpRequest) {
        return httpRequest.headers().asHttpHeaders().toSingleValueMap().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        e -> e.getKey().toLowerCase(),
                        Map.Entry::getValue
                ));
    }

    private void publishGuardEvents(RequestContext context, List<GuardDecision> decisions) {
        for (GuardDecision decision : decisions) {
            switch (decision) {
                case GuardDecision.Block block -> eventBus.publish(
                        new GuardDecisionEvent(context.getRequestId(), block.guardName(), "block", block.reason()));
                case GuardDecision.Modify modify -> eventBus.publish(
                        new GuardDecisionEvent(context.getRequestId(), modify.guardName(), "modify", modify.reason()));
                case GuardDecision.Warn warn -> eventBus.publish(
                        new GuardDecisionEvent(context.getRequestId(), warn.guardName(), "warn", warn.reason()));
                case GuardDecision.Allow ignored -> { /* no event for allow decisions */ }
            }
        }
    }

    private void recordBlockAuditEvent(RequestContext context, GuardPipelineResult.Rejected rejected) {
        String keyId = context.getVirtualKeyInfo() != null ? context.getVirtualKeyInfo().keyId() : null;
        auditLog.record(AuditEvent.of(
                context.getRequestId(),
                keyId,
                AuditEventType.REQUEST_BLOCKED,
                StringUtils.truncate("Guard '" + rejected.guardName() + "' blocked: " + rejected.reason(), 500)));
    }

    private void recordModifyWarnAuditEvents(RequestContext context, List<GuardDecision> decisions) {
        String keyId = context.getVirtualKeyInfo() != null ? context.getVirtualKeyInfo().keyId() : null;

        boolean hadModify = decisions.stream().anyMatch(d -> d instanceof GuardDecision.Modify);
        boolean hadWarn = decisions.stream().anyMatch(d -> d instanceof GuardDecision.Warn);

        if (hadModify && keyId != null) {
            auditLog.record(AuditEvent.of(
                    context.getRequestId(), keyId,
                    AuditEventType.REQUEST_MODIFIED,
                    "Request modified by guard pipeline"));
        }
        if (hadWarn && keyId != null) {
            auditLog.record(AuditEvent.of(
                    context.getRequestId(), keyId,
                    AuditEventType.REQUEST_WARNED,
                    "Guard pipeline issued warnings"));
        }
    }
}
