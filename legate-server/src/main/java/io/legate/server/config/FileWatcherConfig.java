package io.legate.server.config;

import io.legate.core.config.LegateConfig;
import io.legate.core.routing.RoutingEngine;
import io.legate.spring.properties.LegateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Watches the Legate YAML configuration file for changes and triggers hot-reload.
 *
 * <p>When the file changes, a 500 ms debounce timer fires, then Legate:</p>
 * <ol>
 *   <li>Re-reads the YAML configuration.</li>
 *   <li>Validates it via {@code ConfigValidator}.</li>
 *   <li>If valid: atomically swaps the routing engine config.</li>
 *   <li>If invalid: logs errors and retains the existing config.</li>
 * </ol>
 *
 * <p>Watching is disabled if the config file path is not set or does not exist.</p>
 */
@Component
public class FileWatcherConfig implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherConfig.class);
    private static final long DEBOUNCE_MS = 500;

    private final RoutingEngine routingEngine;
    private final LegateProperties legateProperties;
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "legate-file-watcher");
            t.setDaemon(true);
            return t;
        });
    private final AtomicReference<ScheduledFuture<?>> pendingReload = new AtomicReference<>();
    private Thread watchThread;
    private WatchService watchService;

    public FileWatcherConfig(
        RoutingEngine routingEngine,
        LegateProperties legateProperties,
        @Value("${legate.config.watch-path:}") String watchPath
    ) {
        this.routingEngine    = routingEngine;
        this.legateProperties = legateProperties;
        if (watchPath != null && !watchPath.isBlank()) {
            startWatching(Path.of(watchPath));
        } else {
            log.debug("FileWatcherConfig: no watch-path configured, hot-reload disabled.");
        }
    }

    /**
     * Triggers an immediate config reload. Called from the admin endpoint.
     *
     * @return {@code true} if reload succeeded; {@code false} if validation failed
     */
    public boolean reloadNow() {
        try {
            LegateConfig newConfig = legateProperties.toLegateConfig();
            routingEngine.reload(newConfig);
            log.info("Config reloaded on demand.");
            return true;
        } catch (Exception e) {
            log.error("Config reload failed: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("FileWatcherConfig: failed to close WatchService on shutdown", e);
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    // -------------------------------------------------------------------------

    private void startWatching(Path configPath) {
        if (!Files.exists(configPath)) {
            log.debug("Config file '{}' not found — hot-reload disabled.", configPath);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            configPath.getParent().register(watchService, ENTRY_MODIFY);

            watchThread = new Thread(() -> runWatchLoop(configPath, configPath.getFileName()), "legate-file-watcher");
            watchThread.setDaemon(true);
            watchThread.start();

            log.info("FileWatcherConfig: watching '{}' for changes.", configPath);
        } catch (IOException e) {
            log.warn("FileWatcherConfig: could not start file watcher: {}", e.getMessage());
        }
    }

    private void runWatchLoop(Path configPath, Path fileName) {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                Path changed = (Path) event.context();
                if (changed.equals(fileName)) {
                    scheduleReload();
                }
            }
            if (!key.reset()) break;
        }
    }

    private void scheduleReload() {
        ScheduledFuture<?> pending = pendingReload.getAndSet(null);
        if (pending != null) pending.cancel(false);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            log.info("Config file changed — reloading...");
            reloadNow();
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);

        pendingReload.set(future);
    }
}
