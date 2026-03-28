package io.legate.core.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for provider adapters.
 * Supports lookup by provider name or automatic selection by model name.
 */
public class ProviderAdapterRegistry {
    private static final Logger log = LoggerFactory.getLogger(ProviderAdapterRegistry.class);

    private final Map<String, ProviderAdapter> adaptersByName = new ConcurrentHashMap<>();

    /**
     * Registers a provider adapter.
     *
     * @param adapter the adapter to register
     */
    public void register(ProviderAdapter adapter) {
        String name = adapter.getProviderName();
        adaptersByName.put(name, adapter);
        log.info("Registered provider adapter: {}", name);
    }

    /**
     * Registers multiple provider adapters.
     *
     * @param adapters the adapters to register
     */
    public void registerAll(List<ProviderAdapter> adapters) {
        adapters.forEach(this::register);
    }

    /**
     * Gets a provider adapter by name.
     *
     * @param providerName the provider name
     * @return the adapter, or empty if not found
     */
    public Optional<ProviderAdapter> getByName(String providerName) {
        return Optional.ofNullable(adaptersByName.get(providerName));
    }

    /**
     * Finds a provider adapter that supports the given model name.
     * Returns the first adapter that claims to support the model.
     *
     * @param modelName the model name
     * @return the adapter, or empty if no adapter supports the model
     */
    public Optional<ProviderAdapter> findByModel(String modelName) {
        return adaptersByName.values().stream()
                .filter(adapter -> adapter.supports(modelName))
                .findFirst();
    }

    /**
     * Gets a provider adapter by name or auto-detects by model.
     *
     * @param providerName the explicit provider name (may be null)
     * @param modelName    the model name
     * @return the adapter
     * @throws IllegalArgumentException if no suitable adapter is found
     */
    public ProviderAdapter resolve(String providerName, String modelName) {
        // If provider is explicitly specified, use it
        if (providerName != null && !providerName.isBlank()) {
            return getByName(providerName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown provider: " + providerName
                    ));
        }

        // Otherwise, auto-detect from model name
        return findByModel(modelName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No provider adapter found for model: " + modelName +
                                ". Available providers: " + adaptersByName.keySet()
                ));
    }

    /**
     * Returns all registered provider names.
     */
    public List<String> getProviderNames() {
        return List.copyOf(adaptersByName.keySet());
    }

    /**
     * Returns the number of registered adapters.
     */
    public int size() {
        return adaptersByName.size();
    }

    /**
     * Clears all registered adapters.
     */
    public void clear() {
        adaptersByName.clear();
    }
}
