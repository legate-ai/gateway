package io.legate.server.handler.pipeline.step;

import io.legate.core.audit.AuditEvent;
import io.legate.core.audit.AuditEventType;
import io.legate.core.audit.AuditLogger;
import io.legate.core.config.spend.BreachAction;
import io.legate.core.context.RequestContext;
import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.event.EventBus;
import io.legate.core.event.SpendLimitBreachedEvent;
import io.legate.core.exception.SpendLimitExceededException;
import io.legate.core.meter.SpendTracker;
import io.legate.core.meter.SpendTracker.BreachDetail;
import io.legate.server.handler.pipeline.RequestPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Pipeline step that enforces spend (budget) limits for the authenticated virtual key.
 *
 * <p>Runs only when a virtual key is present in the context (i.e., after
 * {@link AuthenticationStep}). The configured {@link BreachAction} determines
 * the response to a limit breach:</p>
 * <ul>
 *   <li>{@code BLOCK}    — throws {@link SpendLimitExceededException}, returning 403.</li>
 *   <li>{@code WARN}     — logs a warning, emits an event, and allows the request.</li>
 *   <li>{@code LOG_ONLY} — logs silently and allows the request.</li>
 * </ul>
 *
 * <p>Execution order: {@code 20} — runs after authentication, before access control.</p>
 */
@Component
public class SpendControlStep implements RequestPipelineStep {

    private static final Logger log = LoggerFactory.getLogger(SpendControlStep.class);

    private final SpendTracker spendTracker;
    private final EventBus eventBus;
    private final AuditLogger auditLog;

    public SpendControlStep(SpendTracker spendTracker, EventBus eventBus, AuditLogger auditLog) {
        this.spendTracker = spendTracker;
        this.eventBus = eventBus;
        this.auditLog = auditLog;
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public void execute(ServerRequest httpRequest, RequestContext context) {
        VirtualKeyInfo keyInfo = context.getVirtualKeyInfo();
        if (keyInfo == null) {
            return;
        }
        BreachDetail breach = spendTracker.checkBudget(keyInfo.keyId());
        if (breach == null) {
            return;
        }
        recordBreach(context, keyInfo.keyId(), breach);
        applyBreachAction(keyInfo.keyId(), breach, spendTracker.getBreachAction(keyInfo.keyId()));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void recordBreach(RequestContext context, String keyId, BreachDetail breach) {
        auditLog.record(AuditEvent.of(
                context.getRequestId(),
                keyId,
                AuditEventType.SPEND_LIMIT_EXCEEDED,
                breach.period().substring(0, 1).toUpperCase() + breach.period().substring(1)
                        + " spend limit exceeded for key: " + keyId
                        + " (current=" + breach.currentSpend() + ", limit=" + breach.limit() + ")"));

        eventBus.publish(new SpendLimitBreachedEvent(
                context.getRequestId(),
                keyId,
                breach.period(),
                breach.currentSpend(),
                breach.limit()));
    }

    private void applyBreachAction(String keyId, BreachDetail breach, BreachAction action) {
        switch (action) {
            case BLOCK -> throw new SpendLimitExceededException(
                    keyId, breach.period(), breach.currentSpend(), breach.limit());
            case WARN -> log.warn(
                    "Spend limit exceeded for virtual key '{}' ({}) — proceeding (action=WARN)",
                    keyId, breach.period());
            case LOG_ONLY -> log.info(
                    "Spend limit exceeded for virtual key '{}' ({}) — proceeding (action=LOG_ONLY)",
                    keyId, breach.period());
        }
    }
}
