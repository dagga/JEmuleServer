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
import java.util.Arrays;
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
    /** The other tags of the files whose only tag is their source count, the most common case. */
    private static final Tag[] NO_TAGS = new Tag[0];

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

    /** False for a file known without any online source, which shares {@code NO_SOURCES}. */
    boolean hasOwnSources() {
        return sources != NO_SOURCES;
    }

    /** A file known without any online source: no source map of its own. */
    public static FileMetadata withoutSources(String hash, String name, long size, String type, List<Tag> tags) {
        if (tags == null) return new FileMetadata(hash, name, size, type, null, NO_SOURCES);
        for (int i = 0; i < tags.size(); i++) {
            Tag tag = tags.get(i);
            if (Tag.NAME_SOURCES.equals(tag.name()) && tag.isInt()) {
                Tag[] retainedTags = tags.size() == 1 ? NO_TAGS : new Tag[tags.size() - 1];
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

    /**
     * Whether the index keeps a tag: the source count and the media tags searches filter on ({@link SearchQuery}).
     * The name, size and type are fields; the other tags (FT_FILENAME, FT_FILESIZE, FT_FILEFORMAT...) are dropped.
     */
    public static boolean isIndexedTag(String name) {
        if (name == null || name.length() != 1) return false;
        int id = name.charAt(0);
        return id == (SearchQuery.ID_SOURCES & 0xFF) || id == (SearchQuery.ID_FILERATING & 0xFF)
                || (id >= (SearchQuery.ID_MEDIA_ARTIST & 0xFF) && id <= (SearchQuery.ID_MEDIA_CODEC & 0xFF));
    }

    /** This file with only the tags the index keeps, their names shared ({@link Tag#idName}). */
    public FileMetadata indexed() {
        if (tags == null || tags.isEmpty()) return this;
        if (tags instanceof CompactTags compact ? compact.retainedIndexed() : allIndexed(tags)) return this;
        List<Tag> kept = new java.util.ArrayList<>(2);
        for (Tag tag : tags) {
            if (isIndexedTag(tag.name())) kept.add(new Tag(tag.type(), Tag.idName(tag.name().charAt(0)), tag.value()));
        }
        List<Tag> indexedTags = List.copyOf(kept);
        return sources == NO_SOURCES ? withoutSources(hash, name, size, type, indexedTags)
                : new FileMetadata(hash, name, size, type, indexedTags, sources);
    }

    /** Whether every tag is kept by the index, under its shared name. */
    private static boolean allIndexed(List<Tag> tags) {
        for (Tag tag : tags) {
            if (!isIndexedTag(tag.name()) || tag.name() != Tag.idName(tag.name().charAt(0))) return false;
        }
        return true;
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
     * Returns the highest count known from active clients or the retained DHT/database source-count tag.
     */
    public int sourceCount() {
        int count = sources == null ? 0 : sources.size();
        if (tags instanceof CompactTags compactTags) return Math.max(count, (int) compactTags.sourceCount());
        if (tags == null) return count;
        for (Tag tag : tags) {
            if (Tag.NAME_SOURCES.equals(tag.name()) && tag.isInt()) {
                return Math.max(count, (int) tag.unsignedValue());
            }
        }
        return count;
    }

    /** Returns metadata with the known total source count updated, preserving active client sources. */
    public FileMetadata withSourceCount(int count) {
        if (count < 0) throw new IllegalArgumentException("Source count cannot be negative");
        List<Tag> updatedTags = new java.util.ArrayList<>(tags == null ? List.of() : tags);
        Tag sourceCountTag = new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, count);
        for (int i = 0; i < updatedTags.size(); i++) {
            if (Tag.NAME_SOURCES.equals(updatedTags.get(i).name())) {
                updatedTags.set(i, sourceCountTag);
                return new FileMetadata(hash, name, size, type, updatedTags, sources);
            }
        }
        updatedTags.add(sourceCountTag);
        return new FileMetadata(hash, name, size, type, updatedTags, sources);
    }

    private static final class CompactTags extends AbstractList<Tag> implements RandomAccess {
        private final Tag[] retainedTags;
        private final int sourceCountTagIndex;
        private final byte sourceCountTagType;
        private final long sourceCount;

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
                // Built on each call rather than kept: one field less on hundreds of thousands of files
                Object value = switch (sourceCountTagType) {
                    case Tag.TYPE_INTEGER -> (int) sourceCount;
                    case Tag.TYPE_INT16 -> (short) sourceCount;
                    case Tag.TYPE_INT8 -> (byte) sourceCount;
                    case Tag.TYPE_UINT64 -> sourceCount;
                    default -> throw new IllegalStateException("Invalid compacted source-count tag type");
                };
                return new Tag(sourceCountTagType, Tag.NAME_SOURCES, value);
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

        /** Whether the other tags are all kept by the index, without building the source count tag. */
        private boolean retainedIndexed() {
            return allIndexed(Arrays.asList(retainedTags));
        }
    }
}
