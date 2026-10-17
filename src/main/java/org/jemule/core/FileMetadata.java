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

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Metadata for an indexed file. Database-loaded source counts are stored compactly in the tag list until callers need
 * the original tag.
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
     * Sources of files known without any online source: shared by all of them, so that a million files loaded from
     * the database cost no map each. It cannot be filled: {@link FileIndex} replaces such a file with the published
     * one, which has its own map.
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

    /** A file known without any online source: no source map of its own. */
    public static FileMetadata withoutSources(String hash, String name, long size, String type, List<Tag> tags) {
        if (tags == null) return new FileMetadata(hash, name, size, type, null, NO_SOURCES);
        for (int i = 0; i < tags.size(); i++) {
            Tag tag = tags.get(i);
            if (Tag.NAME_SOURCES.equals(tag.name()) && tag.isInt()) {
                Tag[] retainedTags = new Tag[tags.size() - 1];
                for (int j = 0; j < i; j++) retainedTags[j] = tags.get(j);
                for (int j = i + 1; j < tags.size(); j++) retainedTags[j - 1] = tags.get(j);
                return new FileMetadata(hash, name, size, type,
                        new CompactTags(retainedTags, i, tag.type(), tag.unsignedValue()), NO_SOURCES);
            }
        }
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
     * Evaluates active client states if connected, or a stored tag if loaded from DHT or database.
     */
    public int sourceCount() {
        if (sources != null && !sources.isEmpty()) return sources.size();
        if (tags instanceof CompactTags compactTags) return (int) compactTags.sourceCount();
        if (tags == null) return 0;
        for (Tag tag : tags) {
            if (Tag.NAME_SOURCES.equals(tag.name()) && tag.isInt()) return (int) tag.unsignedValue();
        }
        return 0;
    }

    private static final class CompactTags extends AbstractList<Tag> implements RandomAccess {
        private final Tag[] retainedTags;
        private final int sourceCountTagIndex;
        private final byte sourceCountTagType;
        private final long sourceCount;
        private volatile Tag sourceCountTag;

        private CompactTags(Tag[] retainedTags, int sourceCountTagIndex, byte sourceCountTagType, long sourceCount) {
            this.retainedTags = retainedTags;
            this.sourceCountTagIndex = sourceCountTagIndex;
            this.sourceCountTagType = sourceCountTagType;
            this.sourceCount = sourceCount;
        }

        @Override
        public Tag get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
            if (index == sourceCountTagIndex) {
                Tag result = sourceCountTag;
                if (result == null) {
                    Object value = switch (sourceCountTagType) {
                        case Tag.TYPE_INTEGER -> (int) sourceCount;
                        case Tag.TYPE_INT16 -> (short) sourceCount;
                        case Tag.TYPE_INT8 -> (byte) sourceCount;
                        case Tag.TYPE_UINT64 -> sourceCount;
                        default -> throw new IllegalStateException("Invalid compacted source-count tag type");
                    };
                    sourceCountTag = result = new Tag(sourceCountTagType, Tag.NAME_SOURCES, value);
                }
                return result;
            }
            int retainedIndex = index < sourceCountTagIndex ? index : index - 1;
            return retainedTags[retainedIndex];
        }

        @Override
        public int size() {
            return retainedTags.length + 1;
        }

        private long sourceCount() {
            return sourceCount;
        }
    }
}
