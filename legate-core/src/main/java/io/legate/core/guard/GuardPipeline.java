package io.legate.core.guard;

import io.legate.core.context.RequestContext;
import io.legate.core.model.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Executes the ordered {@link RequestGuard} pipeline for a single request.
 *
 * <p>Execution rules:</p>
 * <ul>
 *   <li>Guards are sorted ascending by {@link RequestGuard#getOrder()}.</li>
 *   <li>A {@link GuardDecision.Block} short-circuits the pipeline and returns
 *       {@link GuardPipelineResult.Rejected}.</li>
 *   <li>A {@link GuardDecision.Modify} cascades: the modified request replaces the
 *       effective request in {@link RequestContext} and all subsequent guards see the
 *       modified version.</li>
 *   <li>{@link GuardDecision.Allow} and {@link GuardDecision.Warn} continue the pipeline.</li>
 * </ul>
 *
 * <p>All decisions (including Allow and Warn) are recorded in {@code RequestContext.guardDecisions}
 * for audit purposes.</p>
 *
 * <p>Thread-safe: this class holds no mutable state; the guard list is immutable after
 * construction. Each call to {@link #execute} uses only its own local variables.</p>
 */
public class GuardPipeline {

    private static final Logger log = LoggerFactory.getLogger(GuardPipeline.class);

    private final List<RequestGuard> requestGuards;
    private final List<ResponseGuard> responseGuards;

    /**
     * Creates a pipeline with the given guards.
     *
     * @param requestGuards  request guards; sorted by order at construction time
     * @param responseGuards response guards; sorted by order at construction time
     */
    public GuardPipeline(List<RequestGuard> requestGuards, List<ResponseGuard> responseGuards) {
        this.requestGuards = (requestGuards == null ? List.<RequestGuard>of() : requestGuards)
                .stream().sorted(Comparator.comparingInt(RequestGuard::getOrder)).toList();
        this.responseGuards = (responseGuards == null ? List.<ResponseGuard>of() : responseGuards)
                .stream().sorted(Comparator.comparingInt(ResponseGuard::getOrder)).toList();
    }

    /**
     * Creates a pipeline with only request guards (no response guards).
     */
    public GuardPipeline(List<RequestGuard> requestGuards) {
        this(requestGuards, List.of());
    }

    /**
     * Creates an empty no-op pipeline.
     */
    public GuardPipeline() {
        this(List.of(), List.of());
    }

    /**
     * Executes the request guard pipeline.
     *
     * @param context the request context (effective request may be updated for Modify decisions)
     * @param headers HTTP request headers for context (may be empty)
     * @return the pipeline result
     */
    public GuardPipelineResult execute(RequestContext context, Map<String, String> headers) {
        List<GuardDecision> decisions = new ArrayList<>();
        ChatCompletionRequest currentRequest = context.getEffectiveRequest();

        for (RequestGuard guard : requestGuards) {
            GuardContext guardContext = new GuardContext(
                    currentRequest,
                    context.getVirtualKeyInfo(),
                    headers,
                    context.getRequestId()
            );

            GuardDecision decision;
            try {
                decision = guard.inspect(guardContext);
            } catch (Exception e) {
                log.error("Guard '{}' threw unexpected exception — blocking request (fail-closed)", guard.getName(), e);
                decision = new GuardDecision.Block(guard.getName(), "Guard threw exception: " + e.getMessage());
            }

            decisions.add(decision);
            context.addGuardDecision(decision);

            switch (decision) {
                case GuardDecision.Block b -> {
                    log.debug("Guard '{}' blocked request {}: {}", b.guardName(), context.getRequestId(), b.reason());
                    return new GuardPipelineResult.Rejected(List.copyOf(decisions), b.reason(), b.guardName());
                }
                case GuardDecision.Modify m -> {
                    log.debug("Guard '{}' modified request {}: {}", m.guardName(), context.getRequestId(), m.reason());
                    currentRequest = m.modifiedRequest();
                    context.setEffectiveRequest(currentRequest);
                }
                case GuardDecision.Warn w ->
                        log.debug("Guard '{}' warned on request {}: {}", w.guardName(), context.getRequestId(), w.reason());
                case GuardDecision.Allow a -> {
                }
            }
        }

        return new GuardPipelineResult.Approved(List.copyOf(decisions));
    }

    /**
     * Executes the response guard pipeline.
     *
     * @param context the response guard context
     * @return result decisions list (Reject is returned as first Block decision, or all Allow/Warn)
     */
    public List<ResponseGuardDecision> executeResponse(ResponseGuardContext context) {
        List<ResponseGuardDecision> decisions = new ArrayList<>();

        for (ResponseGuard guard : responseGuards) {
            ResponseGuardDecision decision;
            try {
                decision = guard.inspect(context);
            } catch (Exception e) {
                log.error("Response guard '{}' threw unexpected exception — blocking response (fail-closed)", guard.getName(), e);
                decision = new ResponseGuardDecision.Block(guard.getName(), "Guard threw exception: " + e.getMessage());
            }
            decisions.add(decision);
        }

        return List.copyOf(decisions);
    }

    /**
     * Returns {@code true} if there are no configured guards.
     */
    public boolean isEmpty() {
        return requestGuards.isEmpty() && responseGuards.isEmpty();
    }
}
