package org.jemule.core.event;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Counts the events of every type: total since startup and number during the last minute (searches per minute,
 * logins per minute...). Subscribed to every event; shown by the admin command 'events'.
 */
public class EventStatistics implements Consumer<Event> {
    private static final int WINDOW_SECONDS = 60;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    public void accept(Event event) {
        counters.computeIfAbsent(event.getType(), k -> new Counter()).add(event.getTimestamp().getEpochSecond());
    }

    /** Totals since startup, by event type (sorted). */
    public Map<String, Long> totals() {
        Map<String, Long> result = new TreeMap<>();
        counters.forEach((type, c) -> result.put(type, c.total.sum()));
        return result;
    }

    /** Events of the last 60 seconds, by event type (sorted). */
    public Map<String, Long> lastMinute(long nowEpochSecond) {
        Map<String, Long> result = new TreeMap<>();
        counters.forEach((type, c) -> result.put(type, c.lastMinute(nowEpochSecond)));
        return result;
    }

    /** One bucket per second of the window, reset when the second it holds is too old. */
    private static final class Counter {
        private final LongAdder total = new LongAdder();
        private final long[] seconds = new long[WINDOW_SECONDS];
        private final long[] counts = new long[WINDOW_SECONDS];

        void add(long epochSecond) {
            total.increment();
            int i = (int) Math.floorMod(epochSecond, WINDOW_SECONDS);
            synchronized (this) {
                if (seconds[i] != epochSecond) {
                    seconds[i] = epochSecond;
                    counts[i] = 0;
                }
                counts[i]++;
            }
        }

        synchronized long lastMinute(long now) {
            long n = 0;
            for (int i = 0; i < WINDOW_SECONDS; i++) {
                if (now - seconds[i] < WINDOW_SECONDS && seconds[i] <= now) n += counts[i];
            }
            return n;
        }
    }
}
