package io.legate.server.handler.pipeline.step;

import io.legate.core.audit.AuditEvent;
import io.legate.core.audit.AuditEventType;
import io.legate.core.audit.AuditLogger;
import io.legate.core.config.LegateConfig;
import io.legate.core.context.RequestContext;
import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.event.EventBus;
import io.legate.core.event.RateLimitBreachedEvent;
import io.legate.core.exception.AuthenticationException;
import io.legate.core.exception.RateLimitExceededException;
import io.legate.core.key.VirtualKeyStore;
import io.legate.core.model.ChatCompletionRequest;
import io.legate.core.ratelimit.RateLimitResult;
import io.legate.core.ratelimit.RateLimiter;
import io.legate.server.handler.pipeline.RequestPipelineStep;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pipeline step that authenticates the request via a virtual key bearer token
 * and enforces per-key rate limits.
 *
 * <p>When authentication is disabled ({@code legate.admin.require-auth: false}),
 * this step is a no-op and the request proceeds unauthenticated. This supports
 * development and trusted-network deployments.</p>
 *
 * <p>On success, populates {@link RequestContext#setVirtualKeyInfo(VirtualKeyInfo)}
 * so downstream steps can make access-control and spend-limit decisions.</p>
 *
 * <p>Execution order: {@code 10} — must run before all other steps.</p>
 */
@Component
@Order(10)
public class AuthenticationStep implements RequestPipelineStep {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    private final VirtualKeyStore virtualKeyStore;
    private final RateLimiter rateLimiter;
    private final LegateConfig legateConfig;
    private final EventBus eventBus;
    private final AuditLogger auditLog;

    public AuthenticationStep(
            VirtualKeyStore virtualKeyStore,
            RateLimiter rateLimiter,
            LegateConfig legateConfig,
            EventBus eventBus,
            AuditLogger auditLog
    ) {
        this.virtualKeyStore = virtualKeyStore;
        this.rateLimiter = rateLimiter;
        this.legateConfig = legateConfig;
        this.eventBus = eventBus;
        this.auditLog = auditLog;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public void execute(ServerRequest httpRequest, RequestContext context) {
        if (!isAuthRequired()) {
            return;
        }
        VirtualKeyInfo keyInfo = resolveKey(httpRequest, context);
        enforceRateLimit(keyInfo, context);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isAuthRequired() {
        return legateConfig.admin() != null && legateConfig.admin().requireAuth();
    }

    private VirtualKeyInfo resolveKey(ServerRequest httpRequest, RequestContext context) {
        String authHeader = httpRequest.headers().firstHeader(HttpHeaders.AUTHORIZATION);

        if (StringUtils.isBlank(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            throw AuthenticationException.missingKey();
        }

        String rawKey = StringUtils.strip(authHeader.substring(BEARER_PREFIX.length()));
        Optional<VirtualKeyInfo> keyInfo = virtualKeyStore.resolve(rawKey);

        if (keyInfo.isEmpty()) {
            throw AuthenticationException.invalidKey();
        }

        context.setVirtualKeyInfo(keyInfo.get());
        return keyInfo.get();
    }

    private void enforceRateLimit(VirtualKeyInfo keyInfo, RequestContext context) {
        int estimatedTokens = estimateInputTokens(context.getOriginalRequest());
        RateLimitResult result = rateLimiter.tryAcquire(keyInfo.keyId(), estimatedTokens);

        if (result instanceof RateLimitResult.Denied denied) {
            auditLog.record(AuditEvent.of(
                    context.getRequestId(),
                    keyInfo.keyId(),
                    AuditEventType.RATE_LIMIT_EXCEEDED,
                    "Rate limit exceeded: " + denied.reason()));

            eventBus.publish(new RateLimitBreachedEvent(
                    context.getRequestId(),
                    keyInfo.keyId(),
                    denied.reason(),
                    denied.current(),
                    denied.limit()));

            Instant retryAfter = Instant.now().plusSeconds(denied.retryAfter());
            throw new RateLimitExceededException(keyInfo.keyId(), denied.reason(), retryAfter);
        }
    }

    /**
     * Estimates token count from message character lengths.
     * Uses the widely-cited heuristic of approximately 4 characters per token.
     */
    private int estimateInputTokens(ChatCompletionRequest request) {
        List<io.legate.core.model.Message> messages = request.messages();
        if (messages == null) {
            return 0;
        }
        return messages.stream()
                .filter(m -> m.content() != null)
                .mapToInt(m -> m.content().length() / CHARS_PER_TOKEN_ESTIMATE)
                .sum();
    }
}
