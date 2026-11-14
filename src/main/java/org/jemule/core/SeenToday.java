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

import java.util.function.IntSupplier;

/**
 * The files of the index seen today (published by a client, found by a search, brought by the index update or the
 * relay feed), so that the day a file was last seen is written to the database once a day at most: a client
 * publishing its files at every login, or a file found by every search, costs no database write after the first one
 * of the day. Forgotten when the day changes.
 * <p>
 * Bounded, so that a server short of heap keeps it small: 16 bytes per slot of an {@link Md4Set}, one 64th of the
 * heap at most (8 MB for -Xmx560m, 64 MB at most). Beyond, the files not remembered are written each time they are
 * seen, the database skipping the rows already written today ({@link DatabaseManager#TOUCH_FILE}): more database
 * reads, never a file purged although seen.
 */
final class SeenToday {
    /** Fewest and most slots of the set. */
    private static final int MIN_SLOTS = 1 << 12, MAX_SLOTS = 1 << 22;

    private final IntSupplier clock;
    private final int capacity;
    private final Md4Set ids = new Md4Set();
    private int day = Integer.MIN_VALUE;

    /**
     * @param clock    the current day
     * @param capacity most files remembered
     */
    SeenToday(IntSupplier clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
    }

    /** The files a heap of {@code maxHeapBytes} remembers: 3/4 of the slots, so that the set never grows beyond. */
    static int capacityFor(long maxHeapBytes) {
        long slots = Long.highestOneBit(Math.max(1, maxHeapBytes / 64 / 16));
        slots = Math.clamp(slots, MIN_SLOTS, MAX_SLOTS);
        return (int) (slots * 3 / 4);
    }

    /**
     * Records that a file was seen.
     *
     * @param id the key of the file in the index
     * @return true if its day must be written: the first time today, or the set is full
     */
    synchronized boolean mark(String id) {
        roll();
        if (ids.contains(id)) return false;
        if (ids.size() < capacity) ids.add(id);
        return true;
    }

    /** Whether a file was seen today (and remembered). */
    synchronized boolean contains(String id) {
        roll();
        return ids.contains(id);
    }

    private void roll() {
        int today = clock.getAsInt();
        if (today != day) {
            ids.clear();
            day = today;
        }
    }
}
