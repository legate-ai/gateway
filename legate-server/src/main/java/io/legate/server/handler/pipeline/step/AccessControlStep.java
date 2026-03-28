package io.legate.server.handler.pipeline.step;

import io.legate.core.context.RequestContext;
import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.routing.AccessController;
import io.legate.core.routing.RoutingEngine;
import io.legate.server.handler.pipeline.RequestPipelineStep;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Pipeline step that enforces model-level access control for the authenticated virtual key.
 *
 * <p>Resolves the requested model or alias to its canonical name, then delegates to
 * {@link AccessController} which applies the key's allow/deny glob patterns. Requests
 * for disallowed models are rejected with {@link io.legate.core.exception.ModelNotAllowedException}.</p>
 *
 * <p>This step is a no-op when no virtual key is present (unauthenticated requests in
 * development mode pass through with no access restrictions).</p>
 *
 * <p>Execution order: {@code 30} — runs after spend control, before guards.</p>
 */
@Component
public class AccessControlStep implements RequestPipelineStep {

    private final AccessController accessController;
    private final RoutingEngine routingEngine;

    public AccessControlStep(AccessController accessController, RoutingEngine routingEngine) {
        this.accessController = accessController;
        this.routingEngine = routingEngine;
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public void execute(ServerRequest httpRequest, RequestContext context) {
        VirtualKeyInfo keyInfo = context.getVirtualKeyInfo();
        if (keyInfo == null) {
            return;
        }
        String resolvedModel = routingEngine.resolveAlias(context.getEffectiveRequest().model());
        accessController.checkAccess(keyInfo, resolvedModel);
    }
}
