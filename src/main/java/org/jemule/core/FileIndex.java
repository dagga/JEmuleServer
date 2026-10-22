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

import org.jemule.core.event.EventManager;
import org.jemule.core.event.FileEvent;
import org.jemule.security.ChildAbuseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages the global file index for the server.
 * Provides fast full-text and complex search capabilities using an inverted index.
 * <p>
 * The files are stored by number in a {@link FileTable}; the {@link FileMetadata} returned are built from it on
 * demand, their source map being the live one. The searches read under a read lock, so that they run in parallel;
 * adding or removing a file takes the write lock for that file only.
 */
public class FileIndex {
    private static final Logger log = LoggerFactory.getLogger(FileIndex.class);
    private static final Comparator<FileMetadata> SOURCE_COUNT_ORDER =
            Comparator.comparingInt(FileMetadata::sourceCount).reversed();
    private final FileTable table = new FileTable();
    /** Guards {@link #table} and the postings of {@link #invertedIndex}. */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ConcurrentSkipListMap<String, Postings> invertedIndex = new ConcurrentSkipListMap<>();
    /** Words of {@link #invertedIndex}, searched by substring. */
    private final WordDictionary dictionary = new WordDictionary();
    private final DatabaseManager db;
    /** Runs the database writes: the caller's thread by default, the ingestion thread on a running server. */
    private volatile java.util.concurrent.Executor persistence = Runnable::run;
    private final EventManager eventManager;
    private final ChildAbuseFilter abuseFilter;
    /** Hashes banned by the child abuse filter (upper case), loaded from the database and completed at runtime. */
    private final Md4Set abuseBannedHashes = new Md4Set();
    /** Count of files whose hash was not indexed yet, per ingestion channel (since server start). */
    private final Map<FileSource, LongAdder> newFilesBySource = new ConcurrentHashMap<>();

    // LRU Cache for searches (Thread-safe via Collections.synchronizedMap)
    private final Map<String, List<FileMetadata>> searchCache = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<FileMetadata>> eldest) {
            return size() > 100; // Limit to 100 entries
        }
    });

    /**
     * Constructs a FileIndex, optionally loading existing files from the database. Files of the database that the
     * child abuse filter rejects are deleted from it and banned instead of being loaded.
     *
     * @param db           The database manager for persistence.
     * @param eventManager The event manager for broadcasting file events.
     * @param abuseFilter  The child abuse filter applied to every published or loaded file.
     */
    public FileIndex(DatabaseManager db, EventManager eventManager, ChildAbuseFilter abuseFilter) {
        this.db = db;
        this.eventManager = eventManager;
        this.abuseFilter = abuseFilter;
        if (db != null) {
            try {
                db.loadBannedHashes(ChildAbuseFilter.BAN_REASON).forEach(h -> abuseBannedHashes.add(h.toUpperCase(Locale.ROOT)));
                // The files are indexed while they are read; the child abuse files are banned and deleted afterwards,
                // the database being locked during the read
                List<FileMetadata> rejected = new ArrayList<>();
                db.forEachFile(meta -> {
                    if (abuseBannedHashes.contains(meta.hash().toUpperCase(Locale.ROOT)) || abuseFilter.matches(meta.name())) {
                        rejected.add(meta);
                    } else {
                        indexInMemory(meta);
                    }
                });
                for (FileMetadata meta : rejected) {
                    isChildAbuse(meta.hash(), meta.name());
                    db.deleteFile(meta.hash());
                }
                if (!rejected.isEmpty()) {
                    log.warn("Deleted {} child abuse file(s) from the database", rejected.size());
                }
                // The arrays grew by half at a time while loading: their free slots go
                lock.writeLock().lock();
                try {
                    invertedIndex.values().forEach(Postings::trim);
                    table.trim();
                } finally {
                    lock.writeLock().unlock();
                }
            } catch (Exception e) {
                // If DB fails during load, we continue with empty memory index
                // DatabaseManager with CircuitBreaker should handle specific errors
            }
        }
    }

    public FileIndex(DatabaseManager db, EventManager eventManager) {
        this(db, eventManager, ChildAbuseFilter.defaults());
    }

    public FileIndex(DatabaseManager db) {
        this(db, null);
    }

    /**
     * Checks a file against the child abuse filter. A file whose name matches has its hash banned for good
     * (banned_hashes in the database), so that it stays rejected whatever name it is published under.
     *
     * @return true if the file must be rejected
     */
    public boolean isChildAbuse(String hash, String name) {
        String key = hash.toUpperCase(Locale.ROOT);
        if (abuseBannedHashes.contains(key)) return true;
        if (!abuseFilter.matches(name)) return false;
        abuseBannedHashes.add(key);
        log.warn("Child abuse file banned: hash={} name='{}'", hash, name);
        if (db != null) {
            String reason = ChildAbuseFilter.BAN_REASON + ": " + name;
            db.addBannedHash(key, reason.length() > 255 ? reason.substring(0, 255) : reason);
        }
        return true;
    }

    /**
     * Registers a published file. If the file (same hash) is already known, the publisher's sources are
     * <b>added</b> to the existing entry: every client sharing a file must be returned as a source.
     *
     * @param meta The file metadata to add, carrying the publishing client(s) in {@link FileMetadata#sources()}.
     * @return the file as now stored in the index for this hash
     */
    public FileMetadata addFile(FileMetadata meta) {
        return addFile(meta, FileSource.UNKNOWN);
    }

    /**
     * Registers a published file, recording its ingestion channel for the monitoring counters.
     */
    public FileMetadata addFile(FileMetadata meta, FileSource source) {
        Writes writes = new Writes(1);
        FileMetadata stored = addFileInternal(meta, writes, source);
        persist(writes);
        return stored;
    }

    /**
     * Adds a group of files to the in-memory index and persists changed entries in one database transaction.
     */
    public List<FileMetadata> addFiles(Collection<FileMetadata> files) {
        return addFiles(files, FileSource.UNKNOWN);
    }

    /**
     * Adds a group of files to the in-memory index, recording their ingestion channel for the monitoring counters.
     */
    public List<FileMetadata> addFiles(Collection<FileMetadata> files, FileSource source) {
        if (files == null || files.isEmpty()) return List.of();
        Writes writes = new Writes(files.size());
        List<FileMetadata> stored = new ArrayList<>(files.size());
        for (FileMetadata file : files) {
            if (file != null && file.hash() != null) stored.add(addFileInternal(file, writes, source));
        }
        persist(writes);
        return List.copyOf(stored);
    }

    /** Database writes of a call: the files of unknown hashes, and the higher source counts of known ones. */
    private record Writes(List<FileMetadata> files, List<FileMetadata> sourceCounts) {
        Writes(int expected) {
            this(new ArrayList<>(expected), new ArrayList<>(0));
        }
    }

    private FileMetadata addFileInternal(FileMetadata meta, Writes writes, FileSource source) {
        FileMetadata incoming = meta.indexed();
        boolean isNew = false;
        boolean isNewHash = false;
        boolean sourceCountChanged = false;
        FileMetadata replaced = null;
        FileMetadata stored;
        lock.writeLock().lock();
        try {
            int id = table.find(incoming.hash());
            FileMetadata existing = id < 0 ? null : table.view(id);
            if (existing == null || (existing.sources().isEmpty() && !incoming.sources().isEmpty())) {
                // unknown file, or a file without online source (e.g. loaded from the database) published by a
                // client: take the new metadata, under the hash the database knows. A search result of a known hash
                // keeps the entry below.
                FileMetadata selected = existing == null ? incoming : withHash(incoming, existing.hash());
                int knownSources = existing == null ? 0 : existing.sourceCount();
                if (selected.sourceCount() < knownSources) {
                    selected = selected.withSourceCount(knownSources);
                }
                isNew = true;
                isNewHash = existing == null;
                replaced = existing;
                stored = selected;
                if (existing == null) {
                    id = table.add(stored);
                } else {
                    table.set(id, stored);
                }
                // A file published again under the same name keeps its words; under another name, it is reindexed
                if (replaced == null || !Objects.equals(replaced.name(), stored.name())) {
                    if (replaced != null) unindexTokens(id, replaced.name());
                    indexTokens(id, stored.name());
                }
            } else {
                existing.sources().putAll(incoming.sources());
                int mergedSourceCount = Math.max(existing.sourceCount(), incoming.sourceCount());
                if (mergedSourceCount > existing.sourceCount()) {
                    sourceCountChanged = true;
                    stored = existing.withSourceCount(mergedSourceCount);
                    table.set(id, stored);
                } else {
                    stored = existing;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (isNew) {
            if (isNewHash) {
                newFilesBySource.computeIfAbsent(source, k -> new LongAdder()).increment();
            }
            searchCache.clear(); // Invalidate cache when adding a new file
            // The index holds every hash of the database: an unknown hash is written whole, a known one only when
            // its source count rose
            if (isNewHash) {
                writes.files().add(stored);
            } else if (stored.sourceCount() > replaced.sourceCount()) {
                writes.sourceCounts().add(stored);
            }
            if (eventManager != null) {
                eventManager.broadcast(new FileEvent(FileEvent.PUBLISHED, stored.name(), stored.hash(), "File published: " + stored.name()));
            }
        } else if (sourceCountChanged) {
            searchCache.clear();
            writes.sourceCounts().add(stored); // the hash is already stored: only its count is written
        }
        return stored;
    }

    private static FileMetadata withHash(FileMetadata meta, String hash) {
        return hash.equals(meta.hash()) ? meta
                : new FileMetadata(hash, meta.name(), meta.size(), meta.type(), meta.tags(), meta.sources());
    }

    /**
     * Removes a disconnected client from the sources of every file it offered. Files left without any
     * source stay indexed and searchable, like the files loaded from the database.
     */
    public void removeSource(ClientState client) {
        String key = sourceKey(client);
        lock.readLock().lock();
        try {
            for (String hash : client.publishedHashes()) {
                int id = table.find(hash);
                ConcurrentHashMap<String, ClientState> sources = id < 0 ? null : table.sources(id);
                if (sources != null) {
                    sources.remove(key);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        client.publishedHashes().clear();
    }

    /**
     * Removes a file from the index and the database (e.g. after a ban). Its current sources are not told: they
     * simply stop being returned.
     *
     * @return true if the file was indexed
     */
    public boolean removeFile(String hash) {
        String hashToDelete;
        lock.writeLock().lock();
        try {
            int id = table.find(hash);
            if (id < 0) return false;
            hashToDelete = table.hash(id);
            unindexTokens(id, table.name(id));
            table.remove(id);
        } finally {
            lock.writeLock().unlock();
        }
        searchCache.clear();
        if (db != null) {
            persistence.execute(() -> db.deleteFile(hashToDelete)); // after any queued write of this file
        }
        return true;
    }

    /**
     * Runs the database writes on {@code executor}, one at a time and in order, instead of the caller's thread: a
     * client publishing files or a search delivering results then never waits for the database.
     */
    public void setPersistenceExecutor(java.util.concurrent.Executor executor) {
        this.persistence = executor == null ? Runnable::run : executor;
    }

    private void persist(Writes writes) {
        if (db == null) return;
        if (!writes.files().isEmpty()) persistence.execute(() -> db.saveFiles(writes.files()));
        if (!writes.sourceCounts().isEmpty()) persistence.execute(() -> db.saveSourceCounts(writes.sourceCounts()));
    }

    /** Called with the write lock held. */
    private void unindexTokens(int id, String name) {
        for (String t : words(name)) {
            invertedIndex.computeIfPresent(t, (k, ids) -> {
                ids.remove(id);
                return ids.size() == 0 ? null : ids;
            });
        }
    }

    /** Distinct words of a file name, each indexed once. */
    private static Set<String> words(String name) {
        if (name == null) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(Keywords.split(name)));
    }

    /** Key of a client in {@link FileMetadata#sources()}: unique per connection (several clients may share one IP). */
    public static String sourceKey(ClientState client) {
        return String.valueOf(client.connectionId());
    }

    private void indexInMemory(FileMetadata meta) {
        FileMetadata indexed = meta.indexed();
        lock.writeLock().lock();
        try {
            if (table.find(indexed.hash()) >= 0) return; // the same hash in another case
            indexTokens(table.add(indexed), indexed.name());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Called with the write lock held. */
    private void indexTokens(int id, String name) {
        for (String t : words(name)) {
            Postings ids = invertedIndex.get(t);
            if (ids == null) {
                invertedIndex.put(t, new Postings(id));
                dictionary.add(t); // once in the index, so that a dictionary rebuild cannot miss it
            } else {
                ids.add(id);
            }
        }
    }

    /**
     * Retrieves a list of clients (sources) currently sharing a file.
     * Implements IP proximity sorting and diversity via shuffling.
     *
     * @param hash      The MD4 hash of the file.
     * @param requester The client state of the requester (for proximity matching).
     * @param limit     The maximum number of sources to return.
     * @return A list of {@link ClientState} representing sources.
     */
    public List<ClientState> getSources(String hash, ClientState requester, int limit) {
        List<ClientState> all;
        lock.readLock().lock();
        try {
            int id = table.find(hash);
            ConcurrentHashMap<String, ClientState> sources = id < 0 ? null : table.sources(id);
            if (sources == null || sources.isEmpty()) return Collections.emptyList();
            all = new ArrayList<>(sources.values());
        } finally {
            lock.readLock().unlock();
        }
        if (all.isEmpty()) return Collections.emptyList();
        if (all.size() <= limit && requester == null) {
            Collections.shuffle(all);
            return all;
        }

        // Diversity: start by shuffling
        Collections.shuffle(all);

        if (requester != null) {
            byte[] reqIp = requester.address().getAddress();
            // Sort to prioritize IP proximity (same /16 or /24)
            all.sort((a, b) -> {
                int scoreA = calculateProximity(a.address().getAddress(), reqIp);
                int scoreB = calculateProximity(b.address().getAddress(), reqIp);
                return Integer.compare(scoreB, scoreA); // Highest score first
            });
        }

        return all.subList(0, Math.min(limit, all.size()));
    }

    private int calculateProximity(byte[] ipA, byte[] ipB) {
        if (ipA.length != ipB.length) return 0;
        int score = 0;
        for (int i = 0; i < ipA.length; i++) {
            if (ipA[i] == ipB[i]) score++;
            else break;
        }
        return score;
    }

    /**
     * Executes a complex search query using boolean logic and filters, on every known file, including the
     * files loaded from the database and the files whose sources are all offline (FT_SOURCES = 0).
     *
     * @param query The parsed {@link SearchQuery}.
     * @param limit The maximum number of results.
     * @return A list of matching {@link FileMetadata}.
     */
    public List<FileMetadata> searchComplex(SearchQuery query, int limit) {
        if (eventManager != null) {
            eventManager.broadcast(new FileEvent(FileEvent.SEARCHED, query.toString(), "", "Complex search executed"));
        }
        if (limit <= 0) return List.of();
        List<FileMetadata> results = new ArrayList<>();
        lock.readLock().lock();
        try {
            List<Postings> postings = candidates(query);
            if (postings == null) {
                // No keyword constrains the file name (e.g. a type or size filter alone): every file is tested
                table.forEach(id -> {
                    FileMetadata meta = table.view(id);
                    if (query.test(meta)) results.add(meta);
                });
            } else {
                // Only the files indexed under a word containing a keyword are tested; a file may appear in
                // several sets
                BitSet tested = new BitSet();
                for (Postings ids : postings) {
                    ids.forEach(id -> {
                        if (tested.get(id)) return;
                        tested.set(id);
                        FileMetadata meta = table.view(id);
                        if (query.test(meta)) results.add(meta);
                    });
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return results.stream()
                .sorted(SOURCE_COUNT_ORDER)
                .limit(limit)
                .toList();
    }

    /**
     * Files that may match a query: every match belongs to one of the returned sets, or null when the query does
     * not constrain the file name. A file name keyword matches a name that contains it ({@link
     * SearchQuery.TermQuery}); made of letters and digits only, it can only lie inside one word of the name, so the
     * files to test are those indexed under the words containing it. Called with the read lock held.
     */
    private List<Postings> candidates(SearchQuery query) {
        return switch (query) {
            case SearchQuery.TermQuery t when t.id() == SearchQuery.ID_FILENAME -> {
                List<Postings> best = null;
                long bestSize = Long.MAX_VALUE;
                for (String token : Keywords.split(t.term())) {
                    // Every word of the keyword must be in the name: the rarest one is enough to select the files
                    List<Postings> sets = new ArrayList<>();
                    long size = 0;
                    for (String word : dictionary.wordsContaining(token, invertedIndex.keySet())) {
                        Postings ids = invertedIndex.get(word);
                        if (ids != null) {
                            sets.add(ids);
                            size += ids.size();
                        }
                    }
                    if (size < bestSize) {
                        best = sets;
                        bestSize = size;
                    }
                }
                yield best != null ? best : List.of(); // a keyword without any letter/digit matches nothing
            }
            case SearchQuery.AndQuery a -> smallest(candidates(a.left()), candidates(a.right()));
            case SearchQuery.NotQuery n -> candidates(n.left());
            case SearchQuery.OrQuery o -> {
                List<Postings> left = candidates(o.left());
                List<Postings> right = candidates(o.right());
                if (left == null || right == null) yield null;
                List<Postings> both = new ArrayList<>(left);
                both.addAll(right);
                yield both;
            }
            default -> null;
        };
    }

    private static List<Postings> smallest(List<Postings> a, List<Postings> b) {
        if (a == null) return b;
        if (b == null) return a;
        return total(a) <= total(b) ? a : b;
    }

    private static long total(List<Postings> sets) {
        long n = 0;
        for (Postings s : sets) n += s.size();
        return n;
    }

    /**
     * Executes a simple keyword-based search.
     * Supports prefix matching and uses a result cache for performance.
     *
     * @param query The textual query string.
     * @param limit The maximum number of results.
     * @return A list of matching {@link FileMetadata}.
     */
    public List<FileMetadata> search(String query, int limit) {
        if (eventManager != null) {
            eventManager.broadcast(new FileEvent(FileEvent.SEARCHED, query, "", "Search executed: " + query));
        }
        if (query == null || query.trim().isEmpty()) {
            List<FileMetadata> all = new ArrayList<>();
            lock.readLock().lock();
            try {
                table.forEach(id -> {
                    if (all.size() < limit) all.add(table.view(id));
                });
            } finally {
                lock.readLock().unlock();
            }
            return all;
        }

        String queryLower = Keywords.normalize(query).trim();

        // Cache check
        String cacheKey = queryLower + "|" + limit;
        List<FileMetadata> cached = searchCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<FileMetadata> res = new ArrayList<>();
        lock.readLock().lock();
        try {
            BitSet candidates = null;
            for (String t : Keywords.split(queryLower)) {
                if (t.isEmpty()) continue;

                // Optimized prefix search via subMap (O(log n))
                BitSet matches = new BitSet();
                String nextPrefix = t.substring(0, t.length() - 1) + (char) (t.charAt(t.length() - 1) + 1);
                for (Postings ids : invertedIndex.subMap(t, nextPrefix).values()) {
                    ids.forEach(matches::set);
                }

                if (matches.isEmpty()) return Collections.emptyList();
                if (candidates == null) {
                    candidates = matches;
                } else {
                    candidates.and(matches);
                }
                if (candidates.isEmpty()) break;
            }
            if (candidates == null || candidates.isEmpty()) return Collections.emptyList();

            for (int id = candidates.nextSetBit(0); id >= 0 && res.size() < limit; id = candidates.nextSetBit(id + 1)) {
                res.add(table.view(id));
            }
        } finally {
            lock.readLock().unlock();
        }

        // Cache the result
        searchCache.put(cacheKey, res);

        return res;
    }

    /** Number of distinct words of the inverted index. */
    public int wordCount() {
        return invertedIndex.size();
    }

    /** True if a file with this hash is indexed (case ignored for hexadecimal hashes). */
    public boolean contains(String hash) {
        lock.readLock().lock();
        try {
            return table.find(hash) >= 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the total number of files indexed.
     *
     * @return The file count.
     */
    public int fileCount() {
        lock.readLock().lock();
        try {
            return table.count();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Number of new files (a hash not indexed before) inserted since server start, for one ingestion channel. */
    public long newFileCount(FileSource source) {
        LongAdder adder = newFilesBySource.get(source);
        return adder == null ? 0 : adder.sum();
    }

    /** Number of new files inserted since server start, per ingestion channel (in enum order). */
    public Map<FileSource, Long> newFileCounts() {
        Map<FileSource, Long> counts = new LinkedHashMap<>();
        for (FileSource source : FileSource.values()) {
            counts.put(source, newFileCount(source));
        }
        return counts;
    }

    /** Total number of new files inserted since server start. */
    public long totalNewFiles() {
        return newFilesBySource.values().stream().mapToLong(LongAdder::sum).sum();
    }
}
