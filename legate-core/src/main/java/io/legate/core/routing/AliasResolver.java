package io.legate.core.routing;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves model name aliases to their canonical model identifiers.
 *
 * <p>Allows clients to use short, memorable names (e.g., {@code "smart"}, {@code "fast"})
 * that are transparently mapped to full provider model IDs before routing.</p>
 *
 * <p>Thread-safe: alias map updates are published atomically via {@link AtomicReference}
 * so in-flight requests always see a consistent snapshot.</p>
 *
 * <h3>Usage in YAML</h3>
 * <pre>{@code
 * legate:
 *   routing:
 *     aliases:
 *       smart: gpt-4o
 *       fast:  gpt-4o-mini
 *       claude: claude-3-5-sonnet-20241022
 * }</pre>
 */
public class AliasResolver {

    private final AtomicReference<Map<String, String>> aliasesRef;

    /**
     * Creates an AliasResolver with the given alias map.
     *
     * @param aliases model-alias to model-id mapping; may be null (treated as empty)
     */
    public AliasResolver(Map<String, String> aliases) {
        this.aliasesRef = new AtomicReference<>(
                aliases != null ? Map.copyOf(aliases) : Map.of()
        );
    }

    /**
     * Creates an AliasResolver with no aliases.
     */
    public AliasResolver() {
        this(null);
    }

    /**
     * Resolves a model name or alias to its canonical model identifier.
     *
     * @param modelOrAlias the raw model name from the client request
     * @return the resolved canonical model name, or {@code modelOrAlias} if no alias exists
     */
    public String resolve(String modelOrAlias) {
        if (modelOrAlias == null) {
            return null;
        }
        return aliasesRef.get().getOrDefault(modelOrAlias, modelOrAlias);
    }

    /**
     * Returns true if the given name is a configured alias.
     *
     * @param name the name to check
     */
    public boolean isAlias(String name) {
        return aliasesRef.get().containsKey(name);
    }

    /**
     * Atomically replaces the alias map (hot-reload support).
     *
     * @param newAliases replacement alias map; may be null (clears all aliases)
     */
    public void reload(Map<String, String> newAliases) {
        aliasesRef.set(newAliases != null ? Map.copyOf(newAliases) : Map.of());
    }

    /**
     * Returns the current alias map (snapshot).
     */
    public Map<String, String> getAliases() {
        return aliasesRef.get();
    }
}
