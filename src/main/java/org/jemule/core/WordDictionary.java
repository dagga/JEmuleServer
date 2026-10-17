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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the words of the index containing a keyword. The words are concatenated in one string searched with
 * {@link String#indexOf(String, int)}: a few megabytes scanned sequentially, instead of hundreds of thousands of
 * scattered strings. The words added since the last snapshot are kept aside and scanned one by one until the next
 * rebuild.
 */
final class WordDictionary {
    /** Words added since the last snapshot beyond which the snapshot is rebuilt. */
    static final int REBUILD_THRESHOLD = 10_000;
    /** Separates the words in the snapshot: never part of a word (letters and digits only). */
    private static final char SEPARATOR = ' ';

    /** Words separated by {@link #SEPARATOR}; {@code starts[i]} is the offset of {@code words[i]}. */
    private record Snapshot(String text, int[] starts, String[] words) {
        static final Snapshot EMPTY = new Snapshot("", new int[0], new String[0]);
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile Set<String> pending = ConcurrentHashMap.newKeySet();

    /** Registers a new word; to be called once the word is in the index. */
    void add(String word) {
        pending.add(word);
    }

    /**
     * Words containing {@code token}. A removed word may still be returned until the next rebuild: the caller looks
     * it up in the index anyway.
     *
     * @param allWords Current words of the index, read when the snapshot is rebuilt.
     */
    List<String> wordsContaining(String token, Collection<String> allWords) {
        if (pending.size() > REBUILD_THRESHOLD) {
            rebuild(allWords);
        }
        List<String> found = new ArrayList<>();
        Snapshot s = snapshot;
        String text = s.text();
        int from = 0;
        while (from < text.length()) {
            int pos = text.indexOf(token, from);
            if (pos < 0) break;
            int i = Arrays.binarySearch(s.starts(), pos);
            if (i < 0) i = -i - 2; // the word starting before the match
            found.add(s.words()[i]);
            from = i + 1 < s.starts().length ? s.starts()[i + 1] : text.length(); // one hit per word
        }
        for (String word : pending) {
            if (word.contains(token)) found.add(word);
        }
        return found;
    }

    private synchronized void rebuild(Collection<String> allWords) {
        if (pending.size() <= REBUILD_THRESHOLD) return; // rebuilt by another thread meanwhile
        // The words added from now on go to the new pending set; those added before are already in allWords
        pending = ConcurrentHashMap.newKeySet();
        List<String> words = new ArrayList<>(allWords);
        int[] starts = new int[words.size()];
        StringBuilder text = new StringBuilder(words.size() * 9);
        for (int i = 0; i < words.size(); i++) {
            starts[i] = text.length();
            text.append(words.get(i)).append(SEPARATOR);
        }
        snapshot = new Snapshot(text.toString(), starts, words.toArray(String[]::new));
    }
}
