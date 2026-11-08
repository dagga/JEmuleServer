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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Applies the name cleaning rules ({@link NameCleaner}) to the files indexed before them, on demand of the
 * administrator: the files table is read window after window in hash order, the database selecting the files whose
 * name holds a rule ({@code LOWER(name) LIKE '%rule%'}); each one is renamed in the index (its whole document
 * rewritten, so that the searches find its new words) and the window's files in the database, in one transaction.
 * <p>
 * One run at a time, on a thread of the ingestion tier. It stops by a flag checked between two windows, never by an
 * interrupt: an interrupted thread reading H2 closes the file channel of the whole database.
 */
public final class NameCleaningJob implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NameCleaningJob.class);
    /** Files of the table read per query: what bounds the time a query holds the database. */
    static final int WINDOW_FILES = 20_000;
    /** Time {@link #close} waits for the window under way. */
    private static final long CLOSE_TIMEOUT_MS = 60_000;

    /** The states of a run, as the admin API names them in lower case. */
    public enum State { IDLE, RUNNING, DONE, STOPPED, FAILED }

    /** Progress of the last run: files of the table read, files renamed, start and end (epoch ms, 0: none). */
    public record Status(State state, long scanned, long renamed, long startedAt, long finishedAt, String lastError) {
        /** The state as the admin API writes it. */
        public String stateName() {
            return state.name().toLowerCase(Locale.ROOT);
        }
    }

    private final DatabaseManager db;
    private final FileIndex index;
    private final NameCleaner cleaner;
    private final int window;
    private volatile State state = State.IDLE;
    private volatile boolean stopRequested;
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong renamed = new AtomicLong();
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile String lastError = "";
    private Thread thread;

    public NameCleaningJob(DatabaseManager db, FileIndex index, NameCleaner cleaner) {
        this(db, index, cleaner, WINDOW_FILES);
    }

    NameCleaningJob(DatabaseManager db, FileIndex index, NameCleaner cleaner, int window) {
        this.db = db;
        this.index = index;
        this.cleaner = cleaner;
        this.window = window;
    }

    /**
     * Starts a run unless one is under way.
     *
     * @return false if a run was under way already
     */
    public synchronized boolean start() {
        if (state == State.RUNNING) return false;
        stopRequested = false;
        scanned.set(0);
        renamed.set(0);
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        lastError = "";
        state = State.RUNNING;
        thread = ServerThreads.start(ServerThreads.Tier.INGESTION, "name-cleaning", this::run);
        return true;
    }

    /** Asks the run under way to stop after its current window. */
    public void stop() {
        stopRequested = true;
    }

    public Status status() {
        return new Status(state, scanned.get(), renamed.get(), startedAt, finishedAt, lastError);
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
        State end;
        try {
            end = cleanAll();
        } catch (RuntimeException e) {
            log.error("File name cleaning failed", e);
            lastError = String.valueOf(e.getMessage());
            end = State.FAILED;
        }
        finishedAt = System.currentTimeMillis();
        state = end;
        log.info("File name cleaning {}: {} files read, {} renamed in {} s", end.name().toLowerCase(Locale.ROOT),
                String.format("%,d", scanned.get()), String.format("%,d", renamed.get()),
                (finishedAt - startedAt) / 1000);
    }

    private State cleanAll() {
        if (db == null) {
            lastError = "No database";
            return State.FAILED;
        }
        List<String> texts = cleaner.rules().stream().map(NameCleaner.Rule::text).toList();
        if (texts.isEmpty()) return State.DONE;
        log.info("File name cleaning started: {} rule(s)", texts.size());
        String after = "";
        while (true) {
            if (stopRequested) return State.STOPPED;
            DatabaseManager.NameWindow files = db.filesNamedLike(texts, after, window);
            if (files == null) {
                lastError = "Reading the files of the database failed";
                return State.FAILED;
            }
            if (files.last() == null) return State.DONE;
            Map<String, String> names = new LinkedHashMap<>();
            for (DatabaseManager.NamedFile file : files.matches()) {
                String name = cleaner.clean(file.name());
                if (name.equals(file.name())) continue;
                if (index != null) index.renameToCleanName(file.hash());
                names.put(file.hash(), name);
            }
            if (!db.renameFiles(names)) {
                lastError = "Renaming files in the database failed";
                return State.FAILED;
            }
            renamed.addAndGet(names.size());
            scanned.addAndGet(files.scanned());
            after = files.last();
        }
    }

    /** Stops the run under way and waits for its current window, before the index and the database close. */
    @Override
    public void close() {
        stop();
        try {
            if (!awaitEnd(CLOSE_TIMEOUT_MS)) log.warn("File name cleaning still running at the shutdown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
