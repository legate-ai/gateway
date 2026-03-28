package io.legate.core.exception;

import java.math.BigDecimal;

/**
 * Thrown when a virtual key exceeds its spend limit.
 */
public class SpendLimitExceededException extends LegateException {
    private final String virtualKeyId;
    private final String limitType;
    private final BigDecimal currentSpend;
    private final BigDecimal limit;

    public SpendLimitExceededException(
            String virtualKeyId,
            String limitType,
            BigDecimal currentSpend,
            BigDecimal limit
    ) {
        super(String.format(
                "Spend limit exceeded for virtual key '%s': %s (current: $%.2f, limit: $%.2f)",
                virtualKeyId, limitType, currentSpend, limit
        ), "SPEND_LIMIT_EXCEEDED");
        this.virtualKeyId = virtualKeyId;
        this.limitType = limitType;
        this.currentSpend = currentSpend;
        this.limit = limit;
    }

    public String getVirtualKeyId() {
        return virtualKeyId;
    }

    public String getLimitType() {
        return limitType;
    }

    public BigDecimal getCurrentSpend() {
        return currentSpend;
    }

    public BigDecimal getLimit() {
        return limit;
    }
}
