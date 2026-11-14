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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Purges the files nobody published nor found for {@code fileRetentionDays} days: their last_seen day
 * ({@link DatabaseManager#touchFiles}) is older than the retention period. Once a day, on a thread of the maintenance
 * tier: the files table is read window after window in hash order ({@link DatabaseManager#staleFiles}), and the
 * stale files of each window are purged by batches of {@link #BATCH_FILES} ({@link FileIndex#purgeStale}: out of the
 * index, then out of the database in one transaction with their tags), with a pause after every query so that the
 * clients, the searches and the ingestion keep the database and the index.
 * <p>
 * Never while the database is failing ({@link DatabaseManager#isAvailable}), nor in relay mode, where the local index
 * is no longer written and its files no longer seen. It stops by a flag checked between two batches, never by an
 * interrupt: an interrupted thread reading H2 closes the file channel of the whole database. A run stopped (server
 * stop, database failing) is not recorded: the next check starts it again.
 */
public final class FilePurgeJob implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FilePurgeJob.class);
    /** Files of the table read per query. */
    static final int WINDOW_FILES = 10_000;
    /** Files deleted per transaction. */
    static final int BATCH_FILES = 1_000;
    /** Pause after every query of a run, for the clients and the searches. */
    static final long PAUSE_MS = 100;
    /** Least time between two runs. */
    static final long INTERVAL_MS = 24 * 3_600_000L;
    /** Database stats of the last complete run: its start (epoch ms), the files purged, its duration (ms). */
    static final String LAST_RUN_STAT = "file_purge_last_run", LAST_COUNT_STAT = "file_purge_last_files",
            LAST_DURATION_STAT = "file_purge_last_ms";
    /** Time {@link #close} waits for the batch under way. */
    private static final long CLOSE_TIMEOUT_MS = 60_000;

    /** The states of a run, as the admin API names them in lower case. */
    public enum State { IDLE, RUNNING, DONE, STOPPED, FAILED }

    /**
     * The last run: its state, the files read and purged by the run under way or the last one, and the last complete
     * run (start in epoch ms, 0: never; files purged; duration in ms).
     */
    public record Status(State state, int retentionDays, long scanned, long purged, long lastRunAt, long lastRunFiles,
                         long lastRunMillis, long purgedSinceStart) {
        /** The state as the admin API writes it. */
        public String stateName() {
            return state.name().toLowerCase(Locale.ROOT);
        }

        /** The figures of the admin API. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("state", stateName());
            map.put("retentionDays", retentionDays);
            map.put("scanned", scanned);
            map.put("purged", purged);
            map.put("lastRunAt", lastRunAt);
            map.put("lastRunFiles", lastRunFiles);
            map.put("lastRunSeconds", lastRunMillis / 1000);
            map.put("purgedSinceStart", purgedSinceStart);
            return map;
        }
    }

    private final DatabaseManager db;
    private final FileIndex index;
    private final IntSupplier retentionDays;
    private final int window;
    private final int batch;
    private final long pauseMs;
    private final Object pause = new Object();
    private volatile State state = State.IDLE;
    private volatile boolean stopRequested;
    private volatile boolean closed;
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong purged = new AtomicLong();
    private final AtomicLong purgedSinceStart = new AtomicLong();
    private volatile long lastRunAt;
    private volatile long lastRunFiles;
    private volatile long lastRunMillis;
    private Thread thread;

    /**
     * @param retentionDays days a file not seen is kept, read at every check (hot): 0 never purges
     */
    public FilePurgeJob(DatabaseManager db, FileIndex index, IntSupplier retentionDays) {
        this(db, index, retentionDays, WINDOW_FILES, BATCH_FILES, PAUSE_MS);
    }

    FilePurgeJob(DatabaseManager db, FileIndex index, IntSupplier retentionDays, int window, int batch, long pauseMs) {
        this.db = db;
        this.index = index;
        this.retentionDays = retentionDays;
        this.window = window;
        this.batch = batch;
        this.pauseMs = pauseMs;
        // Read once, here, so that the checks of the maintenance thread never read the database
        if (db != null) {
            lastRunAt = db.getStat(LAST_RUN_STAT, 0);
            lastRunFiles = db.getStat(LAST_COUNT_STAT, 0);
            lastRunMillis = db.getStat(LAST_DURATION_STAT, 0);
        }
    }

    /**
     * Starts a run if the last complete one is a day old or more, the retention is not 0, the database works and
     * the server is not in relay mode. Called regularly by the maintenance scheduler; never reads the database.
     *
     * @return true if a run was started
     */
    public boolean runIfDue() {
        return runIfDue(System.currentTimeMillis());
    }

    boolean runIfDue(long now) {
        if (now - lastRunAt < INTERVAL_MS) return false;
        if (retentionDays.getAsInt() <= 0 || db == null || index == null) return false;
        if (!db.isAvailable()) {
            log.debug("File purge postponed: the database is failing");
            return false;
        }
        if (index.relaying()) return false; // the local index is only a fallback: its files are no longer seen
        return start();
    }

    /**
     * Starts a run now unless one is under way or the job is closed.
     *
     * @return false if a run was under way already
     */
    public synchronized boolean start() {
        if (state == State.RUNNING || closed || db == null || index == null) return false;
        stopRequested = false;
        scanned.set(0);
        purged.set(0);
        state = State.RUNNING;
        thread = ServerThreads.start(ServerThreads.Tier.MAINTENANCE, "file-purge", this::run);
        return true;
    }

    /** Asks the run under way to stop after its current batch. */
    public void stop() {
        stopRequested = true;
        synchronized (pause) {
            pause.notifyAll();
        }
    }

    public Status status() {
        return new Status(state, retentionDays.getAsInt(), scanned.get(), purged.get(), lastRunAt, lastRunFiles,
                lastRunMillis, purgedSinceStart.get());
    }

    /** Waits for the run under way to end, for the tests. */
    boolean awaitEnd(long millis) throws InterruptedException {
        Thread running;
        synchronized (this) {
            running = thread;
        }
        if (running != null) running.join(millis);
        return state != State.RUNNING;
    }

    private void run() {
        long start = System.currentTimeMillis();
        int days = retentionDays.getAsInt();
        State end;
        try {
            end = purgeAll(days);
        } catch (RuntimeException e) {
            log.error("File purge failed", e);
            end = State.FAILED;
        }
        long millis = System.currentTimeMillis() - start;
        if (end == State.DONE) {
            lastRunAt = start;
            lastRunFiles = purged.get();
            lastRunMillis = millis;
            db.setStat(LAST_RUN_STAT, start);
            db.setStat(LAST_COUNT_STAT, lastRunFiles);
            db.setStat(LAST_DURATION_STAT, millis);
        }
        if (purged.get() > 0) index.compactWordDictionary();
        state = end;
        log.info("File purge {}: {} files not seen for {} days purged ({} files read) in {} s",
                end.name().toLowerCase(Locale.ROOT), String.format("%,d", purged.get()), days,
                String.format("%,d", scanned.get()), millis / 1000);
    }

    private State purgeAll(int days) {
        if (days <= 0) return State.DONE;
        String after = "";
        while (true) {
            if (stopRequested) return State.STOPPED;
            if (!db.isAvailable()) {
                log.warn("File purge stopped: the database is failing");
                return State.STOPPED;
            }
            // Read at every window: a retention changed meanwhile applies at once, 0 stops the run
            days = retentionDays.getAsInt();
            if (days <= 0) return State.STOPPED;
            int cutoff = db.today() - days;
            DatabaseManager.StaleWindow files = db.staleFiles(after, window, cutoff);
            if (files == null) return State.FAILED; // the database logged why
            if (files.last() == null) return State.DONE;
            List<String> stale = files.hashes();
            for (int from = 0; from < stale.size(); from += batch) {
                if (stopRequested) return State.STOPPED;
                FileIndex.Purge purge = index.purgeStale(stale.subList(from, Math.min(stale.size(), from + batch)), cutoff);
                int deleted;
                try {
                    deleted = purge.deletedFromDatabase().join();
                } catch (CompletionException e) {
                    log.error("File purge: deleting files failed: {}", String.valueOf(e.getCause()));
                    return State.FAILED;
                }
                if (deleted < 0) return State.FAILED; // the database logged why
                purged.addAndGet(deleted);
                purgedSinceStart.addAndGet(deleted);
                pause();
            }
            scanned.addAndGet(files.scanned());
            after = files.last();
            pause();
        }
    }

    /** Leaves the database and the index to the others for a moment; cut short by {@link #stop}. */
    private void pause() {
        if (pauseMs <= 0) return;
        synchronized (pause) {
            if (stopRequested) return;
            try {
                pause.wait(pauseMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopRequested = true;
            }
        }
    }

    /** Stops the run under way and waits for its current batch, before the index and the database close. */
    @Override
    public void close() {
        closed = true;
        stop();
        try {
            if (!awaitEnd(CLOSE_TIMEOUT_MS)) log.warn("File purge still running at the shutdown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
