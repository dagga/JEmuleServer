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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-wide side of OFFERFILES v1 (doc/offerfiles-v1.md): the ceiling on files per second from all v1 connections
 * together, served in arrival order, and the counters shown in the admin panel. A batch waits in the thread of its
 * connection, which reads nothing meanwhile: the client is held back by TCP, nothing is queued in the server.
 */
public final class OfferFilesV1 {

    /** Time source and sleep, replaced in tests. */
    public interface Clock {
        long nanoTime();

        void sleep(long nanos) throws InterruptedException;
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long nanoTime() { return System.nanoTime(); }
        @Override public void sleep(long nanos) throws InterruptedException { TimeUnit.NANOSECONDS.sleep(nanos); }
    };

    private final Clock clock;
    private OfferPacer ceiling;
    private int ceilingRate;

    private final AtomicLong loginsAsked = new AtomicLong();
    private final AtomicLong sessionsAdvertised = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong records = new AtomicLong();
    private final AtomicLong paceWaits = new AtomicLong();
    private final AtomicLong paceWaitNanos = new AtomicLong();
    private final AtomicLong ceilingWaits = new AtomicLong();
    private final AtomicLong ceilingWaitNanos = new AtomicLong();
    private final AtomicLong ceilingLongestNanos = new AtomicLong();
    private final AtomicLong ceilingWaitingNow = new AtomicLong();
    private final AtomicLong oversizedBatches = new AtomicLong();
    private final AtomicLong hardLimitRejects = new AtomicLong();
    private final AtomicLong softLimitIgnored = new AtomicLong();

    public OfferFilesV1() {
        this(SYSTEM_CLOCK);
    }

    public OfferFilesV1(Clock clock) {
        this.clock = clock;
    }

    /**
     * Waits until a batch of {@code count} files may be processed: first the connection's own pace, then the
     * server-wide ceiling. Nothing is dropped, a batch delivered early only waits.
     *
     * @param globalRecordsPerSec the live ceiling, 0 for none
     */
    public void awaitTurn(OfferFilesSession session, long count, int globalRecordsPerSec) throws InterruptedException {
        batches.incrementAndGet();
        records.addAndGet(count);

        long wait = session.pacer().reserve(count, clock.nanoTime());
        if (wait > 0) {
            paceWaits.incrementAndGet();
            paceWaitNanos.addAndGet(wait);
            clock.sleep(wait);
        }

        OfferPacer global = ceiling(globalRecordsPerSec);
        if (global == null) return;
        wait = global.reserve(count, clock.nanoTime());
        if (wait > 0) {
            ceilingWaits.incrementAndGet();
            ceilingWaitNanos.addAndGet(wait);
            ceilingLongestNanos.accumulateAndGet(wait, Math::max);
            ceilingWaitingNow.incrementAndGet();
            try {
                clock.sleep(wait);
            } finally {
                ceilingWaitingNow.decrementAndGet();
            }
        }
    }

    /** The server-wide bucket: one second of files; a new rate starts a new bucket. */
    private synchronized OfferPacer ceiling(int recordsPerSec) {
        if (recordsPerSec <= 0) return null;
        if (ceiling == null || ceilingRate != recordsPerSec) {
            ceiling = new OfferPacer(recordsPerSec, 1_000_000_000L);
            ceilingRate = recordsPerSec;
        }
        return ceiling;
    }

    public void loginAsked() { loginsAsked.incrementAndGet(); }
    public void sessionAdvertised() { sessionsAdvertised.incrementAndGet(); }
    public void oversizedBatch() { oversizedBatches.incrementAndGet(); }
    public void hardLimitReject() { hardLimitRejects.incrementAndGet(); }
    public void softLimitIgnored(long files) { softLimitIgnored.addAndGet(files); }

    public long loginsAsked() { return loginsAsked.get(); }
    public long sessionsAdvertised() { return sessionsAdvertised.get(); }
    public long batches() { return batches.get(); }
    public long records() { return records.get(); }
    public long paceWaits() { return paceWaits.get(); }
    public long ceilingWaits() { return ceilingWaits.get(); }
    public long oversizedBatches() { return oversizedBatches.get(); }
    public long hardLimitRejects() { return hardLimitRejects.get(); }
    public long softLimitIgnored() { return softLimitIgnored.get(); }

    /** Counters for the admin panel; waits in milliseconds. */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("loginsAsked", loginsAsked.get());
        stats.put("sessionsAdvertised", sessionsAdvertised.get());
        stats.put("batches", batches.get());
        stats.put("records", records.get());
        stats.put("paceWaits", paceWaits.get());
        stats.put("paceWaitMs", paceWaitNanos.get() / 1_000_000);
        stats.put("ceilingWaits", ceilingWaits.get());
        stats.put("ceilingWaitMs", ceilingWaitNanos.get() / 1_000_000);
        stats.put("ceilingLongestWaitMs", ceilingLongestNanos.get() / 1_000_000);
        stats.put("ceilingWaitingNow", ceilingWaitingNow.get());
        stats.put("oversizedBatches", oversizedBatches.get());
        stats.put("hardLimitRejects", hardLimitRejects.get());
        stats.put("softLimitIgnoredFiles", softLimitIgnored.get());
        return stats;
    }
}
