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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Tells when the heap cannot take more indexed files. The index lives in the old generation: once a collection
 * leaves it {@link #MAX_LIVE_RATIO} full, new files are refused, whatever maxFiles allows. Above that, the full
 * collections follow each other and free little, on one vCPU the clients wait, and an OutOfMemoryError makes H2
 * close the database.
 * <p>
 * The live size is the old generation's use after its last collection: the files still referenced, not the
 * garbage waiting for the next one. It only changes at a collection, so the guard does not flap.
 */
public final class HeapGuard {
    /** Share of the old generation that the live data may fill before new files are refused. */
    public static final double MAX_LIVE_RATIO = 0.80;

    private final LongSupplier liveBytes;
    private final LongSupplier maxBytes;

    HeapGuard(LongSupplier liveBytes, LongSupplier maxBytes) {
        this.liveBytes = liveBytes;
        this.maxBytes = maxBytes;
    }

    /** The guard of this JVM's old generation; never full when the JVM does not report it. */
    public static HeapGuard jvm() {
        MemoryPoolMXBean old = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP && p.isCollectionUsageThresholdSupported())
                .filter(p -> {
                    String name = p.getName().toLowerCase(Locale.ROOT);
                    return name.contains("old") || name.contains("tenured");
                })
                .max(Comparator.comparingLong(p -> p.getUsage().getMax()))
                .orElse(null);
        if (old == null) return new HeapGuard(() -> 0, () -> 0);
        return new HeapGuard(() -> {
            MemoryUsage afterGc = old.getCollectionUsage();
            return afterGc == null ? 0 : afterGc.getUsed();
        }, () -> old.getUsage().getMax());
    }

    /** True when the live data fills the old generation beyond {@link #MAX_LIVE_RATIO}. */
    public boolean full() {
        long max = maxBytes.getAsLong();
        return max > 0 && liveBytes.getAsLong() >= max * MAX_LIVE_RATIO;
    }

    /** Live data after the last collection of the old generation, in bytes (0 before the first one). */
    public long liveBytes() {
        return liveBytes.getAsLong();
    }

    /** Size the old generation may grow to, in bytes (0 when unknown). */
    public long maxBytes() {
        return Math.max(0, maxBytes.getAsLong());
    }
}
