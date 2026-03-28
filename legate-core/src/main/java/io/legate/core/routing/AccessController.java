package io.legate.core.routing;

import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.exception.ModelNotAllowedException;

/**
 * Enforces model-level access control for virtual keys.
 *
 * <p>Access evaluation uses the glob patterns stored in {@link VirtualKeyInfo}:</p>
 * <ol>
 *   <li>If the resolved model matches any pattern in {@code deniedModels} → deny.</li>
 *   <li>Else, if {@code allowedModels} is empty → deny.</li>
 *   <li>Else, if the model matches any pattern in {@code allowedModels} → allow.</li>
 *   <li>Otherwise → deny.</li>
 * </ol>
 *
 * <p>Glob patterns support {@code *} as a wildcard prefix or suffix
 * (e.g., {@code gpt-*}, {@code *-mini}, {@code *}).</p>
 *
 * <p>Thread-safe: this class is stateless.</p>
 */
public class AccessController {

    /**
     * Checks whether the given virtual key is allowed to use the specified model.
     *
     * @param keyInfo       the authenticated virtual key; if {@code null}, access is allowed
     *                      (dev/no-auth mode)
     * @param resolvedModel the canonical model name after alias resolution
     * @throws ModelNotAllowedException if the key is not allowed to use the model
     */
    public void checkAccess(VirtualKeyInfo keyInfo, String resolvedModel) {
        if (keyInfo == null) {
            return; // dev mode: no key, allow all
        }

        if (!keyInfo.isModelAllowed(resolvedModel)) {
            throw new ModelNotAllowedException(resolvedModel, keyInfo.keyId());
        }
    }
}
