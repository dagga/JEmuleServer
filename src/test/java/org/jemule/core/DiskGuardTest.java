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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DiskGuardTest {

    @Test
    void fullBelowTheMinimumFreeSpaceReadEveryTenSeconds() {
        AtomicLong free = new AtomicLong(DiskGuard.MIN_FREE_BYTES);
        AtomicLong now = new AtomicLong(1_000);
        AtomicInteger reads = new AtomicInteger();
        DiskGuard guard = new DiskGuard(() -> {
            reads.incrementAndGet();
            return free.get();
        }, now::get);
        assertFalse(guard.full());
        free.set(DiskGuard.MIN_FREE_BYTES - 1);
        assertFalse(guard.full(), "read again only after the check period");
        now.addAndGet(DiskGuard.CHECK_MILLIS);
        assertTrue(guard.full());
        assertEquals(2, reads.get());
        free.set(DiskGuard.MIN_FREE_BYTES * 2);
        now.addAndGet(DiskGuard.CHECK_MILLIS);
        assertFalse(guard.full(), "room again");
    }

    @Test
    void aPathNotCreatedYetIsCheckedOnItsDisk(@TempDir Path dir) {
        assertFalse(DiskGuard.of(dir.resolve("db.index")).full());
        assertFalse(DiskGuard.of(null).full(), "an index in the heap");
    }
}
