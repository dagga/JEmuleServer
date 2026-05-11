/*
 * JEmuleServer - An experimental eMule server.
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

import java.util.*;
import org.jemule.core.event.EventManager;
import org.jemule.core.event.FileEvent;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FileIndex {
    private final ConcurrentHashMap<String, FileMetadata> byHash = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<String, Set<String>> invertedIndex = new ConcurrentSkipListMap<>();
    private static final Pattern TOKENIZER = Pattern.compile("[^a-zA-Z0-9]+");
    private final DatabaseManager db;
    private final EventManager eventManager;

    // LRU Cache for searches (Thread-safe via Collections.synchronizedMap)
    private final Map<String, List<FileMetadata>> searchCache = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<FileMetadata>> eldest) {
            return size() > 100; // Limit to 100 entries
        }
    });

    public FileIndex(DatabaseManager db, EventManager eventManager) {
        this.db = db;
        this.eventManager = eventManager;
        if (db != null) {
            try {
                List<FileMetadata> loaded = db.loadFiles();
                for (FileMetadata meta : loaded) {
                    indexInMemory(meta);
                }
            } catch (Exception e) {
                // If DB fails during load, we continue with empty memory index
                // DatabaseManager with CircuitBreaker should handle specific errors
            }
        }
    }

    public FileIndex(DatabaseManager db) {
        this(db, null);
    }

    public void addFile(FileMetadata meta) {
        indexInMemory(meta);
        searchCache.clear(); // Invalidate cache when adding a new file
        if (db != null) {
            db.saveFile(meta);
        }
        if (eventManager != null) {
            eventManager.broadcast(new FileEvent(FileEvent.PUBLISHED, meta.name(), meta.hash(), "File published: " + meta.name()));
        }
    }

    private void indexInMemory(FileMetadata meta) {
        byHash.put(meta.hash(), meta);
        String name = meta.name();
        if (name == null) return;
        String[] tokens = TOKENIZER.split(name.toLowerCase());
        for (String t : tokens) {
            if (!t.isEmpty()) {
                invertedIndex.computeIfAbsent(t, k -> ConcurrentHashMap.newKeySet()).add(meta.hash());
            }
        }
    }

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

        return all.subList(0, limit);
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

    public List<FileMetadata> searchComplex(SearchQuery query, int limit) {
        if (eventManager != null) {
            eventManager.broadcast(new FileEvent(FileEvent.SEARCHED, "ComplexQuery", "", "Complex search executed"));
        }
        return byHash.values().stream()
                .filter(query)
                .limit(limit)
                .toList();
    }

    public List<FileMetadata> search(String query, int limit) {
        if (eventManager != null) {
            eventManager.broadcast(new FileEvent(FileEvent.SEARCHED, query, "", "Search executed: " + query));
        }
        if (query == null || query.trim().isEmpty()) {
            return byHash.values().stream().limit(limit).toList();
        }
        
        String queryLower = query.toLowerCase().trim();
        
        // Cache check
        String cacheKey = queryLower + "|" + limit;
        List<FileMetadata> cached = searchCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String[] tokens = TOKENIZER.split(queryLower);
        Set<String> candidates = null;
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            
            Set<String> matches = new HashSet<>();
            
            // Optimized prefix search via subMap (O(log n))
            String nextPrefix = t.substring(0, t.length() - 1) + (char) (t.charAt(t.length() - 1) + 1);
            SortedMap<String, Set<String>> prefixMatches = invertedIndex.subMap(t, nextPrefix);
            for (Set<String> fileHashes : prefixMatches.values()) {
                matches.addAll(fileHashes);
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

    public int fileCount() {
        return byHash.size();
    }
}
