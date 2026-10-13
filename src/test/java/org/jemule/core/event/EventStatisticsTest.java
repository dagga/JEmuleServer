package org.jemule.core.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventStatisticsTest {

    private static Event event(String type, long epochSecond) {
        return new Event() {
            @Override public Instant getTimestamp() { return Instant.ofEpochSecond(epochSecond); }
            @Override public String getType() { return type; }
            @Override public String getMessage() { return ""; }
        };
    }

    @Test
    void countsTotalsAndTheLastMinute() {
        EventStatistics stats = new EventStatistics();
        long now = 1_000_000;
        stats.accept(event(FileEvent.SEARCHED, now - 200)); // out of the window
        stats.accept(event(FileEvent.SEARCHED, now - 59));
        stats.accept(event(FileEvent.SEARCHED, now));
        stats.accept(event(FileEvent.SEARCHED, now));
        stats.accept(event(ClientEvent.LOGIN, now - 60)); // exactly one minute ago: out of the window

        assertEquals(Map.of(FileEvent.SEARCHED, 4L, ClientEvent.LOGIN, 1L), stats.totals());
        assertEquals(Map.of(FileEvent.SEARCHED, 3L, ClientEvent.LOGIN, 0L), stats.lastMinute(now));
        assertEquals(Map.of(FileEvent.SEARCHED, 0L, ClientEvent.LOGIN, 0L), stats.lastMinute(now + 120));
    }

    @Test
    void anOldSecondIsReplacedInItsBucket() {
        EventStatistics stats = new EventStatistics();
        long now = 2_000_000;
        stats.accept(event("X", now - 60)); // same bucket as now
        stats.accept(event("X", now));
        assertEquals(1L, stats.lastMinute(now).get("X"));
    }

    @Test
    void subscribedListenerSeesBroadcastEvents() {
        EventManager manager = new EventManager();
        EventStatistics stats = new EventStatistics();
        manager.subscribeAll(stats);
        manager.broadcast(new FileEvent(FileEvent.PUBLISHED, "a.avi", "00", "published"));
        assertEquals(1L, stats.totals().get(FileEvent.PUBLISHED));
    }
}
