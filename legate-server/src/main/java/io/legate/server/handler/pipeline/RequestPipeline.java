package io.legate.server.handler.pipeline;

import io.legate.core.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.List;

/**
 * Orchestrates the pre-request processing pipeline using the
 * <em>Chain of Responsibility</em> pattern.
 *
 * <p>All discovered {@link RequestPipelineStep} beans are sorted by
 * {@link org.springframework.core.Ordered#getOrder()} and executed in sequence.
 * Any step may throw a {@link RuntimeException} to abort further processing;
 * the exception propagates to the calling handler which returns the
 * appropriate HTTP error response.</p>
 *
 * <p>Adding a new processing concern (e.g., a geo-fencing check) requires only
 * implementing {@link RequestPipelineStep} and annotating with
 * {@code @Component} — no modification to this class is needed.</p>
 *
 * <h3>Default step order</h3>
 * <ol>
 *   <li>10 — Authentication &amp; rate limiting</li>
 *   <li>20 — Spend limit pre-check</li>
 *   <li>30 — Model access control</li>
 *   <li>40 — Request guard pipeline</li>
 * </ol>
 */
@Component
public class RequestPipeline {

    private static final Logger log = LoggerFactory.getLogger(RequestPipeline.class);

    private final List<RequestPipelineStep> steps;

    /**
     * Spring injects all {@link RequestPipelineStep} beans; they are sorted
     * by {@link AnnotationAwareOrderComparator} at construction time.
     *
     * @param steps all registered pipeline steps
     */
    public RequestPipeline(List<RequestPipelineStep> steps) {
        this.steps = steps.stream()
            .sorted(AnnotationAwareOrderComparator.INSTANCE)
            .toList();

        log.info("RequestPipeline initialised with {} step(s): {}",
            this.steps.size(),
            this.steps.stream()
                .map(s -> s.getClass().getSimpleName() + "(order=" + s.getOrder() + ")")
                .toList());
    }

    /**
     * Executes all pipeline steps in order for the given request.
     *
     * <p>Execution halts immediately if any step throws. The exception
     * is propagated to the caller unchanged — it is the handler's
     * responsibility to map it to an appropriate HTTP response.</p>
     *
     * @param httpRequest the raw HTTP request
     * @param context     the mutable request context
     * @throws RuntimeException from any step that rejects the request
     */
    public void execute(ServerRequest httpRequest, RequestContext context) {
        for (RequestPipelineStep step : steps) {
            log.debug("Executing pipeline step {} for request {}",
                step.getClass().getSimpleName(), context.getRequestId());
            step.execute(httpRequest, context);
        }
    }
}
