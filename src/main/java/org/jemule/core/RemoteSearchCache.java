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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Results of the remote searches (Kad, eD2K servers) played recently: the same search asked again within the time to
 * live ({@code remoteSearchCacheMinutes}) gets the files found then, without being played again on the network. A
 * search still running is joined: the second client waits for the first search instead of starting another one.
 * <p>
 * Only clean results are stored (the bridges screen them before completing the search), and a cached answer is never
 * ingested again: the files were indexed when they were first received. An empty answer is not stored, as it often
 * means a transient condition (Kad not bootstrapped yet, no verified server, a search refused as too frequent).
 * <p>
 * Memory: at most {@link #MAX_ENTRIES} searches, {@link #MAX_RESULTS_PER_ENTRY} files each and {@link #MAX_RESULTS}
 * files together; beyond, the oldest searches are forgotten first, which are also the first to expire.
 */
public final class RemoteSearchCache {
    /** Default time to live of a search, in minutes. */
    public static final int DEFAULT_MINUTES = 15;
    public static final int MAX_ENTRIES = 2_000;
    /** Files kept per search: the others were indexed and the local search finds them. */
    public static final int MAX_RESULTS_PER_ENTRY = 1_000;
    public static final int MAX_RESULTS = 30_000;
    /** Longer keys (huge search trees) are not cached. */
    static final int MAX_KEY_LENGTH = 1_024;

    private final LongSupplier ttlMs;
    private final LongSupplier clock;
    private final int maxEntries;
    private final int maxResultsPerEntry;
    private final int maxResults;
    /** Searches by key, the oldest stored first; searches still running are where they started. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private int storedResults;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong joins = new AtomicLong();

    private static final class Entry {
        private final CompletableFuture<List<FileMetadata>> future = new CompletableFuture<>();
        /** Null while the search runs. */
        private List<FileMetadata> results;
        private long storedAt;
    }

    /** A cache whose time to live is read at every search, so that a changed setting applies at once. */
    public RemoteSearchCache(LongSupplier ttlMs) {
        this(ttlMs, System::currentTimeMillis, MAX_ENTRIES, MAX_RESULTS_PER_ENTRY, MAX_RESULTS);
    }

    RemoteSearchCache(LongSupplier ttlMs, LongSupplier clock, int maxEntries, int maxResultsPerEntry, int maxResults) {
        if (maxEntries < 1 || maxResultsPerEntry < 1 || maxResults < 1) throw new IllegalArgumentException("Invalid limits");
        this.ttlMs = Objects.requireNonNull(ttlMs);
        this.clock = Objects.requireNonNull(clock);
        this.maxEntries = maxEntries;
        this.maxResultsPerEntry = maxResultsPerEntry;
        this.maxResults = maxResults;
    }

    /** The time to live of {@code minutes} minutes, in milliseconds (0 or less: the cache is disabled). */
    public static long ttlMs(int minutes) {
        return Math.max(0, minutes) * 60_000L;
    }

    /**
     * The results of the search {@code key}: those stored within the time to live, those of the same search still
     * running, or those of {@code search}, which is then played and its results stored.
     *
     * @param key    the normalized search ({@link #key(SearchQuery)}); null plays the search without the cache
     * @param search plays the search on the network
     */
    public CompletableFuture<List<FileMetadata>> search(String key, Supplier<CompletableFuture<List<FileMetadata>>> search) {
        long ttl = ttlMs.getAsLong();
        if (ttl <= 0 || key == null) {
            if (ttl <= 0) clear();
            return search.get();
        }
        Entry entry;
        synchronized (this) {
            long now = clock.getAsLong();
            purgeExpired(now, ttl);
            Entry existing = entries.get(key);
            if (existing != null && existing.results == null) {
                joins.incrementAndGet();
                return existing.future.copy();
            }
            if (existing != null && now - existing.storedAt < ttl) {
                hits.incrementAndGet();
                return CompletableFuture.completedFuture(existing.results);
            }
            if (existing != null) { // expired, but stored after a younger one (clock set back)
                storedResults -= existing.results.size();
                entries.remove(key);
            }
            misses.incrementAndGet();
            entry = new Entry();
            entries.put(key, entry);
            evictOverLimits();
        }
        CompletableFuture<List<FileMetadata>> played;
        try {
            played = search.get();
        } catch (RuntimeException e) {
            store(key, entry, null, e);
            throw e;
        }
        if (played == null) played = CompletableFuture.completedFuture(List.of());
        played.whenComplete((results, error) -> store(key, entry, results, error));
        return entry.future.copy();
    }

    /** Stores the results of a search that ended, then hands them to the clients waiting for it. */
    private void store(String key, Entry entry, List<FileMetadata> results, Throwable error) {
        synchronized (this) {
            if (entries.get(key) == entry) {
                entries.remove(key);
                if (error == null && results != null && !results.isEmpty() && ttlMs.getAsLong() > 0) {
                    entry.results = results.stream().filter(Objects::nonNull).limit(maxResultsPerEntry).toList();
                    entry.storedAt = clock.getAsLong();
                    storedResults += entry.results.size();
                    entries.put(key, entry); // now the newest stored
                    evictOverLimits();
                }
            }
        }
        if (error != null) {
            entry.future.completeExceptionally(error);
        } else {
            entry.future.complete(results == null ? List.of() : results);
        }
    }

    /** Forgets the stored searches older than the time to live; those still running are kept. */
    private void purgeExpired(long now, long ttl) {
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.results == null) continue;
            if (now - entry.storedAt < ttl) break; // the next ones were stored later
            storedResults -= entry.results.size();
            it.remove();
        }
    }

    private void evictOverLimits() {
        Iterator<Entry> it = entries.values().iterator();
        while ((entries.size() > maxEntries || storedResults > maxResults) && it.hasNext()) {
            Entry oldest = it.next();
            // A search still running is forgotten too: its clients still get its results, but they are not stored
            if (oldest.results != null) storedResults -= oldest.results.size();
            it.remove();
        }
    }

    public synchronized void clear() {
        entries.clear();
        storedResults = 0;
    }

    /** Searches answered with stored results. */
    public long hits() {
        return hits.get();
    }

    /** Searches played on the network. */
    public long misses() {
        return misses.get();
    }

    /** Searches that waited for the same search already running. */
    public long joins() {
        return joins.get();
    }

    /** Searches stored or running. */
    public synchronized int size() {
        return entries.size();
    }

    /** Files stored for all searches. */
    public synchronized int storedResults() {
        return storedResults;
    }

    /** Counters for the admin overview, under {@code prefix} (e.g. "kadCache": kadCacheHits, kadCacheMisses...). */
    public Map<String, Object> stats(String prefix) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put(prefix + "Hits", hits());
        stats.put(prefix + "Misses", misses());
        stats.put(prefix + "Joins", joins());
        stats.put(prefix + "Entries", size());
        return stats;
    }

    /**
     * The normalized form of a search, so that the same search sent by two clients hits the same entry even when
     * they encode it differently: words in lower case and split as the index splits them (a keyword "Foo Bar" is
     * the same as "bar" AND "foo"), the operands of AND and OR sorted and deduplicated (both are commutative and
     * associative), NOT kept in order. File type and format values are compared in lower case, numeric filters as
     * they are.
     *
     * @return the key, or null when the query cannot be cached (none, or too large)
     */
    public static String key(SearchQuery query) {
        if (query == null) return null;
        String key = canonical(query);
        return key.length() > MAX_KEY_LENGTH ? null : key;
    }

    private static String canonical(SearchQuery query) {
        return switch (query) {
            case SearchQuery.AndQuery and -> {
                TreeSet<String> operands = new TreeSet<>();
                collectAnd(and, operands);
                yield join(operands, " & ");
            }
            case SearchQuery.TermQuery term when term.id() == SearchQuery.ID_FILENAME -> {
                TreeSet<String> operands = new TreeSet<>();
                collectAnd(term, operands);
                yield join(operands, " & ");
            }
            case SearchQuery.OrQuery or -> {
                TreeSet<String> operands = new TreeSet<>();
                collectOr(or, operands);
                yield join(operands, " | ");
            }
            case SearchQuery.NotQuery not -> "(" + canonical(not.left()) + " - " + canonical(not.right()) + ")";
            case SearchQuery.TermQuery term -> "t" + (term.id() & 0xFF) + ":" + Keywords.normalize(term.term().trim());
            case SearchQuery.NumericQuery numeric ->
                    "n" + (numeric.id() & 0xFF) + "o" + numeric.operator() + ":" + numeric.value();
            default -> "*"; // a tag the parser does not filter on
        };
    }

    private static void collectAnd(SearchQuery query, TreeSet<String> operands) {
        switch (query) {
            case SearchQuery.AndQuery and -> {
                collectAnd(and.left(), operands);
                collectAnd(and.right(), operands);
            }
            case SearchQuery.TermQuery term when term.id() == SearchQuery.ID_FILENAME -> {
                String[] words = Keywords.split(term.term());
                if (words.length == 0) operands.add("w?" + Keywords.normalize(term.term().trim()));
                for (String word : words) operands.add("w:" + word);
            }
            default -> operands.add(canonical(query));
        }
    }

    private static void collectOr(SearchQuery query, TreeSet<String> operands) {
        if (query instanceof SearchQuery.OrQuery or) {
            collectOr(or.left(), operands);
            collectOr(or.right(), operands);
        } else {
            operands.add(canonical(query));
        }
    }

    private static String join(TreeSet<String> operands, String operator) {
        if (operands.size() == 1) return operands.first();
        return "(" + String.join(operator, new ArrayList<>(operands)) + ")";
    }
}
