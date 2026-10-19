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

/**
 * Manages the global file index for the server.
 * Provides fast full-text and complex search capabilities using an inverted index.
 */
public class FileIndex {
    private static final Logger log = LoggerFactory.getLogger(FileIndex.class);
    private static final Comparator<FileMetadata> SOURCE_COUNT_ORDER =
            Comparator.comparingInt(FileMetadata::sourceCount).reversed();
    private final ConcurrentHashMap<String, FileMetadata> byHash = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<String, Postings> invertedIndex = new ConcurrentSkipListMap<>();
    /** Words of {@link #invertedIndex}, searched by substring. */
    private final WordDictionary dictionary = new WordDictionary();
    private final DatabaseManager db;
    private final EventManager eventManager;
    private final ChildAbuseFilter abuseFilter;
    /** Hashes banned by the child abuse filter (upper case), loaded from the database and completed at runtime. */
    private final Set<String> abuseBannedHashes = ConcurrentHashMap.newKeySet();

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
     * @return the metadata instance now stored in the index for this hash
     */
    public FileMetadata addFile(FileMetadata meta) {
        List<FileMetadata> toPersist = new ArrayList<>(1);
        FileMetadata stored = addFileInternal(meta, toPersist);
        if (db != null && !toPersist.isEmpty()) db.saveFiles(toPersist);
        return stored;
    }

    /**
     * Adds a group of files to the in-memory index and persists changed entries in one database transaction.
     */
    public List<FileMetadata> addFiles(Collection<FileMetadata> files) {
        if (files == null || files.isEmpty()) return List.of();
        List<FileMetadata> toPersist = new ArrayList<>(files.size());
        List<FileMetadata> stored = new ArrayList<>(files.size());
        for (FileMetadata file : files) {
            if (file != null && file.hash() != null) stored.add(addFileInternal(file, toPersist));
        }
        if (db != null && !toPersist.isEmpty()) db.saveFiles(toPersist);
        return List.copyOf(stored);
    }

    private FileMetadata addFileInternal(FileMetadata meta, List<FileMetadata> toPersist) {
        FileMetadata incoming = meta;
        boolean[] isNew = {false};
        boolean[] sourceCountChanged = {false};
        FileMetadata[] replaced = {null};
        FileMetadata stored = byHash.compute(meta.hash(), (h, existing) -> {
            if (existing == null || existing.sources().isEmpty()) {
                // unknown file, or a file without online source (e.g. loaded from the database): take the new metadata
                FileMetadata selected = incoming;
                if (existing != null) selected.sources().putAll(existing.sources());
                int knownSources = existing == null ? 0 : existing.sourceCount();
                if (selected.sourceCount() < knownSources) {
                    selected = selected.withSourceCount(knownSources);
                }
                isNew[0] = true;
                replaced[0] = existing;
                return selected;
            }
            existing.sources().putAll(incoming.sources());
            int mergedSourceCount = Math.max(existing.sourceCount(), incoming.sourceCount());
            if (mergedSourceCount > existing.sourceCount()) {
                sourceCountChanged[0] = true;
                return existing.withSourceCount(mergedSourceCount);
            }
            return existing;
        });
        if (isNew[0]) {
            // A file published again under the same name keeps its words; under another name, it is reindexed
            if (replaced[0] == null || !Objects.equals(replaced[0].name(), stored.name())) {
                if (replaced[0] != null) unindexTokens(replaced[0]);
                indexTokens(stored);
            }
            searchCache.clear(); // Invalidate cache when adding a new file
            toPersist.add(stored);
            if (eventManager != null) {
                eventManager.broadcast(new FileEvent(FileEvent.PUBLISHED, stored.name(), stored.hash(), "File published: " + stored.name()));
            }
        } else if (sourceCountChanged[0]) {
            searchCache.clear();
            toPersist.add(stored);
        }
        return stored;
    }

    /**
     * Removes a disconnected client from the sources of every file it offered. Files left without any
     * source stay indexed and searchable, like the files loaded from the database.
     */
    public void removeSource(ClientState client) {
        String key = sourceKey(client);
        for (String hash : client.publishedHashes()) {
            FileMetadata meta = byHash.get(hash);
            if (meta != null) {
                meta.sources().remove(key);
            }
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
        FileMetadata meta = byHash.remove(hash.toLowerCase(Locale.ROOT));
        if (meta == null) meta = byHash.remove(hash.toUpperCase(Locale.ROOT));
        if (meta == null) return false;
        unindexTokens(meta);
        searchCache.clear();
        if (db != null) {
            db.deleteFile(meta.hash());
        }
        return true;
    }

    private void unindexTokens(FileMetadata meta) {
        for (String t : words(meta.name())) {
            invertedIndex.computeIfPresent(t, (k, hashes) -> {
                hashes.remove(meta.hash());
                return hashes.size() == 0 ? null : hashes;
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
        byHash.put(meta.hash(), meta);
        indexTokens(meta);
    }

    private void indexTokens(FileMetadata meta) {
        for (String t : words(meta.name())) {
            Postings hashes = invertedIndex.get(t);
            if (hashes == null) {
                Postings created = new Postings(meta.hash());
                hashes = invertedIndex.putIfAbsent(t, created);
                if (hashes == null) {
                    dictionary.add(t); // once in the index, so that a dictionary rebuild cannot miss it
                    continue;
                }
            }
            hashes.add(meta.hash());
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
        FileMetadata m = byHash.get(hash);
        if (m == null) return Collections.emptyList();
        
        // Avoid full copy if possible
        Collection<ClientState> sources = m.sources().values();
        if (sources.isEmpty()) return Collections.emptyList();

        List<ClientState> all = new ArrayList<>(sources);
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
        List<Postings> postings = candidates(query);
        if (postings == null) {
            // No keyword constrains the file name (e.g. a type or size filter alone): every file is tested
            return byHash.values().stream()
                    .filter(query)
                    .sorted(SOURCE_COUNT_ORDER)
                    .limit(limit)
                    .toList();
        }
        // Only the files indexed under a word containing a keyword are tested; a file may appear in several sets
        Map<String, FileMetadata> results = new LinkedHashMap<>();
        for (Postings hashes : postings) {
            for (String hash : hashes) {
                if (results.containsKey(hash)) continue;
                FileMetadata meta = byHash.get(hash);
                if (meta != null && query.test(meta)) {
                    results.put(hash, meta);
                }
            }
        }
        return results.values().stream()
                .sorted(SOURCE_COUNT_ORDER)
                .limit(limit)
                .toList();
    }

    /**
     * Files that may match a query: every match belongs to one of the returned hash sets, or null when the query
     * does not constrain the file name. A file name keyword matches a name that contains it ({@link
     * SearchQuery.TermQuery}); made of letters and digits only, it can only lie inside one word of the name, so the
     * files to test are those indexed under the words containing it.
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
                        Postings hashes = invertedIndex.get(word);
                        if (hashes != null) {
                            sets.add(hashes);
                            size += hashes.size();
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
            return byHash.values().stream().limit(limit).toList();
        }
        
        String queryLower = Keywords.normalize(query).trim();
        
        // Cache check
        String cacheKey = queryLower + "|" + limit;
        List<FileMetadata> cached = searchCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String[] tokens = Keywords.split(queryLower);
        Set<String> candidates = null;
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            
            Set<String> matches = new HashSet<>();
            
            // Optimized prefix search via subMap (O(log n))
            String nextPrefix = t.substring(0, t.length() - 1) + (char) (t.charAt(t.length() - 1) + 1);
            SortedMap<String, Postings> prefixMatches = invertedIndex.subMap(t, nextPrefix);
            for (Postings fileHashes : prefixMatches.values()) {
                fileHashes.forEach(matches::add);
            }
            
            if (matches.isEmpty()) return Collections.emptyList();
            candidates = (candidates == null) ? new HashSet<>(matches) : candidates;
            candidates.retainAll(matches);
            if (candidates.isEmpty()) break;
        }
        
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();

        List<FileMetadata> res = new ArrayList<>(Math.min(candidates.size(), limit));
        for (String h : candidates) {
            FileMetadata m = byHash.get(h);
            if (m != null) res.add(m);
            if (res.size() >= limit) break;
        }
        
        // Cache the result
        searchCache.put(cacheKey, res);
        
        return res;
    }

    /** Number of distinct words of the inverted index. */
    public int wordCount() {
        return invertedIndex.size();
    }

    /** True if a file with this hash is indexed. */
    public boolean contains(String hash) {
        return byHash.containsKey(hash);
    }

    /**
     * Returns the total number of files indexed.
     *
     * @return The file count.
     */
    public int fileCount() {
        return byHash.size();
    }
}
