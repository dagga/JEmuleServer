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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * The indexed files, stored by number in parallel arrays rather than as one object graph per file: the MD4 hash as
 * two longs, the size, the type, the source count and the name, about 60 bytes per file plus the name, where a
 * {@link FileMetadata} with its hash string, tag list and map entry took about 200. A file only gets an object of
 * its own ({@link Extra}) for what most files do not have: online sources, media tags, a hash that is not 32
 * hexadecimal digits in one case.
 * <p>
 * The hash is looked up case-insensitively, through an open addressing table of file numbers; it is given back in
 * the form it was stored in, which is the key of the file in the database. {@link #view} builds the
 * {@link FileMetadata} of a file on demand; its source map is the live one.
 * <p>
 * Not thread-safe: {@link FileIndex} guards it with its read-write lock.
 */
final class FileTable {
    private static final byte LOWER_CASE = 0;
    private static final byte UPPER_CASE = 1;
    /** The hash is kept as given in {@link Extra#hash} (another case mix, or not a hexadecimal MD4 hash). */
    private static final byte RAW = 2;
    private static final int INITIAL_CAPACITY = 1024;

    /** What only some files have; null for most of them. */
    private static final class Extra {
        /** The hash as given, when it is not lower or upper case hexadecimal. */
        String hash;
        /** The whole tag list, when the file has other tags than its source count. */
        List<Tag> tags;
        /** The clients sharing the file, once one has published it. */
        ConcurrentHashMap<String, ClientState> sources;

        boolean isEmpty() {
            return hash == null && tags == null && sources == null;
        }
    }

    private long[] high = new long[INITIAL_CAPACITY];
    private long[] low = new long[INITIAL_CAPACITY];
    private byte[] hashForm = new byte[INITIAL_CAPACITY];
    private String[] names = new String[INITIAL_CAPACITY];
    private long[] sizes = new long[INITIAL_CAPACITY];
    private String[] types = new String[INITIAL_CAPACITY];
    /** Value of the FT_SOURCES tag (unsigned); its tag type in {@link #sourceTagTypes}, 0 without such a tag. */
    private int[] sourceCounts = new int[INITIAL_CAPACITY];
    private byte[] sourceTagTypes = new byte[INITIAL_CAPACITY];
    private Extra[] extras = new Extra[INITIAL_CAPACITY];
    /** True for the numbers in use. */
    private boolean[] used = new boolean[INITIAL_CAPACITY];

    /** Numbers given so far; the freed ones are reused first. */
    private int end;
    private int count;
    private int[] free = new int[16];
    private int freeCount;

    /** File number + 1 of the hexadecimal hashes by their 128-bit value; 0 is an empty slot. */
    private int[] slots = new int[INITIAL_CAPACITY * 2];
    /** Files whose hash is not hexadecimal, by exact hash. */
    private final Map<String, Integer> rawHashes = new HashMap<>();
    /** One instance of each type name. */
    private final Map<String, String> typeNames = new HashMap<>();

    int count() {
        return count;
    }

    /** Number of the file with this hash (case ignored for hexadecimal hashes), or -1. */
    int find(String hash) {
        if (hash == null) return -1;
        long[] value = Md4Set.parse(hash);
        if (value == null) {
            Integer id = rawHashes.get(hash);
            return id == null ? -1 : id;
        }
        return slotId(value[0], value[1]);
    }

    /** Stores a new file and returns its number; its hash must not be stored yet. */
    int add(FileMetadata meta) {
        int id = freeCount > 0 ? free[--freeCount] : end++;
        if (id >= used.length) grow(Math.max(INITIAL_CAPACITY, used.length + (used.length >> 1)));
        used[id] = true;
        count++;
        long[] value = Md4Set.parse(meta.hash());
        Extra extra = null;
        if (value == null) {
            extra = new Extra();
            extra.hash = meta.hash();
            hashForm[id] = RAW;
            rawHashes.put(meta.hash(), id);
        } else {
            high[id] = value[0];
            low[id] = value[1];
            byte form = formOf(meta.hash());
            hashForm[id] = form;
            if (form == RAW) {
                extra = new Extra();
                extra.hash = meta.hash();
            }
            if ((count + 1) * 4L > slots.length * 3L) resizeSlots(slots.length * 2);
            insertSlot(id);
        }
        extras[id] = extra;
        setContent(id, meta);
        return id;
    }

    /** Replaces the content of a file (name, size, type, tags, sources); its hash is kept as stored. */
    void set(int id, FileMetadata meta) {
        setContent(id, meta);
    }

    /** Frees the number of a file. */
    void remove(int id) {
        if (hashForm[id] == RAW && Md4Set.parse(extras[id].hash) == null) {
            rawHashes.remove(extras[id].hash);
        } else {
            removeSlot(id);
        }
        used[id] = false;
        names[id] = null;
        types[id] = null;
        extras[id] = null;
        count--;
        if (freeCount == free.length) free = Arrays.copyOf(free, free.length * 2);
        free[freeCount++] = id;
    }

    String hash(int id) {
        return switch (hashForm[id]) {
            case LOWER_CASE -> hex(high[id], low[id], false);
            case UPPER_CASE -> hex(high[id], low[id], true);
            default -> extras[id].hash;
        };
    }

    String name(int id) {
        return names[id];
    }

    /** The live source map of a file, or null if no client ever published it. */
    ConcurrentHashMap<String, ClientState> sources(int id) {
        Extra extra = extras[id];
        return extra == null ? null : extra.sources;
    }

    /** The file as a {@link FileMetadata}, built for the caller; its source map is the live one. */
    FileMetadata view(int id) {
        Extra extra = extras[id];
        List<Tag> tags;
        if (extra != null && extra.tags != null) {
            tags = extra.tags;
        } else if (sourceTagTypes[id] != 0) {
            tags = List.of(new Tag(sourceTagTypes[id], Tag.NAME_SOURCES, sourceTagValue(id)));
        } else {
            tags = List.of();
        }
        ConcurrentHashMap<String, ClientState> sources = extra == null ? null : extra.sources;
        return sources == null
                ? FileMetadata.withoutSources(hash(id), names[id], sizes[id], types[id], tags)
                : new FileMetadata(hash(id), names[id], sizes[id], types[id], tags, sources);
    }

    /** Calls {@code action} with the number of every file. */
    void forEach(IntConsumer action) {
        for (int id = 0; id < end; id++) {
            if (used[id]) action.accept(id);
        }
    }

    /** Releases the free slots of the arrays, once the files are loaded. */
    void trim() {
        if (used.length > end + INITIAL_CAPACITY) grow(end + INITIAL_CAPACITY);
    }

    private void setContent(int id, FileMetadata meta) {
        names[id] = meta.name();
        sizes[id] = meta.size();
        types[id] = meta.type() == null ? null : typeNames.computeIfAbsent(meta.type(), t -> t);
        List<Tag> tags = meta.tags() == null ? List.of() : meta.tags();
        Extra extra = extras[id];
        sourceTagTypes[id] = 0;
        sourceCounts[id] = 0;
        List<Tag> otherTags = null;
        if (tags.size() == 1 && Tag.NAME_SOURCES.equals(tags.getFirst().name()) && tags.getFirst().isInt()) {
            sourceTagTypes[id] = tags.getFirst().type();
            sourceCounts[id] = (int) Math.min(tags.getFirst().unsignedValue(), 0xFFFFFFFFL);
        } else if (!tags.isEmpty()) {
            otherTags = List.copyOf(tags);
        }
        ConcurrentHashMap<String, ClientState> sources = meta.hasOwnSources() ? meta.sources() : null;
        if (otherTags != null || sources != null || extra != null) {
            if (extra == null) extra = new Extra();
            extra.tags = otherTags;
            extra.sources = sources;
            extras[id] = extra.isEmpty() ? null : extra;
        }
    }

    private Object sourceTagValue(int id) {
        long value = Integer.toUnsignedLong(sourceCounts[id]);
        return switch (sourceTagTypes[id]) {
            case Tag.TYPE_INT16 -> (short) value;
            case Tag.TYPE_INT8 -> (byte) value;
            case Tag.TYPE_UINT64 -> value;
            default -> (int) value;
        };
    }

    private static byte formOf(String hash) {
        boolean lower = false, upper = false;
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            if (c >= 'a' && c <= 'f') lower = true;
            else if (c >= 'A' && c <= 'F') upper = true;
        }
        return lower && upper ? RAW : upper ? UPPER_CASE : LOWER_CASE;
    }

    private static String hex(long high, long low, boolean upperCase) {
        char[] chars = new char[32];
        char a = upperCase ? 'A' : 'a';
        for (int i = 15; i >= 0; i--) {
            int h = (int) (high & 0xF), l = (int) (low & 0xF);
            chars[i] = (char) (h < 10 ? '0' + h : a + h - 10);
            chars[i + 16] = (char) (l < 10 ? '0' + l : a + l - 10);
            high >>>= 4;
            low >>>= 4;
        }
        return new String(chars);
    }

    private void grow(int capacity) {
        high = Arrays.copyOf(high, capacity);
        low = Arrays.copyOf(low, capacity);
        hashForm = Arrays.copyOf(hashForm, capacity);
        names = Arrays.copyOf(names, capacity);
        sizes = Arrays.copyOf(sizes, capacity);
        types = Arrays.copyOf(types, capacity);
        sourceCounts = Arrays.copyOf(sourceCounts, capacity);
        sourceTagTypes = Arrays.copyOf(sourceTagTypes, capacity);
        extras = Arrays.copyOf(extras, capacity);
        used = Arrays.copyOf(used, capacity);
    }

    private static int index(long h, long l, int mask) {
        long x = h * 0x9E3779B97F4A7C15L ^ l;
        x ^= x >>> 33;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        return (int) x & mask;
    }

    private int slotId(long h, long l) {
        int mask = slots.length - 1;
        for (int i = index(h, l, mask); ; i = (i + 1) & mask) {
            int id = slots[i] - 1;
            if (id < 0) return -1;
            if (high[id] == h && low[id] == l) return id;
        }
    }

    private void insertSlot(int id) {
        int mask = slots.length - 1;
        int i = index(high[id], low[id], mask);
        while (slots[i] != 0) i = (i + 1) & mask;
        slots[i] = id + 1;
    }

    private void removeSlot(int id) {
        int mask = slots.length - 1;
        int i = index(high[id], low[id], mask);
        while (slots[i] != id + 1) i = (i + 1) & mask;
        // Backward shift: the following entries of the run move back, so that no lookup stops on a hole
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            int other = slots[j] - 1;
            if (other < 0) break;
            int home = index(high[other], low[other], mask);
            boolean between = i <= j ? (i < home && home <= j) : (i < home || home <= j);
            if (!between) {
                slots[i] = slots[j];
                i = j;
            }
        }
        slots[i] = 0;
    }

    private void resizeSlots(int capacity) {
        slots = new int[capacity];
        for (int id = 0; id < end; id++) {
            if (used[id] && !(hashForm[id] == RAW && Md4Set.parse(extras[id].hash) == null)) insertSlot(id);
        }
    }
}
