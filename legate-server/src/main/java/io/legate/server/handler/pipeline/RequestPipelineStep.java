package io.legate.server.handler.pipeline;

import io.legate.core.context.RequestContext;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * A single step in the pre-request processing pipeline.
 *
 * <p>Steps are executed in {@link #getOrder()} ascending order by
 * {@link RequestPipeline}. Each step may:</p>
 * <ul>
 *   <li>Mutate the {@link RequestContext} (e.g., populate virtual-key info).</li>
 *   <li>Throw a {@link RuntimeException} to short-circuit the pipeline and
 *       return an error response to the client.</li>
 *   <li>Pass through silently when its condition does not apply.</li>
 * </ul>
 *
 * <p>The {@link ServerRequest} is provided so steps can read HTTP headers,
 * query parameters, or other request metadata without coupling the context
 * object to HTTP concerns.</p>
 *
 * <p>Implementations should be stateless and thread-safe; they are Spring
 * {@code @Component} beans registered once for the JVM lifetime.</p>
 *
 * @see RequestPipeline
 */
public interface RequestPipelineStep extends Ordered {

    /**
     * Executes this step for the given request.
     *
     * @param httpRequest the raw HTTP request (read-only)
     * @param context     the mutable request context shared across all steps
     * @throws RuntimeException to abort the pipeline and propagate an error
     */
    void execute(ServerRequest httpRequest, RequestContext context);
}
