package io.legate.server.handler.pipeline;

import io.legate.core.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Orchestrates the post-response processing pipeline.
 *
 * <p>All discovered {@link PostResponsePipelineStep} beans are sorted by
 * {@link org.springframework.core.Ordered#getOrder()} and executed in sequence.
 * Steps must not throw — errors are caught and logged so that one failing step
 * cannot block subsequent steps.</p>
 *
 * <h3>Default step order</h3>
 * <ol>
 *   <li>10 — Cost calculation + spend tracking</li>
 *   <li>20 — Token usage reporting + event publishing</li>
 *   <li>30 — Response cache write</li>
 * </ol>
 */
@Component
public class PostResponsePipeline {

    private static final Logger log = LoggerFactory.getLogger(PostResponsePipeline.class);

    private final List<PostResponsePipelineStep> steps;

    public PostResponsePipeline(List<PostResponsePipelineStep> steps) {
        this.steps = steps.stream()
            .sorted(AnnotationAwareOrderComparator.INSTANCE)
            .toList();
        log.info("PostResponsePipeline initialised with {} step(s): {}",
            this.steps.size(),
            this.steps.stream()
                .map(s -> s.getClass().getSimpleName() + "(order=" + s.getOrder() + ")")
                .toList());
    }

    public void execute(RequestContext context) {
        for (PostResponsePipelineStep step : steps) {
            try {
                step.execute(context);
            } catch (Exception e) {
                log.error("PostResponsePipeline: step {} threw unexpectedly for request {}",
                    step.getClass().getSimpleName(), context.getRequestId(), e);
            }
        }
    }
}
