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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Set of MD4 hashes (32 hexadecimal characters, case ignored) kept as 128-bit values in two arrays with linear
 * probing: 21 to 43 bytes per hash (16 per slot, 37 to 75% of the slots used) instead of about 110 for a concurrent set of strings, which matters for the
 * hundreds of thousands of banned hashes. Any other string is kept apart, in upper case, so that the set accepts
 * what a set of strings did. Thread-safe; meant for sets read far more often than written.
 */
public final class Md4Set {
    private static final int MIN_CAPACITY = 16;

    private long[] high = new long[MIN_CAPACITY];
    private long[] low = new long[MIN_CAPACITY];
    /** Hashes stored in the arrays; the all-zero hash marks an empty slot, so it is flagged apart. */
    private int size;
    private boolean zeroHash;
    private final Set<String> others = new HashSet<>();

    public synchronized boolean add(String hash) {
        if (hash == null) return false;
        long[] value = parse(hash);
        if (value == null) return others.add(hash.toUpperCase(Locale.ROOT));
        if (value[0] == 0 && value[1] == 0) {
            boolean added = !zeroHash;
            zeroHash = true;
            return added;
        }
        if (slot(value[0], value[1]) >= 0) return false;
        if ((size + 1) * 4L > high.length * 3L) resize(high.length * 2);
        insert(value[0], value[1]);
        size++;
        return true;
    }

    public synchronized boolean contains(String hash) {
        if (hash == null) return false;
        long[] value = parse(hash);
        if (value == null) return !others.isEmpty() && others.contains(hash.toUpperCase(Locale.ROOT));
        if (value[0] == 0 && value[1] == 0) return zeroHash;
        return slot(value[0], value[1]) >= 0;
    }

    public synchronized boolean remove(String hash) {
        if (hash == null) return false;
        long[] value = parse(hash);
        if (value == null) return others.remove(hash.toUpperCase(Locale.ROOT));
        if (value[0] == 0 && value[1] == 0) {
            boolean removed = zeroHash;
            zeroHash = false;
            return removed;
        }
        int i = slot(value[0], value[1]);
        if (i < 0) return false;
        // Backward shift: the following entries of the run move back, so that no lookup stops on a hole
        int mask = high.length - 1;
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            if (high[j] == 0 && low[j] == 0) break;
            int home = index(high[j], low[j], mask);
            boolean between = i <= j ? (i < home && home <= j) : (i < home || home <= j);
            if (!between) {
                high[i] = high[j];
                low[i] = low[j];
                i = j;
            }
        }
        high[i] = 0;
        low[i] = 0;
        size--;
        return true;
    }

    public synchronized void clear() {
        high = new long[MIN_CAPACITY];
        low = new long[MIN_CAPACITY];
        size = 0;
        zeroHash = false;
        others.clear();
    }

    public synchronized int size() {
        return size + (zeroHash ? 1 : 0) + others.size();
    }

    private int slot(long h, long l) {
        int mask = high.length - 1;
        for (int i = index(h, l, mask); ; i = (i + 1) & mask) {
            if (high[i] == h && low[i] == l) return i;
            if (high[i] == 0 && low[i] == 0) return -1;
        }
    }

    private void insert(long h, long l) {
        int mask = high.length - 1;
        int i = index(h, l, mask);
        while (high[i] != 0 || low[i] != 0) i = (i + 1) & mask;
        high[i] = h;
        low[i] = l;
    }

    private void resize(int capacity) {
        long[] oldHigh = high, oldLow = low;
        high = new long[capacity];
        low = new long[capacity];
        for (int i = 0; i < oldHigh.length; i++) {
            if (oldHigh[i] != 0 || oldLow[i] != 0) insert(oldHigh[i], oldLow[i]);
        }
    }

    private static int index(long h, long l, int mask) {
        long x = h * 0x9E3779B97F4A7C15L ^ l;
        x ^= x >>> 33;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        return (int) x & mask;
    }

    /** The two halves of a 32-character hexadecimal hash, or null for any other string. */
    static long[] parse(String hash) {
        if (hash.length() != 32) return null;
        long h = 0, l = 0;
        for (int i = 0; i < 32; i++) {
            int digit = Character.digit(hash.charAt(i), 16);
            if (digit < 0) return null;
            if (i < 16) h = h << 4 | digit;
            else l = l << 4 | digit;
        }
        return new long[]{h, l};
    }
}
