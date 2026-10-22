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
 * Numbers ({@link FileTable}) of the files whose name contains one word of the inverted index: 4 bytes per file.
 * Written under the write lock of {@link FileIndex} and read under its read lock. The caller must not add a number
 * twice.
 */
final class Postings {
    private int[] ids;
    private int size;

    Postings(int first) {
        ids = new int[]{first};
        size = 1;
    }

    void add(int id) {
        // Half of the length while short, an eighth for the long lists of the common words
        if (size == ids.length) ids = Arrays.copyOf(ids, ids.length + Math.max(1, ids.length >= 64 ? ids.length >> 3 : ids.length >> 1));
        ids[size++] = id;
    }

    void remove(int id) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == id) {
                System.arraycopy(ids, i + 1, ids, i, size - i - 1);
                size--;
                return;
            }
        }
    }

    /** Drops the free slots left by the growth of the array (up to a third of it), once the files are loaded. */
    void trim() {
        if (ids.length > size) ids = Arrays.copyOf(ids, size);
    }

    int size() {
        return size;
    }

    void forEach(IntConsumer action) {
        for (int i = 0; i < size; i++) action.accept(ids[i]);
    }
}
