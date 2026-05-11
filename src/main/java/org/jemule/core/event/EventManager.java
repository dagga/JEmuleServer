package org.jemule.core.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class EventManager {
    private final Map<String, List<Consumer<Event>>> listeners = new ConcurrentHashMap<>();
    private final List<Consumer<Event>> allEventsListeners = new CopyOnWriteArrayList<>();

    public void subscribe(String eventType, Consumer<Event> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void subscribeAll(Consumer<Event> listener) {
        allEventsListeners.add(listener);
    }

    public void broadcast(Event event) {
        // Notify specific listeners
        List<Consumer<Event>> typeListeners = listeners.get(event.getType());
        if (typeListeners != null) {
            typeListeners.forEach(listener -> listener.accept(event));
        }
        
        // Notify "all events" listeners
        allEventsListeners.forEach(listener -> listener.accept(event));
    }
}
