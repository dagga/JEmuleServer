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

import org.jemule.protocol.Tag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Represents metadata for a file indexed by the server.
 * Includes hash, name, size, type, and the collection of sources (clients) sharing it.
 *
 * @param hash    The 16-byte MD4 hash as a 32-character hex string.
 * @param name    The filename.
 * @param size    The file size in bytes.
 * @param type    The file category (e.g., Video, Audio, Archive).
 * @param tags    Additional metadata tags.
 * @param sources Map of client IDs to {@link ClientState} currently sharing this file.
 */
public record FileMetadata(
        String hash,
        String name,
        long size,
        String type,
        List<Tag> tags,
        ConcurrentHashMap<String, ClientState> sources
) {
    /**
     * Sources of the files known without any online source: shared by all of them, so that a million files loaded
     * from the database cost no map each. It cannot be filled: {@link FileIndex} replaces such a file with the
     * published one, which has its own map.
     */
    private static final ConcurrentHashMap<String, ClientState> NO_SOURCES = new ConcurrentHashMap<>(1) {
        @Override public ClientState put(String key, ClientState value) { throw readOnly(); }
        @Override public ClientState putIfAbsent(String key, ClientState value) { throw readOnly(); }
        @Override public void putAll(Map<? extends String, ? extends ClientState> m) { if (!m.isEmpty()) throw readOnly(); }
        @Override public ClientState compute(String key, BiFunction<? super String, ? super ClientState, ? extends ClientState> f) { throw readOnly(); }
        @Override public ClientState computeIfAbsent(String key, Function<? super String, ? extends ClientState> f) { throw readOnly(); }
        @Override public ClientState computeIfPresent(String key, BiFunction<? super String, ? super ClientState, ? extends ClientState> f) { throw readOnly(); }
        @Override public ClientState merge(String key, ClientState value, BiFunction<? super ClientState, ? super ClientState, ? extends ClientState> f) { throw readOnly(); }

        private UnsupportedOperationException readOnly() {
            return new UnsupportedOperationException("A file known without source cannot get one: publish a new FileMetadata");
        }
    };

    public FileMetadata(String hash, String name, long size, String type) {
        this(hash, name, size, type, List.of(), new ConcurrentHashMap<>());
    }

    /** A file known without any online source (loaded from the database, found on Kad): no source map of its own. */
    public static FileMetadata withoutSources(String hash, String name, long size, String type, List<Tag> tags) {
        return new FileMetadata(hash, name, size, type, tags, NO_SOURCES);
    }

    public FileMetadata(String hash, String name, long size, String type, List<Tag> tags) {
        this(hash, name, size, type, tags, new ConcurrentHashMap<>());
    }

    /** Number of sources sharing the complete file (FT_COMPLETE_SOURCES). */
    public int completeSourceCount() {
        int n = 0;
        for (ClientState s : sources.values()) {
            if (!s.partialHashes().contains(hash)) n++;
        }
        return n;
    }

    /**
     * Total number of sources sharing this file.
     * Evaluates active client states if connected, or tag {@link Tag#NAME_SOURCES} if loaded from DHT or database.
     */
    public int sourceCount() {
        if (sources != null && !sources.isEmpty()) {
            return sources.size();
        }
        if (tags != null) {
            for (Tag t : tags) {
                if (Tag.NAME_SOURCES.equals(t.name()) && t.isInt()) {
                    return (int) t.unsignedValue();
                }
            }
        }
        return 0;
    }
}
