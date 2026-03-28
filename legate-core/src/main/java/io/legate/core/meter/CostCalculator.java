package io.legate.core.meter;

import io.legate.core.context.RequestContext;
import io.legate.core.model.Usage;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Computes the estimated USD cost for a completed request and stores it in
 * {@link RequestContext#setEstimatedCostUsd(BigDecimal)}.
 *
 * <p>Uses {@link PricingService} for the per-model rate lookup. If the model
 * has no configured pricing, cost is left as {@code null} (unknown).</p>
 *
 * <p>Thread-safe: this class holds no mutable state.</p>
 */
public class CostCalculator {

    private final PricingService pricingService;

    public CostCalculator(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /**
     * Calculates the cost for the request and sets {@code estimatedCostUsd} on the context.
     *
     * <p>The model used for lookup is the actual model in the response (from the endpoint),
     * not the alias the client requested.</p>
     *
     * @param context the request context containing routing decision and usage data
     */
    public void calculate(RequestContext context) {
        Usage usage = context.getUsage();
        if (usage == null) {
            return;
        }

        String model = resolveActualModel(context);
        if (model == null) {
            return;
        }

        Optional<BigDecimal> cost = pricingService.calculateCost(
                model,
                usage.promptTokens(),
                usage.completionTokens()
        );
        cost.ifPresent(context::setEstimatedCostUsd);
    }

    private String resolveActualModel(RequestContext context) {
        // Prefer the model from the response (actual model used by provider)
        if (context.getResponse() != null && context.getResponse().model() != null) {
            return context.getResponse().model();
        }
        // Fall back to the requested model
        return context.getOriginalRequest() != null ? context.getOriginalRequest().model() : null;
    }
}
