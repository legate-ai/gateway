package io.legate.server.router;

import io.legate.server.admin.AdminHandler;
import io.legate.server.handler.ChatCompletionHandler;
import io.legate.server.handler.ModelsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;

/**
 * Router configuration for all Legate API endpoints.
 *
 * <h3>Routes</h3>
 * <ul>
 *   <li>{@code POST /v1/chat/completions} — chat completion (streaming or non-streaming)</li>
 *   <li>{@code GET  /v1/models}           — list configured models</li>
 *   <li>{@code POST /admin/keys}          — create virtual key</li>
 *   <li>{@code GET  /admin/keys}          — list virtual keys</li>
 *   <li>{@code DELETE /admin/keys/{id}}   — revoke virtual key</li>
 *   <li>{@code POST /admin/config/reload} — hot-reload config</li>
 *   <li>{@code GET  /admin/audit}         — query audit log</li>
 *   <li>{@code GET  /admin/stats}         — real-time stats</li>
 *   <li>{@code DELETE /admin/cache}       — clear response cache</li>
 * </ul>
 */
@Configuration public class LegateRouterConfig {

    @Bean
    public RouterFunction<ServerResponse> legateRoutes(ChatCompletionHandler chatCompletionHandler, ModelsHandler modelsHandler, AdminHandler adminHandler) {
        return RouterFunctions
                // ── Chat completions ──────────────────────────────────────────────
                .route(POST("/v1/chat/completions").and(accept(MediaType.APPLICATION_JSON)), chatCompletionHandler::handleRequest)

                // ── Model list ────────────────────────────────────────────────────
                .andRoute(GET("/v1/models"), modelsHandler::listModels)

                // ── Admin — virtual keys ──────────────────────────────────────────
                .andRoute(POST("/admin/keys"), adminHandler::createKey).andRoute(GET("/admin/keys"), adminHandler::listKeys).andRoute(DELETE("/admin/keys/{keyId}"), adminHandler::revokeKey)

                // ── Admin — config read ───────────────────────────────────────────
                .andRoute(GET("/admin/config"), adminHandler::getConfig)

                // ── Admin — config reload ─────────────────────────────────────────
                .andRoute(POST("/admin/config/reload"), adminHandler::reloadConfig)

                // ── Admin — audit log ─────────────────────────────────────────────
                .andRoute(GET("/admin/audit"), adminHandler::queryAudit)

                // ── Admin — stats ─────────────────────────────────────────────────
                .andRoute(GET("/admin/stats"), adminHandler::getStats)

                // ── Admin — cache management ──────────────────────────────────────
                .andRoute(DELETE("/admin/cache"), adminHandler::clearCache);
    }
}
