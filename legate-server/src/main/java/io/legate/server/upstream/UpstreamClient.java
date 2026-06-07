package io.legate.server.upstream;

import io.legate.core.exception.UpstreamException;
import io.legate.core.provider.ProviderHttpRequest;
import io.legate.core.provider.ProviderHttpResponse;
import io.legate.core.routing.ResolvedEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client for calling upstream LLM providers.
 * One WebClient is built per provider base URL (scheme+host+port) and reused across requests.
 */
@Component
public class UpstreamClient {
    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final WebClient.Builder protoBuilder;
    private final ConcurrentHashMap<String, WebClient> clients = new ConcurrentHashMap<>();

    public UpstreamClient(WebClient.Builder webClientBuilder) {
        this.protoBuilder = webClientBuilder;
    }


    public Mono<ProviderHttpResponse> sendRequest(
        ProviderHttpRequest request,
        ResolvedEndpoint endpoint
    ) {
        log.debug("Sending {} request to {}", request.method(), request.url());

        return clientFor(request.url())
            .method(HttpMethod.valueOf(request.method()))
            .uri(pathOf(request.url()))
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

    public Flux<String> streamRequest(
        ProviderHttpRequest request,
        ResolvedEndpoint endpoint
    ) {
        log.debug("Sending streaming {} request to {}", request.method(), request.url());

        return clientFor(request.url())
            .method(HttpMethod.valueOf(request.method()))
            .uri(pathOf(request.url()))
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
                            new UpstreamException(
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

    private WebClient clientFor(String url) {
        return clients.computeIfAbsent(baseOf(url),
            base -> protoBuilder.clone().baseUrl(base).build());
    }

    private static String baseOf(String url) {
        URI uri = URI.create(url);
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
    }

    private static String pathOf(String url) {
        URI uri = URI.create(url);
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        return query != null ? path + "?" + query : path;
    }

    private String extractDataFromSSE(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        if (line.startsWith("data: ")) {
            return line.substring(6).trim();
        }
        return line.trim();
    }

    private Map<String, String> extractHeaders(HttpHeaders headers) {
        return headers.toSingleValueMap();
    }
}
