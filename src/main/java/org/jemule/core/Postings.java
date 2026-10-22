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
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Hashes of the files whose name contains one word of the inverted index: an array of references to the hash
 * strings, 4 to 8 bytes per file instead of about 45 for a concurrent set. Readers iterate an immutable state (array,
 * size) without locking: an append writes past the size they see then publishes a new state, a removal copies the
 * array. The caller must not add a hash twice.
 */
final class Postings implements Iterable<String> {
    private record State(String[] hashes, int size) {}

    private volatile State state;

    Postings(String first) {
        state = new State(new String[]{first}, 1);
    }

    synchronized void add(String hash) {
        State s = state;
        String[] hashes = s.hashes();
        if (s.size() == hashes.length) {
            hashes = Arrays.copyOf(hashes, hashes.length + Math.max(1, hashes.length >> 1));
        }
        hashes[s.size()] = hash;
        state = new State(hashes, s.size() + 1);
    }

    /** Removes a hash; the array is copied, so that readers keep iterating a consistent state. */
    synchronized void remove(String hash) {
        State s = state;
        for (int i = 0; i < s.size(); i++) {
            if (s.hashes()[i].equals(hash)) {
                String[] copy = new String[Math.max(1, s.size() - 1)];
                System.arraycopy(s.hashes(), 0, copy, 0, i);
                System.arraycopy(s.hashes(), i + 1, copy, i, s.size() - i - 1);
                state = new State(copy, s.size() - 1);
                return;
            }
        }
    }

    /** Drops the free slots left by the growth of the array (up to a third of it), once the files are loaded. */
    synchronized void trim() {
        State s = state;
        if (s.hashes().length > s.size()) state = new State(Arrays.copyOf(s.hashes(), s.size()), s.size());
    }

    int size() {
        return state.size();
    }

    @Override
    public Iterator<String> iterator() {
        State s = state;
        return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < s.size();
            }

            @Override
            public String next() {
                if (next >= s.size()) throw new NoSuchElementException();
                return s.hashes()[next++];
            }
        };
    }
}
