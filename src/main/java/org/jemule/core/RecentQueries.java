/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remembers the remote searches (Kad, eD2K servers) started recently, so that an identical search is sent at most
 * {@code maxStarts} times within {@code windowMs}: the files the first ones found are already indexed, and the local
 * search returns them. At most {@code maxQueries} are remembered; the oldest are forgotten first.
 */
public final class RecentQueries {
    private final long windowMs;
    private final int maxStarts;
    private final int maxQueries;
    /** Window of each query, oldest first. */
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();
    private final AtomicLong skipped = new AtomicLong();

    /** Starts of one query since the first one of its window. */
    private static final class Window {
        private final long firstStart;
        private int starts = 1;

        private Window(long firstStart) {
            this.firstStart = firstStart;
        }
    }

    public RecentQueries(long windowMs, int maxStarts, int maxQueries) {
        if (windowMs < 0 || maxStarts < 1 || maxQueries < 1) throw new IllegalArgumentException("Invalid limits");
        this.windowMs = windowMs;
        this.maxStarts = maxStarts;
        this.maxQueries = maxQueries;
    }

    /**
     * Records that {@code query} starts now, unless it already started {@code maxStarts} times within the window.
     *
     * @return false if the query was searched too often recently and must not be sent again
     */
    public boolean tryStart(String query) {
        return tryStart(query, System.currentTimeMillis());
    }

    synchronized boolean tryStart(String query, long now) {
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
            if (now - it.next().firstStart < windowMs) break; // the next ones started later
            it.remove();
        }
        Window window = windows.get(query);
        if (window != null) {
            if (window.starts >= maxStarts) {
                skipped.incrementAndGet();
                return false;
            }
            window.starts++;
            return true;
        }
        windows.put(query, new Window(now));
        if (windows.size() > maxQueries) {
            Iterator<Map.Entry<String, Window>> oldest = windows.entrySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /** Searches not sent because an identical one was sent too often within the window. */
    public long skipped() {
        return skipped.get();
    }

    /** Queries currently remembered. */
    public synchronized int size() {
        return windows.size();
    }
}
