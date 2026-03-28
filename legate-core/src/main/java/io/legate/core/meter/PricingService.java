package io.legate.core.meter;

import io.legate.core.config.pricing.ModelPricingConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Looks up model pricing from configuration and calculates request costs.
 *
 * <p>Pricing is read from {@code legate.model-pricing} in the config and held in an
 * {@link AtomicReference} so it can be atomically swapped on hot-reload.</p>
 *
 * <p>Models not present in the pricing table return {@link Optional#empty()} —
 * cost is never guessed.</p>
 *
 * <p>Thread-safe.</p>
 */
public class PricingService {

    private final AtomicReference<Map<String, ModelPricingConfig>> pricingRef;

    /**
     * Initialises the service with the given pricing entries.
     *
     * @param pricingEntries model pricing config list; may be empty but not null
     */
    public PricingService(List<ModelPricingConfig> pricingEntries) {
        this.pricingRef = new AtomicReference<>(buildMap(pricingEntries));
    }

    /**
     * Calculates the estimated cost in USD for a completed request.
     *
     * @param model        canonical model name (as returned by the provider)
     * @param inputTokens  number of prompt tokens
     * @param outputTokens number of completion tokens
     * @return the estimated cost, or {@link Optional#empty()} if the model has no pricing entry
     */
    public Optional<BigDecimal> calculateCost(String model, int inputTokens, int outputTokens) {
        ModelPricingConfig pricing = pricingRef.get().get(model);
        if (pricing == null) {
            return Optional.empty();
        }
        return Optional.of(pricing.calculateCost(inputTokens, outputTokens));
    }

    /**
     * Returns the pricing config for a model, or {@link Optional#empty()} if not found.
     */
    public Optional<ModelPricingConfig> getPricing(String model) {
        return Optional.ofNullable(pricingRef.get().get(model));
    }

    /**
     * Atomically replaces the pricing table (hot-reload).
     *
     * @param newPricingEntries updated pricing list
     */
    public void reload(List<ModelPricingConfig> newPricingEntries) {
        pricingRef.set(buildMap(newPricingEntries));
    }

    private static Map<String, ModelPricingConfig> buildMap(List<ModelPricingConfig> entries) {
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        return entries.stream().collect(Collectors.toUnmodifiableMap(
                ModelPricingConfig::model,
                e -> e,
                (a, b) -> b   // last one wins on duplicate model names
        ));
    }
}
