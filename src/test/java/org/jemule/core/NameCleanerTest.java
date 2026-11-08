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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NameCleanerTest {
    private final NameCleaner cleaner = new NameCleaner(NameCleaner.DEFAULT_RULE);

    @TempDir
    Path tempDir;

    @Test
    void theTagsOfTheReferenceServerGo() {
        // Names of the reference server's database
        assertEquals("Sydney Samson - Riverside (Extended Mix).mp3",
                cleaner.clean("[kad emule-qt.org] Sydney Samson - Riverside (Extended Mix).mp3"));
        assertEquals("The Matrix Resurrections - Dual.avi",
                cleaner.clean("[kad emule-qt.org] The Matrix Resurrections - Dual.avi"));
        assertEquals("Some Album.zip", cleaner.clean("[torrent emule-qt.org] Some Album.zip"));
        assertEquals("Some Show S01E01.mkv", cleaner.clean("[web emule-qt.org] Some Show S01E01.mkv"));
        assertEquals("Viktor Kane - Sin testigos.epub",
                cleaner.clean("Viktor Kane - Sin testigos [kad emule-qt.org].epub"));
        assertEquals("Whenifeelnaughty Whenifeelnaughty - Br01N - 11N Full.mpg",
                cleaner.clean("Whenifeelnaughty [kad emule-qt.org] Whenifeelnaughty - Br01N - 11N Full.mpg"));
        assertEquals("aa V.A. - Mojo Presents - Cohen Covered.zip",
                cleaner.clean("aa[kad emule-qt.org] V.A. - Mojo Presents - Cohen Covered.zip"));
        assertEquals("spa.mkv", cleaner.clean("spa[kad emule-qt.org].mkv"));
    }

    @Test
    void theRuleIsComparedWithoutCaseInAnyKindOfBrackets() {
        assertEquals("Song.mp3", cleaner.clean("[KAD eMule-Qt.ORG] Song.mp3"));
        assertEquals("Song.mp3", cleaner.clean("(from emule-qt.org) Song.mp3"));
        assertEquals("Song.mp3", cleaner.clean("Song {EMULE-QT.ORG}.mp3"));
        assertEquals("Song.mp3", cleaner.clean("emule-qt.org Song.mp3"), "the text itself, outside any group");
        assertEquals("Artist - Song (Remix).mp3", cleaner.clean("Artist - Song (Remix) [kad emule-qt.org].mp3"),
                "the other groups stay");
    }

    @Test
    void danglingSeparatorsGoButTheExtensionStays() {
        assertEquals("Song.MP3", cleaner.clean("[kad emule-qt.org] - Song.MP3"));
        assertEquals("Song.mp3", cleaner.clean("Song - [kad emule-qt.org].mp3"));
        assertEquals("Song.mp3", cleaner.clean("[kad emule-qt.org]_Song.mp3"));
        assertEquals("-=Title=-.mp3", cleaner.clean("[kad emule-qt.org] -=Title=-.mp3"), "a dash stuck to a word stays");
        assertEquals("Some Folder", cleaner.clean("Some Folder [kad emule-qt.org]"), "a name without extension");
        assertEquals("Archive.tar.gz", cleaner.clean("[kad emule-qt.org] Archive.tar.gz"));
    }

    @Test
    void aNameWithoutRuleIsNeverTouched() {
        String name = "  odd  name -_ with spaces .mp3";
        assertSame(name, cleaner.clean(name));
        assertSame(name, new NameCleaner().clean(name), "no rule at all");
        assertNull(cleaner.clean((String) null));
    }

    @Test
    void anInvalidOrEmptyResultKeepsTheOriginalName() {
        assertEquals("[kad emule-qt.org].mp3", cleaner.clean("[kad emule-qt.org].mp3"), "nothing left before the extension");
        assertEquals("[kad emule-qt.org]", cleaner.clean("[kad emule-qt.org]"));
        assertEquals(" - [kad emule-qt.org] - ", cleaner.clean(" - [kad emule-qt.org] - "));
        // A control character is invalid: HandlerUtils.isValidFilename refuses the cleaned name
        String control = "Song\u0001 [kad emule-qt.org].mp3";
        assertEquals(control, cleaner.clean(control));
    }

    @Test
    void severalRulesApply() {
        NameCleaner two = new NameCleaner("emule-qt.org", "www.example.test");
        assertEquals("Song.mp3", two.clean("[kad emule-qt.org] Song (www.EXAMPLE.test).mp3"));
    }

    @Test
    void aFileKeepsEverythingButItsName() {
        FileMetadata file = FileMetadata.withoutSources("11111111111111111111111111111111",
                "[kad emule-qt.org] Song.mp3", 123, "Audio", List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 7)));
        FileMetadata cleaned = cleaner.clean(file);
        assertEquals("Song.mp3", cleaned.name());
        assertEquals(file.hash(), cleaned.hash());
        assertEquals(123, cleaned.size());
        assertEquals("Audio", cleaned.type());
        assertEquals(7, cleaned.sourceCount());
        FileMetadata clean = new FileMetadata("22222222222222222222222222222222", "Song.mp3", 1, "Audio");
        assertSame(clean, cleaner.clean(clean));
    }

    @Test
    void rulesAreValidated() {
        assertEquals("emule-qt.org", NameCleaner.normalize("  eMule-Qt.ORG "));
        assertNull(NameCleaner.normalize("   "));
        assertNull(NameCleaner.normalize("x".repeat(NameCleaner.MAX_RULE_LENGTH + 1)));
        assertNotNull(NameCleaner.normalize("x".repeat(NameCleaner.MAX_RULE_LENGTH)));
        assertThrows(IllegalArgumentException.class, () -> cleaner.add(""));
        assertEquals("emule-qt.org", cleaner.add("EMULE-QT.ORG"), "the same rule once");
        assertEquals(1, cleaner.rules().size());
    }

    @Test
    void theRulesAreKeptInTheDatabaseTheDefaultOneWrittenOnce() throws Exception {
        String path = tempDir.resolve("rules").toString();
        try (DatabaseManager db = new DatabaseManager(path)) {
            NameCleaner loaded = NameCleaner.load(db);
            assertEquals(List.of(NameCleaner.DEFAULT_RULE), loaded.rules().stream().map(NameCleaner.Rule::text).toList(),
                    "the default rule at the first start");
            assertTrue(loaded.rules().getFirst().addedAt() > 0);
            loaded.add("  [Another Tag] ");
            assertTrue(loaded.remove(NameCleaner.DEFAULT_RULE));
            assertFalse(loaded.remove("missing"));
        }
        try (DatabaseManager db = new DatabaseManager(path)) {
            NameCleaner reloaded = NameCleaner.load(db);
            assertEquals(List.of("[another tag]"), reloaded.rules().stream().map(NameCleaner.Rule::text).toList(),
                    "the removed default rule is not written again");
            assertEquals("Song.mp3", reloaded.clean("[another tag] Song.mp3"));
        }
    }

    @Test
    void withoutDatabaseTheDefaultRuleApplies() {
        assertEquals("Song.mp3", NameCleaner.load(null).clean("[kad emule-qt.org] Song.mp3"));
    }
}
