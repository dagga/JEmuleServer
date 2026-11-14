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

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Evaluates the searches of the local index on the platform threads of the {@link ServerThreads.Tier#SEARCH} tier.
 * <p>
 * A Lucene search is CPU-bound and may last a few hundred milliseconds. Run on a client virtual thread, it would hold
 * a carrier thread for that long (virtual threads are not preempted): on a one-CPU server, every other client (login,
 * keep-alive, source requests) would wait. A client thread hands its search over with {@link #call} and parks
 * meanwhile, which frees its carrier.
 * <p>
 * Nothing queues without limit: when {@link #QUEUE_PER_THREAD} searches per thread already wait, a client search is
 * refused at once, and the client gets an empty answer as for a failed search.
 */
public final class SearchExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SearchExecutor.class);
    /** Searches waiting per search thread before the next one is refused. */
    static final int QUEUE_PER_THREAD = 16;
    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final ThreadPoolExecutor executor;
    private final int threads;
    private final AtomicLong rejected = new AtomicLong();

    SearchExecutor(int threads, int queueCapacity) {
        if (threads < 1 || queueCapacity < 1) throw new IllegalArgumentException("threads and queue must be positive");
        this.threads = threads;
        this.executor = ServerThreads.searchPool("search", threads, queueCapacity);
    }

    /** One search thread per CPU, at least two, so that one long search never holds back all the others. */
    public static SearchExecutor create() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new SearchExecutor(threads, QUEUE_PER_THREAD * threads);
    }

    /**
     * Runs {@code search} on a search thread and waits for its result; a virtual thread parks meanwhile, freeing its
     * carrier. An exception of the search is thrown again here.
     *
     * @throws RejectedExecutionException when too many searches already wait, or once the executor is closed
     * @throws InterruptedException if the caller is interrupted while waiting; the search is then cancelled
     */
    public <T> T call(Callable<T> search) throws InterruptedException {
        Future<T> future;
        try {
            future = executor.submit(search);
        } catch (RejectedExecutionException e) {
            long count = rejected.incrementAndGet();
            if (count % 100 == 1) {
                log.warn("Search threads saturated ({} searches waiting): client search refused ({} so far)",
                        executor.getQueue().size(), count);
            } else {
                log.debug("Search threads saturated: client search refused ({} so far)", count);
            }
            throw e;
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            // A Lucene search under way is never interrupted: one not started yet just never runs
            future.cancel(false);
            throw e;
        } catch (CancellationException e) {
            throw new RejectedExecutionException("search executor closed", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    /** Number of search threads. */
    public int threads() {
        return threads;
    }

    /** Searches waiting for a search thread. */
    public int waiting() {
        return executor.getQueue().size();
    }

    /** Client searches refused because too many searches were waiting. */
    public long rejected() {
        return rejected.get();
    }

    /**
     * Lets the searches under way and queued end for a few seconds, then stops the threads; a client still waiting for
     * a search that never ran gets a {@link RejectedExecutionException}.
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The futures of the searches which never ran would otherwise never complete
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof Future<?> future) future.cancel(false);
        }
    }
}
