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

/**
 * Token bucket of OFFERFILES v1 (doc/offerfiles-v1.md), counted in offered files: it holds {@code records} files,
 * starts full and refills at {@code records} files per period. A batch arriving before its files are available is not
 * refused: {@link #reserve} tells how long to wait before processing it, and the next batch queues behind it.
 * Idle time is not banked beyond a full bucket.
 */
public final class OfferPacer {
    private final long records;
    private final long periodNanos;
    /** Time at which the bucket is full again after the batches already reserved (virtual scheduling, GCRA). */
    private long fullAt;
    private boolean started;

    /**
     * @param records     files the bucket holds and gets back per period, at least 1
     * @param periodNanos refill period in nanoseconds, at least 1
     */
    public OfferPacer(long records, long periodNanos) {
        if (records < 1 || periodNanos < 1) {
            throw new IllegalArgumentException("records and periodNanos must be at least 1");
        }
        this.records = records;
        this.periodNanos = periodNanos;
    }

    public long records() { return records; }

    /**
     * Takes {@code count} files from the bucket, in arrival order.
     *
     * @param count    files of the batch, at most {@link org.jemule.config.OfferFilesConfig#MAX_BATCH}
     * @param nowNanos current {@link System#nanoTime()}
     * @return nanoseconds to wait before processing the batch, 0 if it may be processed at once
     */
    public synchronized long reserve(long count, long nowNanos) {
        if (!started || fullAt - nowNanos < 0) {
            fullAt = nowNanos;
            started = true;
        }
        // count <= 100,000 and periodNanos <= 3.6e12 (one hour): no overflow
        long cost = Math.multiplyExact(count, periodNanos) / records;
        long due = fullAt + cost - periodNanos;
        fullAt += cost;
        return Math.max(0, due - nowNanos);
    }
}
