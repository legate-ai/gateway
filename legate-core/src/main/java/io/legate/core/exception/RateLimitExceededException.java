package io.legate.core.exception;

import java.time.Instant;

/**
 * Thrown when a virtual key exceeds its rate limit.
 */
public class RateLimitExceededException extends LegateException {
    private final String virtualKeyId;
    private final String limitType;
    private final Instant retryAfter;

    public RateLimitExceededException(String virtualKeyId, String limitType, Instant retryAfter) {
        super(String.format("Rate limit exceeded for virtual key '%s': %s", virtualKeyId, limitType),
                "RATE_LIMIT_EXCEEDED");
        this.virtualKeyId = virtualKeyId;
        this.limitType = limitType;
        this.retryAfter = retryAfter;
    }

    public String getVirtualKeyId() {
        return virtualKeyId;
    }

    public String getLimitType() {
        return limitType;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }
}
