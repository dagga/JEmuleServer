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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Relay mode: the dictionaries of the fallback index leave the heap, and come back at its first use. */
class RelayDictionaryTest {

    private static FileMetadata file(int n, String name) {
        return new FileMetadata(String.format("%032X", n), name, 1_000_000L + n, "Audio", List.of(),
                new java.util.concurrent.ConcurrentHashMap<>());
    }

    private static SearchQuery name(String word) {
        return new SearchQuery.TermQuery(word, SearchQuery.ID_FILENAME);
    }

    /** A feed whose relay mode follows {@code relayMode}; the files offered are dropped. */
    private static FileIndex.RelayFeed feed(AtomicBoolean relayMode) {
        return new FileIndex.RelayFeed() {
            public boolean relayMode() {
                return relayMode.get();
            }

            public void offer(FileMetadata file, FileSource source) {
            }
        };
    }

    @Test
    void releasedInRelayModeAndReadAgainAtTheFirstLocalSearch() {
        try (FileIndex index = new FileIndex(null)) {
            index.addFiles(List.of(file(1, "Magma Live Paris.mp3"), file(2, "Other Record.mp3")));
            int words = index.wordCount();
            assertTrue(words > 0);
            AtomicBoolean relayMode = new AtomicBoolean(true);
            index.setRelayFeed(feed(relayMode));

            assertTrue(index.releaseDictionaries());
            assertFalse(index.dictionariesLoaded());
            assertEquals(0, index.wordCount());
            assertFalse(index.releaseDictionaries(), "already released");

            // A substring of a word is found through the dictionary, read again for the search
            assertEquals(1, index.searchComplex(name("agm"), 10).size());
            assertTrue(index.dictionariesLoaded());
            assertEquals(words, index.wordCount());
            assertEquals(1, index.search("paris", 10).size());
        }
    }

    @Test
    void readAgainBeforeAFileIsWrittenWhenTheRelayModeEnds() {
        try (FileIndex index = new FileIndex(null)) {
            index.addFile(file(1, "Magma Live Paris.mp3"));
            AtomicBoolean relayMode = new AtomicBoolean(true);
            index.setRelayFeed(feed(relayMode));
            assertTrue(index.releaseDictionaries());

            relayMode.set(false);
            index.addFile(file(2, "Zappa Hot Rats.mp3"));
            assertTrue(index.dictionariesLoaded());
            assertEquals(1, index.searchComplex(name("zappa"), 10).size());
            assertEquals(1, index.searchComplex(name("magma"), 10).size(), "the words of the files before the release");
        }
    }

    @Test
    void keptOutsideTheRelayMode() {
        try (FileIndex index = new FileIndex(null)) {
            index.addFile(file(1, "Magma Live Paris.mp3"));
            assertFalse(index.releaseDictionaries(), "no feed");
            index.setRelayFeed(feed(new AtomicBoolean(false)));
            assertFalse(index.releaseDictionaries(), "feed not in relay mode");
            assertTrue(index.dictionariesLoaded());
            assertTrue(index.wordCount() > 0);
        }
    }
}
