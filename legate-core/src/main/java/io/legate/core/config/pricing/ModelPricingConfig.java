package io.legate.core.config.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pricing entry for a single model used to estimate request cost.
 *
 * <p>Costs are expressed in USD per one million tokens to preserve precision when
 * multiplying small per-token rates. The calculation uses {@link RoundingMode#HALF_UP}
 * with 10 decimal places internally, returning a value rounded to 8 decimal places.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   model-pricing:
 *     - model: gpt-4o
 *       input-cost-per-million-tokens: 2.50
 *       output-cost-per-million-tokens: 10.00
 *     - model: gpt-4o-mini
 *       input-cost-per-million-tokens: 0.15
 *       output-cost-per-million-tokens: 0.60
 *     - model: claude-3-5-sonnet-20241022
 *       input-cost-per-million-tokens: 3.00
 *       output-cost-per-million-tokens: 15.00
 *     - model: claude-3-haiku-20240307
 *       input-cost-per-million-tokens: 0.25
 *       output-cost-per-million-tokens: 1.25
 * }</pre>
 */
public record ModelPricingConfig(

    /**
     * Exact model identifier as returned by the provider (e.g., {@code gpt-4o}).
     * This is matched case-sensitively against the {@code model} field in API responses.
     * Glob patterns are not supported — use the canonical model ID.
     */
    String model,

    /**
     * Cost in USD per one million <em>input</em> (prompt) tokens.
     * Must be a non-negative value.
     */
    BigDecimal inputCostPerMillionTokens,

    /**
     * Cost in USD per one million <em>output</em> (completion) tokens.
     * Must be a non-negative value.
     */
    BigDecimal outputCostPerMillionTokens

) {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /**
     * Calculates the total estimated cost in USD for a completed request.
     *
     * @param inputTokens  number of prompt tokens charged
     * @param outputTokens number of completion tokens generated
     * @return estimated cost in USD, rounded to 8 decimal places
     * @throws IllegalArgumentException if either token count is negative
     */
    public BigDecimal calculateCost(int inputTokens, int outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException(
                "Token counts must be non-negative, got inputTokens=" + inputTokens +
                ", outputTokens=" + outputTokens
            );
        }
        BigDecimal inputCost = inputCostPerMillionTokens
            .multiply(BigDecimal.valueOf(inputTokens))
            .divide(MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputCostPerMillionTokens
            .multiply(BigDecimal.valueOf(outputTokens))
            .divide(MILLION, 10, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Returns the effective cost-per-token for input (in USD).
     * Convenience method for cost-optimised load balancing.
     */
    public double inputCostPerToken() {
        return inputCostPerMillionTokens.doubleValue() / 1_000_000.0;
    }

    /**
     * Returns the effective cost-per-token for output (in USD).
     */
    public double outputCostPerToken() {
        return outputCostPerMillionTokens.doubleValue() / 1_000_000.0;
    }
}
