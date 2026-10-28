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

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Numbers ({@link FileTable}) of the files whose name contains one word of the inverted index, in increasing order,
 * each stored as its gap from the previous one in a variable-length integer: one or two bytes for the frequent
 * words, three for the rare ones, instead of four. The number of a word found in one file only is kept in a field,
 * without an array. Written under the write lock of {@link HeapFileIndex} and read under its read lock. The caller must
 * not add a number twice.
 */
final class Postings {
    /** Gaps, 7 bits per byte, the high bit set on every byte but the last of a gap; null for a single number. */
    private byte[] gaps;
    private int length;
    private int size;
    /** The highest number, the single one when {@link #gaps} is null. */
    private int last;

    Postings(int first) {
        last = first;
        size = 1;
    }

    void add(int id) {
        if (size == 0) {
            last = id;
            size = 1;
        } else if (id > last) {
            // The usual case: the numbers are given in increasing order, apart from the reused ones
            if (gaps == null) {
                gaps = new byte[8];
                length = write(gaps, 0, last);
            }
            ensure(5);
            length = write(gaps, length, id - last);
            last = id;
            size++;
        } else {
            int[] ids = toArray();
            int at = -Arrays.binarySearch(ids, id) - 1;
            int[] merged = new int[ids.length + 1];
            System.arraycopy(ids, 0, merged, 0, at);
            merged[at] = id;
            System.arraycopy(ids, at, merged, at + 1, ids.length - at);
            encode(merged, merged.length);
        }
    }

    void remove(int id) {
        if (id > last) return;
        int[] ids = toArray();
        int at = Arrays.binarySearch(ids, id);
        if (at < 0) return;
        System.arraycopy(ids, at + 1, ids, at, ids.length - at - 1);
        encode(ids, ids.length - 1);
    }

    /** Drops the free bytes left by the growth of the array, once the files are loaded. */
    void trim() {
        if (gaps != null && gaps.length > length) gaps = Arrays.copyOf(gaps, length);
    }

    int size() {
        return size;
    }

    void forEach(IntConsumer action) {
        if (gaps == null) {
            if (size == 1) action.accept(last);
            return;
        }
        int id = 0;
        for (int i = 0; i < length; ) {
            int gap = 0;
            for (int shift = 0; ; shift += 7) {
                byte b = gaps[i++];
                gap |= (b & 0x7F) << shift;
                if (b >= 0) break;
            }
            id += gap;
            action.accept(id);
        }
    }

    private int[] toArray() {
        int[] ids = new int[size];
        int[] n = {0};
        forEach(id -> ids[n[0]++] = id);
        return ids;
    }

    /** Stores the first {@code count} numbers of {@code ids}, in increasing order; an empty list keeps no number. */
    private void encode(int[] ids, int count) {
        size = count;
        last = count == 0 ? 0 : ids[count - 1];
        if (count <= 1) {
            gaps = null;
            length = 0;
            return;
        }
        byte[] out = new byte[count * 5];
        int pos = 0, previous = 0;
        for (int i = 0; i < count; i++) {
            pos = write(out, pos, ids[i] - previous);
            previous = ids[i];
        }
        gaps = Arrays.copyOf(out, pos);
        length = pos;
    }

    private void ensure(int bytes) {
        if (length + bytes <= gaps.length) return;
        // Half of the length while short, an eighth for the long lists of the common words
        int growth = Math.max(bytes, gaps.length >= 256 ? gaps.length >> 3 : gaps.length >> 1);
        gaps = Arrays.copyOf(gaps, gaps.length + growth);
    }

    private static int write(byte[] out, int pos, int value) {
        while ((value & ~0x7F) != 0) {
            out[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out[pos++] = (byte) value;
        return pos;
    }
}
