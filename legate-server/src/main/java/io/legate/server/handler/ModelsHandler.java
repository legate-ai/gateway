package io.legate.server.handler;

import io.legate.core.routing.RoutingEngine;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Handles {@code GET /v1/models} — returns the list of model IDs that Legate
 * has configured endpoints for, in OpenAI-compatible list format.
 */
@Component
public class ModelsHandler {

    private final RoutingEngine routingEngine;

    public ModelsHandler(RoutingEngine routingEngine) {
        this.routingEngine = routingEngine;
    }

    public Mono<ServerResponse> listModels(ServerRequest request) {
        List<ModelEntry> entries = routingEngine.configuredModels().stream()
            .sorted()
            .map(id -> new ModelEntry(id, "model", "legate"))
            .toList();

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new ModelList("list", entries));
    }

    // ── Response types ────────────────────────────────────────────────────────

    record ModelEntry(String id, String object, String owned_by) {}

    record ModelList(String object, List<ModelEntry> data) {}
}
