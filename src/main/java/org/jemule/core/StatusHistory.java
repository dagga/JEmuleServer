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
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * The recent curves of the Status view: the figures of the server that change over time, sampled every
 * {@link #INTERVAL_MS} and kept {@link #RETENTION_MS} in memory (a restart starts them again). The longer curves of the
 * index size and of the clients are the {@link MetricsHistory}, kept in the database.
 * <p>
 * The metrics are registered once, before the first sample; each sample is one array of their values (NaN when a
 * value cannot be read), about 100 bytes, so that a day of samples stays under 200 KB.
 */
public final class StatusHistory {
    /** One sample a minute. */
    public static final long INTERVAL_MS = 60_000L;
    /** The last 24 hours. */
    public static final long RETENTION_MS = 24L * 3_600_000L;

    /** How a metric reads on a curve. */
    public enum Kind {
        /** A level at the time of the sample (clients connected, heap used): drawn as it is. */
        GAUGE("gauge"),
        /** A count since the start (connections refused): drawn as its increase between two samples. */
        COUNTER("counter");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** A figure drawn over time. */
    public record Metric(String key, String label, String unit, Kind kind, DoubleSupplier value) {}

    /** One sample: its time and the value of each metric, in their order of registration. */
    public record Sample(long time, double[] values) {}

    private final List<Metric> metrics = new ArrayList<>();
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /**
     * Registers a metric, before the first sample (the samples are arrays in the order of the metrics).
     *
     * @throws IllegalStateException once a sample was taken
     */
    public synchronized StatusHistory add(String key, String label, String unit, Kind kind, DoubleSupplier value) {
        if (!samples.isEmpty()) throw new IllegalStateException("The metrics are registered before the first sample");
        metrics.add(new Metric(key, label, unit, kind, value));
        return this;
    }

    /** The metrics, in their order of registration. */
    public synchronized List<Metric> metrics() {
        return Collections.unmodifiableList(new ArrayList<>(metrics));
    }

    /** Reads every metric now and forgets the samples older than the retention. */
    public void record(long now) {
        List<Metric> current;
        synchronized (this) {
            current = new ArrayList<>(metrics);
        }
        double[] values = new double[current.size()];
        for (int i = 0; i < values.length; i++) {
            double value;
            try {
                value = current.get(i).value().getAsDouble();
            } catch (RuntimeException e) {
                value = Double.NaN; // a figure that cannot be read now: a gap in its curve
            }
            values[i] = Double.isFinite(value) ? value : Double.NaN;
        }
        synchronized (this) {
            samples.add(new Sample(now, values));
            while (!samples.isEmpty() && now - samples.peekFirst().time() >= RETENTION_MS) samples.removeFirst();
        }
    }

    /** Samples taken since {@code from}, oldest first. */
    public synchronized List<Sample> since(long from) {
        List<Sample> result = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.time() >= from) result.add(sample);
        }
        return result;
    }
}
