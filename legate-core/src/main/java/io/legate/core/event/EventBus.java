package io.legate.core.event;

/**
 * Event bus for async telemetry and observability.
 * Publishers emit events without blocking. Subscribers process events asynchronously.
 * Subscriber failures NEVER impact request processing.
 */
public interface EventBus {

    /**
     * Publishes an event to all registered subscribers.
     * This method is non-blocking and returns immediately.
     *
     * @param event the event to publish
     */
    void publish(LegateEvent event);

    /**
     * Subscribes to events of a specific type.
     *
     * @param eventType  the class of events to subscribe to
     * @param subscriber the subscriber callback
     * @param <E>        the event type
     */
    <E extends LegateEvent> void subscribe(Class<E> eventType, EventSubscriber<E> subscriber);

    /**
     * Shuts down the event bus, waiting for pending events to be processed.
     */
    void shutdown();
}
