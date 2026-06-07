package io.legate.server.upstream;

import io.legate.core.provider.ProviderHttpRequest;
import io.legate.core.provider.ProviderHttpResponse;
import io.legate.core.routing.ResolvedEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client for calling upstream LLM providers.
 * Wraps Spring WebClient with timeout configuration per endpoint.
 */
@Component
public class UpstreamClient {
    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final WebClient.Builder webClientBuilder;
    private final ConcurrentHashMap<String, WebClient> clientCache = new ConcurrentHashMap<>();

    public UpstreamClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * Returns a cached {@link WebClient} for the given base URL, creating one on first access.
     * Caching avoids reconstructing the Netty connection pool on every request.
     */
    private WebClient getOrCreateClient(String baseUrl) {
        return clientCache.computeIfAbsent(baseUrl, url ->
                webClientBuilder.clone().baseUrl(url).build());
    }

    /**
     * Sends a non-streaming request to the provider.
     *
     * @param request the HTTP request
     * @param endpoint the resolved endpoint with timeouts
     * @return the HTTP response
     */
    public Mono<ProviderHttpResponse> sendRequest(
        ProviderHttpRequest request,
        ResolvedEndpoint endpoint
    ) {
        log.debug("Sending {} request to {}", request.method(), request.url());

        WebClient webClient = getOrCreateClient(request.url());

        // Use exchangeToMono so all HTTP status codes — including 4xx/5xx — are
        // returned as ProviderHttpResponse rather than thrown as WebClientResponseException.
        // The handler decides whether a non-2xx status is an error.
        return webClient
            .method(org.springframework.http.HttpMethod.valueOf(request.method()))
            .uri("")
            .headers(h -> request.headers().forEach(h::add))
            .bodyValue(request.body() != null ? request.body() : "")
            .exchangeToMono(clientResponse ->
                clientResponse.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> new ProviderHttpResponse(
                        clientResponse.statusCode().value(),
                        extractHeaders(clientResponse.headers().asHttpHeaders()),
                        body))
            )
            .timeout(endpoint.readTimeout())
            .doOnSuccess(response ->
                log.debug("Received response: status={}", response.statusCode())
            )
            .doOnError(error ->
                log.error("Request failed: {}", error.getMessage())
            );
    }

    /**
     * Sends a streaming request to the provider and returns SSE lines.
     *
     * @param request the HTTP request
     * @param endpoint the resolved endpoint with timeouts
     * @return flux of SSE data lines (with "data: " prefix removed)
     */
    public Flux<String> streamRequest(
        ProviderHttpRequest request,
        ResolvedEndpoint endpoint
    ) {
        log.debug("Sending streaming {} request to {}", request.method(), request.url());

        WebClient webClient = getOrCreateClient(request.url());

        // Use exchangeToFlux so error status codes are surfaced as UpstreamException
        // rather than thrown as WebClientResponseException before any data is read.
        return webClient
            .method(org.springframework.http.HttpMethod.valueOf(request.method()))
            .uri("")
            .headers(h -> {
                request.headers().forEach(h::add);
                h.setContentType(MediaType.APPLICATION_JSON);
                h.set(HttpHeaders.ACCEPT, "text/event-stream");
            })
            .bodyValue(request.body() != null ? request.body() : "")
            .exchangeToFlux(clientResponse -> {
                if (clientResponse.statusCode().isError()) {
                    return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMapMany(body -> Flux.error(
                            new io.legate.core.exception.UpstreamException(
                                "upstream", clientResponse.statusCode().value(), body)));
                }
                return clientResponse.bodyToFlux(String.class);
            })
            .timeout(endpoint.readTimeout())
            .map(this::extractDataFromSSE)
            .filter(line -> line != null && !line.isBlank())
            .doOnSubscribe(sub ->
                log.debug("Started streaming from {}", request.url())
            )
            .doOnComplete(() ->
                log.debug("Streaming completed from {}", request.url())
            )
            .doOnError(error ->
                log.error("Streaming failed: {}", error.getMessage())
            );
    }

    /**
     * Extracts data from an SSE line.
     * Input: "data: {json}" or "data: [DONE]"
     * Output: "{json}" or "[DONE]"
     */
    private String extractDataFromSSE(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        // Handle SSE format: "data: <content>"
        if (line.startsWith("data: ")) {
            return line.substring(6).trim();
        }

        // Handle lines without prefix (some providers)
        return line.trim();
    }

    /**
     * Converts Spring HttpHeaders to simple Map<String, String>.
     */
    private Map<String, String> extractHeaders(HttpHeaders headers) {
        return headers.toSingleValueMap();
    }
}
