package io.legate.server.handler.pipeline.step;

import io.legate.core.cache.CacheKey;
import io.legate.core.cache.CachedResponse;
import io.legate.core.cache.ResponseCache;
import io.legate.core.config.LegateConfig;
import io.legate.core.context.RequestContext;
import io.legate.server.constants.LegateHeaders;
import io.legate.server.handler.pipeline.PostResponsePipelineStep;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Post-response step: writes the response to the cache when caching is enabled
 * and the request/response is cacheable and the client did not send {@code x-legate-cache: skip}.
 * Order 30 — last step, only makes sense after cost/event steps complete.
 */
@Component
@Order(30)
public class CacheWriteStep implements PostResponsePipelineStep {

    private final ResponseCache responseCache;
    private final LegateConfig legateConfig;

    public CacheWriteStep(ResponseCache responseCache, LegateConfig legateConfig) {
        this.responseCache = responseCache;
        this.legateConfig = legateConfig;
    }

    @Override
    public int getOrder() { return 30; }

    @Override
    public void execute(RequestContext context) {
        // Only cache non-streaming responses
        if (context.getResponse() == null) return;
        if (!isCacheEnabled()) return;
        if (!CacheKey.isCacheable(context.getEffectiveRequest())) return;

        String directive = StringUtils.defaultString(
            context.getRequestHeaders().get(LegateHeaders.CACHE_STATUS.toLowerCase()));
        if (LegateHeaders.CACHE_SKIP.equalsIgnoreCase(directive)) return;

        CacheKey key = CacheKey.from(context.getEffectiveRequest());
        responseCache.put(key, new CachedResponse(context.getResponse()));
    }

    private boolean isCacheEnabled() {
        return legateConfig.cache() != null && legateConfig.cache().enabled();
    }
}
