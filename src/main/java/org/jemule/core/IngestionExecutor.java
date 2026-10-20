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

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the ingestion of files (indexing and database writes) on one thread of the {@link ServerThreads.Tier#INGESTION}
 * tier, in submission order, so that it never holds back a client connection or a search.
 * <p>
 * Database writes ({@link #execute}) are always queued: they record what clients published. The files found by
 * searches ({@link #offer}) are optional: they are dropped while {@link #MAX_PENDING_OPTIONAL} tasks already wait,
 * so that a burst of search results cannot grow the queue without limit.
 */
public final class IngestionExecutor implements Executor, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IngestionExecutor.class);
    static final int MAX_PENDING_OPTIONAL = 500;
    private static final long CLOSE_TIMEOUT_SECONDS = 60;

    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

    private IngestionExecutor(ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    /** An executor with its own ingestion thread. */
    public static IngestionExecutor create() {
        return new IngestionExecutor(new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                ServerThreads.factory(ServerThreads.Tier.INGESTION, "ingestion")));
    }

    /** An executor running every task at once in the caller's thread (tests, tools). */
    public static IngestionExecutor direct() {
        return new IngestionExecutor(null);
    }

    /** Queues a task that must run (database writes). */
    @Override
    public void execute(Runnable task) {
        if (executor == null) {
            task.run();
        } else {
            executor.execute(guarded(task));
        }
    }

    /**
     * Queues an optional task (files found by a search) unless too many tasks already wait.
     *
     * @return false if the task was dropped
     */
    public boolean offer(Runnable task) {
        if (executor != null && executor.getQueue().size() >= MAX_PENDING_OPTIONAL) {
            if (dropped.incrementAndGet() % 100 == 1) {
                log.warn("Ingestion queue full ({} tasks): search results dropped ({} so far)",
                        executor.getQueue().size(), dropped.get());
            }
            return false;
        }
        execute(task);
        return true;
    }

    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Ingestion task failed: {}", e.getMessage(), e);
            }
        };
    }

    /** Tasks waiting to run. */
    public int pending() {
        return executor == null ? 0 : executor.getQueue().size();
    }

    /** Optional tasks dropped because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    /** Runs the queued tasks to completion, then stops the thread. */
    @Override
    public void close() {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.error("Ingestion did not finish in {} s: {} tasks lost", CLOSE_TIMEOUT_SECONDS,
                        executor.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
