package io.legate.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;

/**
 * Simple async event bus implementation using ExecutorService.
 * Uses a bounded queue to prevent memory issues from backpressure.
 * Events are dispatched on a dedicated thread pool.
 */
public class AsyncEventBus implements EventBus {
    private static final Logger log = LoggerFactory.getLogger(AsyncEventBus.class);
    private static final int MAX_QUEUE_SIZE = 10_000;
    private static final int THREAD_POOL_SIZE = 4;

    private final ConcurrentHashMap<Class<? extends LegateEvent>, CopyOnWriteArrayList<EventSubscriber<?>>> subscribers;
    private final ExecutorService executorService;
    private final BlockingQueue<LegateEvent> eventQueue;
    private final Thread dispatchThread;
    private volatile boolean running;

    public AsyncEventBus() {
        this.subscribers = new ConcurrentHashMap<>();
        this.eventQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        this.executorService = Executors.newFixedThreadPool(
                THREAD_POOL_SIZE,
                runnable -> {
                    Thread eventSubscriber = new Thread(runnable, "legate-event-subscriber");
                    eventSubscriber.setDaemon(true);
                    return eventSubscriber;
                }
        );
        this.running = true;
        this.dispatchThread = new Thread(this::dispatchLoop, "legate-event-dispatcher");
        this.dispatchThread.setDaemon(true);
        this.dispatchThread.start();

        log.info("AsyncEventBus started with queue size {} and {} subscriber threads",
                MAX_QUEUE_SIZE, THREAD_POOL_SIZE);
    }

    @Override
    public void publish(LegateEvent event) {
        if (!running) {
            log.warn("EventBus is shut down, dropping event: {}", event.getClass().getSimpleName());
            return;
        }

        boolean offered = eventQueue.offer(event);
        if (!offered) {
            log.error("EventBus queue full, dropping event: {} (requestId={})",
                    event.getClass().getSimpleName(), event.requestId());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends LegateEvent> void subscribe(Class<E> eventType, EventSubscriber<E> subscriber) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add((EventSubscriber<LegateEvent>) subscriber);

        log.debug("Registered subscriber for event type: {}", eventType.getSimpleName());
    }

    @Override
    public void shutdown() {
        log.info("Shutting down EventBus...");
        running = false;

        try {
            dispatchThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for dispatch thread to finish");
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                List<Runnable> droppedTasks = executorService.shutdownNow();
                log.warn("EventBus executor did not terminate gracefully, dropped {} tasks",
                        droppedTasks.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }

        log.info("EventBus shutdown complete");
    }

    /**
     * Main dispatch loop - runs on dedicated thread, pulling events from queue
     * and dispatching to subscribers.
     */
    private void dispatchLoop() {
        while (running || !eventQueue.isEmpty()) {
            try {
                LegateEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    dispatchEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Dispatch thread interrupted");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in event dispatch loop", e);
            }
        }
        log.debug("Dispatch loop exiting");
    }

    /**
     * Dispatches an event to all registered subscribers for that event type.
     */
    @SuppressWarnings("unchecked")
    private void dispatchEvent(LegateEvent event) {
        Class<?> eventClass = event.getClass();

        // Get subscribers for this exact event type
        List<EventSubscriber<?>> eventSubscribers = subscribers.get(eventClass);
        if (eventSubscribers == null || eventSubscribers.isEmpty()) {
            return;
        }

        // Dispatch to each subscriber asynchronously
        for (EventSubscriber<?> subscriber : eventSubscribers) {
            executorService.submit(() -> {
                try {
                    ((EventSubscriber<LegateEvent>) subscriber).onEvent(event);
                } catch (Exception e) {
                    log.error("Subscriber threw exception handling event: {} (requestId={})",
                            event.getClass().getSimpleName(), event.requestId(), e);
                }
            });
        }
    }
}
