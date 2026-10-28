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

import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index lock: the file count is read without it, and the reads and writes of every kind, run together, all end
 * (the lock is not reentrant: a method taking it twice would block for ever).
 */
class FileIndexLockTest {
    private static FileMetadata file(int i, int sources) {
        return FileMetadata.withoutSources(String.format("%032X", i), "lock test file " + i % 97 + " part" + i + ".avi",
                1_000L + i, "Video", List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources)));
    }

    @Test
    void theFileCountDoesNotWaitForTheIndexLock() throws Exception {
        FileIndex index = new FileIndex(null);
        index.addFiles(List.of(file(1, 1), file(2, 1), file(3, 1)));
        index.setCapacity(() -> 3, () -> false);
        long stamp = index.indexLock().writeLock(); // a writer that never ends, as after the lost read lock of 1.2.29
        try {
            CompletableFuture<Integer> count = CompletableFuture.supplyAsync(index::fileCount);
            CompletableFuture<Boolean> full = CompletableFuture.supplyAsync(index::isFull);
            assertEquals(3, count.get(5, TimeUnit.SECONDS));
            assertTrue(full.get(5, TimeUnit.SECONDS));
        } finally {
            index.indexLock().unlockWrite(stamp);
        }
    }

    @Test
    void readsAndWritesOfEveryKindTogetherAllEnd() throws Exception {
        FileIndex index = new FileIndex(null);
        index.setCapacity(() -> 5_000, () -> false);
        List<FileMetadata> initial = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) initial.add(file(i, i % 30));
        index.addFiles(initial);
        ClientState client = new ClientState(InetAddress.getLoopbackAddress(), 4662, 42, System.currentTimeMillis(),
                new AtomicLong(System.currentTimeMillis()));
        SearchQuery query = new SearchQuery.AndQuery(new SearchQuery.TermQuery("file", SearchQuery.ID_FILENAME),
                new SearchQuery.NumericQuery(SearchQuery.ID_SOURCES, SearchQuery.OP_GREATER, 3));
        int threads = 8;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int seed = t;
                runs.add(pool.submit(() -> {
                    Random random = new Random(seed);
                    int operations = 0;
                    while (System.nanoTime() < until) {
                        int i = random.nextInt(4_000);
                        switch (random.nextInt(11)) {
                            case 0 -> index.addFile(file(i, random.nextInt(50)));
                            case 1 -> index.addFiles(List.of(file(i, 1), file(i + 1, 2), file(i + 2, 3)));
                            case 2 -> index.removeFile(String.format("%032X", i));
                            case 3 -> index.searchComplex(query, 300);
                            case 4 -> index.search("part" + i, 50);
                            case 5 -> index.getSources(String.format("%032X", i), client, 10);
                            case 6 -> index.removeSource(client);
                            case 7 -> index.topWords(20);
                            case 8 -> index.removeFilesNamed("lock test file " + i % 97 + " part" + i + ".avi");
                            case 9 -> index.contains(String.format("%032X", i));
                            default -> index.isFull();
                        }
                        operations++;
                    }
                    return operations;
                }));
            }
            long operations = 0;
            for (Future<Integer> run : runs) operations += run.get(30, TimeUnit.SECONDS);
            assertTrue(operations > threads, "operations: " + operations);
        } finally {
            pool.shutdownNow();
        }
        int found = 0;
        for (int i = 0; i < 4_002; i++) {
            if (index.contains(String.format("%032X", i))) found++;
        }
        assertEquals(found, index.fileCount());
        assertFalse(index.searchComplex(new SearchQuery.TermQuery("lock", SearchQuery.ID_FILENAME), 10).isEmpty()
                && found > 0);
    }
}
