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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * Tells when the disk of the file index cannot take more files: below {@link #MIN_FREE_BYTES} free, new files are
 * refused, whatever maxFiles allows. The index and the H2 database grow with the files, and a full disk would stop
 * both: Lucene refuses every write, H2 closes the database.
 * <p>
 * The free space is read at most every {@link #CHECK_MILLIS}: the guard is asked for each file added.
 */
public final class DiskGuard {
    private static final Logger log = LoggerFactory.getLogger(DiskGuard.class);
    /** Free space below which new files are refused: room for the merges of the index and the database's growth. */
    public static final long MIN_FREE_BYTES = 512L * 1024 * 1024;
    static final long CHECK_MILLIS = 10_000;

    private final LongSupplier freeBytes;
    private final LongSupplier clock;
    private volatile long checkedAt = Long.MIN_VALUE;
    private volatile boolean full;

    DiskGuard(LongSupplier freeBytes, LongSupplier clock) {
        this.freeBytes = freeBytes;
        this.clock = clock;
    }

    /** The guard of the disk holding {@code path}; never full when {@code path} is null (an index in the heap). */
    public static DiskGuard of(Path path) {
        if (path == null) return new DiskGuard(() -> Long.MAX_VALUE, System::currentTimeMillis);
        return new DiskGuard(() -> {
            try {
                Path existing = path;
                while (existing != null && !Files.exists(existing)) existing = existing.toAbsolutePath().getParent();
                return existing == null ? Long.MAX_VALUE : Files.getFileStore(existing).getUsableSpace();
            } catch (IOException e) {
                return Long.MAX_VALUE; // unknown: the disk is not taken for full
            }
        }, System::currentTimeMillis);
    }

    /** True when the disk has less than {@link #MIN_FREE_BYTES} free. */
    public boolean full() {
        long now = clock.getAsLong();
        if (checkedAt != Long.MIN_VALUE && now - checkedAt < CHECK_MILLIS) return full;
        synchronized (this) {
            if (checkedAt == Long.MIN_VALUE || now - checkedAt >= CHECK_MILLIS) {
                long free = freeBytes.getAsLong();
                boolean nowFull = free < MIN_FREE_BYTES;
                if (nowFull != full) {
                    if (nowFull) {
                        log.warn("Disk nearly full ({} MB free): new files are refused", free / (1024 * 1024));
                    } else {
                        log.info("Disk space back ({} MB free): new files are accepted again", free / (1024 * 1024));
                    }
                }
                full = nowFull;
                checkedAt = now;
            }
            return full;
        }
    }
}
