/*
 * JEmuleServer - An experimental eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (herniatgmail.com)
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class FileIndex {
    private final ConcurrentHashMap<String, FileMetadata> byHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();
    private static final Pattern TOKENIZER = Pattern.compile("\\W+");
    private final DatabaseManager db;

    public FileIndex(DatabaseManager db) {
        this.db = db;
        if (db != null) {
            List<FileMetadata> loaded = db.loadFiles();
            for (FileMetadata meta : loaded) {
                indexInMemory(meta);
            }
        }
    }

    public void addFile(FileMetadata meta) {
        indexInMemory(meta);
        if (db != null) {
            db.saveFile(meta);
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

    public Set<ClientState> getSources(String hash) {
        FileMetadata m = byHash.get(hash);
        return m != null ? Set.copyOf(m.sources().values()) : Set.of();
    }

    public List<FileMetadata> search(String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return byHash.values().stream().limit(limit).toList();
        }
        String queryLower = query.toLowerCase();
        String[] tokens = TOKENIZER.split(queryLower);
        Set<String> candidates = null;
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            Set<String> matches = invertedIndex.get(t);
            if (matches == null) return Collections.emptyList();
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
        return res;
    }

    public int fileCount() {
        return byHash.size();
    }
}
