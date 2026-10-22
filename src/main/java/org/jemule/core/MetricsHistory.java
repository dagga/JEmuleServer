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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Number of indexed files and of connected clients, sampled every {@link #INTERVAL_MS} for the curves of the admin
 * panel. The samples of the last {@link #RETENTION_MS} are kept in memory (at most about 9,000) and in the database,
 * so that the curves survive a restart.
 */
public final class MetricsHistory {
    public static final long INTERVAL_MS = 5 * 60_000L;
    /** A rolling month, plus a day so that a 30-day curve always has its first point. */
    public static final long RETENTION_MS = 31L * 24 * 3_600_000L;

    /** One sample. */
    public record Sample(long time, int files, int clients) {}

    private final DatabaseManager db;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /** Loads the samples of the last month from {@code db}, which may be null (nothing is then stored). */
    public MetricsHistory(DatabaseManager db) {
        this(db, System.currentTimeMillis());
    }

    MetricsHistory(DatabaseManager db, long now) {
        this.db = db;
        if (db != null) {
            for (long[] sample : db.loadMetricsSamples(now - RETENTION_MS)) {
                samples.add(new Sample(sample[0], (int) sample[1], (int) sample[2]));
            }
        }
    }

    /** Adds a sample, in memory and in the database, and forgets those older than the retention. */
    public void record(long now, int files, int clients) {
        synchronized (this) {
            samples.add(new Sample(now, files, clients));
            for (Iterator<Sample> it = samples.iterator(); it.hasNext(); ) {
                if (now - it.next().time() < RETENTION_MS) break;
                it.remove();
            }
        }
        if (db != null) db.saveMetricsSample(now, files, clients, now - RETENTION_MS);
    }

    /** Samples taken since {@code from}, oldest first. */
    public synchronized List<Sample> since(long from) {
        List<Sample> result = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.time() >= from) result.add(sample);
        }
        return result;
    }

    /** The last sample taken at or before {@code time}, or null if there is none. */
    public synchronized Sample at(long time) {
        Sample found = null;
        for (Sample sample : samples) {
            if (sample.time() > time) break;
            found = sample;
        }
        return found;
    }

    /** The oldest sample kept, or null. */
    public synchronized Sample oldest() {
        return samples.peekFirst();
    }
}
