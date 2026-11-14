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

import org.jemule.core.ServerThreads.Tier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SearchExecutorTest {

    @Test
    void atLeastTwoThreadsAndOnePerCpu() {
        try (SearchExecutor searches = SearchExecutor.create()) {
            assertEquals(Math.max(2, Runtime.getRuntime().availableProcessors()), searches.threads());
        }
    }

    @Test
    void searchesRunOnPlatformThreadsOfTheSearchTier() throws Exception {
        try (SearchExecutor searches = new SearchExecutor(2, 4, 1)) {
            Thread thread = searches.call(Thread::currentThread);
            assertFalse(thread.isVirtual(), "never on the carriers of the client threads");
            assertEquals(Tier.SEARCH.priority, thread.getPriority());
            assertTrue(thread.isDaemon());
            assertTrue(thread.getName().startsWith("search-"), thread.getName());
        }
    }

    @Test
    void theSearchExceptionIsThrownToTheCaller() {
        try (SearchExecutor searches = new SearchExecutor(1, 1, 1)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> searches.call(() -> { throw new IllegalStateException("broken index"); }));
            assertEquals("broken index", e.getMessage());
        }
    }

    @Test
    void aSaturatedExecutorRefusesAtOnceInsteadOfQueuing() throws Exception {
        try (SearchExecutor searches = new SearchExecutor(1, 1, 1)) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> call(searches, () -> {
                running.countDown();
                release.await();
                return "first";
            }), task -> Thread.ofVirtual().start(task));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            CompletableFuture<String> queued = CompletableFuture.supplyAsync(() -> call(searches, () -> "queued"),
                    task -> Thread.ofVirtual().start(task));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (searches.waiting() < 1 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1, searches.waiting());
            assertThrows(RejectedExecutionException.class, () -> searches.call(() -> "refused"),
                    "one thread busy and one search waiting: the next one is refused at once");
            assertEquals(1, searches.rejected());
            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aClosedExecutorRefusesTheSearches() {
        SearchExecutor searches = new SearchExecutor(1, 1, 1);
        searches.close();
        assertThrows(RejectedExecutionException.class, () -> searches.call(() -> "late"));
    }

    @Test
    void bestEffortTasksBeyondTheLimitAreDropped() throws Exception {
        try (SearchExecutor searches = new SearchExecutor(1, 8, 1)) {
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Thread> ran = new CompletableFuture<>();
            assertTrue(searches.offer(() -> {
                ran.complete(Thread.currentThread());
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertEquals(Tier.SEARCH.priority, ran.get(5, TimeUnit.SECONDS).getPriority());
            assertFalse(searches.offer(() -> fail("dropped: never run")), "one best effort task at most");
            assertEquals(1, searches.dropped());
            release.countDown();
            assertTrue(searches.awaitBestEffortIdle(5_000));

            // A failing task gives its slot back
            assertTrue(searches.offer(() -> { throw new IllegalStateException("broken"); }));
            assertTrue(searches.awaitBestEffortIdle(5_000));
            assertTrue(searches.offer(() -> { }));
            assertEquals(1, searches.dropped());
        }
    }

    private static <T> T call(SearchExecutor searches, java.util.concurrent.Callable<T> search) {
        try {
            return searches.call(search);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
