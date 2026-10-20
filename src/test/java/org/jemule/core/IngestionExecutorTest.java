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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IngestionExecutorTest {

    @Test
    void runsTasksInOrderOnTheIngestionTierAndFlushesThemOnClose() throws Exception {
        List<Integer> order = new CopyOnWriteArrayList<>();
        List<Integer> priorities = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        IngestionExecutor ingestion = IngestionExecutor.create();
        ingestion.execute(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (int i = 0; i < 50; i++) {
            int n = i;
            ingestion.execute(() -> {
                order.add(n);
                priorities.add(Thread.currentThread().getPriority());
            });
        }
        assertTrue(order.isEmpty(), "the caller never waits for the ingestion");
        release.countDown();
        ingestion.close();
        assertEquals(java.util.stream.IntStream.range(0, 50).boxed().toList(), order, "in submission order, all run");
        assertTrue(priorities.stream().allMatch(p -> p == ServerThreads.Tier.INGESTION.priority));
    }

    @Test
    void dropsOptionalTasksWhenTooManyWaitButNeverDatabaseWrites() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        IngestionExecutor ingestion = IngestionExecutor.create();
        ingestion.execute(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(100); // the blocking task left the queue
        for (int i = 0; i < IngestionExecutor.MAX_PENDING_OPTIONAL; i++) {
            assertTrue(ingestion.offer(() -> { }));
        }
        assertFalse(ingestion.offer(() -> { }), "search results are dropped when the queue is full");
        assertEquals(1, ingestion.dropped());
        ingestion.execute(() -> { });
        assertEquals(IngestionExecutor.MAX_PENDING_OPTIONAL + 1, ingestion.pending(), "a database write is always queued");
        release.countDown();
        ingestion.close();
        assertEquals(0, ingestion.pending());
    }

    @Test
    void directExecutorRunsInTheCallerThread() {
        Thread caller = Thread.currentThread();
        Thread[] ran = new Thread[1];
        assertTrue(IngestionExecutor.direct().offer(() -> ran[0] = Thread.currentThread()));
        assertSame(caller, ran[0]);
    }
}
