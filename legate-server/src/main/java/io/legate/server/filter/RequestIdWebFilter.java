package io.legate.server.filter;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFilter that generates a unique request ID for every request.
 * Sets X-Legate-Request-Id response header and stores in exchange attributes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {
    private static final String REQUEST_ID_ATTRIBUTE = "legate.requestId";
    private static final String REQUEST_ID_HEADER = "X-Legate-Request-Id";
    private static final String REQUEST_ID_PREFIX = "req_";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Generate unique request ID
        String requestId = REQUEST_ID_PREFIX + NanoIdUtils.randomNanoId();

        // Store in exchange attributes
        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);

        // Set response header
        exchange.getResponse().getHeaders().add(REQUEST_ID_HEADER, requestId);

        return chain.filter(exchange);
    }

    /**
     * Extracts the request ID from the exchange.
     */
    public static String getRequestId(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(REQUEST_ID_ATTRIBUTE);
    }
}
