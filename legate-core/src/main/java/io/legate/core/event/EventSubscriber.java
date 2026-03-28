package io.legate.core.event;

/**
 * Subscriber interface for receiving events from the EventBus.
 *
 * @param <E> the event type this subscriber handles
 */
@FunctionalInterface
public interface EventSubscriber<E extends LegateEvent> {

    /**
     * Handles an event. Exceptions thrown by this method are caught and logged
     * by the EventBus - they do not affect other subscribers or the event publisher.
     *
     * @param event the event to handle
     */
    void onEvent(E event);
}
