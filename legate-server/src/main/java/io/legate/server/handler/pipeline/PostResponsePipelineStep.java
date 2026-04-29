package io.legate.server.handler.pipeline;

import io.legate.core.context.RequestContext;
import org.springframework.core.Ordered;

/**
 * A single step in the post-response processing pipeline.
 *
 * <p>Steps run after the upstream call completes (or the stream closes).
 * They may read and mutate {@link RequestContext} — e.g., to record cost,
 * flush telemetry, or write to the response cache — but must not throw:
 * errors at this stage cannot be surfaced to the client and should be
 * logged and swallowed.</p>
 *
 * <p>Steps are sorted by {@link #getOrder()} ascending and executed by
 * {@link PostResponsePipeline}.</p>
 *
 * @see PostResponsePipeline
 */
public interface PostResponsePipelineStep extends Ordered {
    void execute(RequestContext context);
}
