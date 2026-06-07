package io.legate.server.handler.pipeline.step;

import io.legate.core.context.RequestContext;
import io.legate.core.meter.CostCalculator;
import io.legate.core.meter.SpendTracker;
import io.legate.server.handler.pipeline.PostResponsePipelineStep;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Post-response step: computes estimated cost and records spend against the virtual key.
 * Order 10 — must run before event publishing so cost is available in the CompletionEvent.
 */
@Component
@Order(10)
public class CostAndSpendStep implements PostResponsePipelineStep {

    private final CostCalculator costCalculator;
    private final SpendTracker spendTracker;

    public CostAndSpendStep(CostCalculator costCalculator, SpendTracker spendTracker) {
        this.costCalculator = costCalculator;
        this.spendTracker = spendTracker;
    }

    @Override
    public int getOrder() { return 10; }

    @Override
    public void execute(RequestContext context) {
        costCalculator.calculate(context);
        if (context.getEstimatedCostUsd() != null && context.getVirtualKeyInfo() != null) {
            spendTracker.recordSpend(
                context.getVirtualKeyInfo().keyId(),
                context.getEstimatedCostUsd());
        }
    }
}
