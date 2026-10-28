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
import org.jemule.security.ChildAbuseFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** What the Lucene index does apart from {@link HeapFileIndex} ({@code LuceneIndexComparisonTest} compares them). */
class FileIndexTest {
    private static final SearchQuery LINUX = new SearchQuery.TermQuery("linux", SearchQuery.ID_FILENAME);

    private static FileMetadata file(String hash, String name, int sources) {
        return FileMetadata.withoutSources(hash, name, 1_000_000L, "Pro",
                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources)));
    }

    private static ClientState client(int id) {
        return new ClientState(InetAddress.getLoopbackAddress(), 4662, id, System.currentTimeMillis(), new AtomicLong());
    }

    private static FileMetadata publishedBy(String hash, String name, ClientState client) {
        ConcurrentHashMap<String, ClientState> sources = new ConcurrentHashMap<>();
        sources.put(FileIndex.sourceKey(client), client);
        client.publishedHashes().add(hash);
        return new FileMetadata(hash, name, 1_000_000L, "Pro", List.of(), sources);
    }

    private static List<String> hashes(List<FileMetadata> files) {
        return files.stream().map(FileMetadata::hash).toList();
    }

    @Test
    void theWritesReachTheSearchesAtTheRefreshButTheLookupsAtOnce() {
        FileIndex index = new FileIndex(null);
        index.refreshEvery(3_600_000); // never during the test
        try (index) {
            index.addFile(file("0123456789ABCDEF0123456789ABCDEF", "linux.iso", 3));
            assertTrue(index.contains("0123456789abcdef0123456789abcdef"), "case ignored");
            assertEquals(1, index.fileCount());
            assertEquals(List.of(), index.searchComplex(LINUX, 10));
            index.refresh();
            assertEquals(List.of("0123456789ABCDEF0123456789ABCDEF"), hashes(index.searchComplex(LINUX, 10)));

            assertTrue(index.removeFile("0123456789abcdef0123456789abcdef"));
            assertFalse(index.contains("0123456789ABCDEF0123456789ABCDEF"));
            assertFalse(index.removeFile("0123456789ABCDEF0123456789ABCDEF"), "already removed");
            assertEquals(0, index.fileCount());
            index.refresh();
            assertEquals(List.of(), index.searchComplex(LINUX, 10));

            index.addFile(file("0123456789ABCDEF0123456789ABCDEF", "linux again.iso", 1));
            assertTrue(index.contains("0123456789ABCDEF0123456789ABCDEF"), "added again after its removal");
        }
    }

    @Test
    void eachStartRebuildsTheIndexFromTheDatabase(@TempDir Path dir) throws Exception {
        Path indexPath = dir.resolve("db.index");
        try (DatabaseManager db = new DatabaseManager(dir.resolve("db").toString())) {
            try (FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults(), indexPath)) {
                index.addFiles(List.of(file("11111111111111111111111111111111", "linux one.iso", 2),
                        file("22222222222222222222222222222222", "linux two.iso", 5)));
                index.removeFile("11111111111111111111111111111111");
            }
            try (FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults(), indexPath)) {
                assertEquals(1, index.fileCount());
                List<FileMetadata> found = index.searchComplex(LINUX, 10);
                assertEquals(List.of("22222222222222222222222222222222"), hashes(found));
                assertEquals(5, found.getFirst().sourceCount());
            }
            try (Stream<Path> files = Files.list(indexPath)) {
                assertTrue(files.findAny().isPresent(), "the index is on disk");
            }
        }
    }

    @Test
    void anIndexClosedCleanlyIsReopenedWhileTheDatabaseDidNotChange(@TempDir Path dir) throws Exception {
        Path indexPath = dir.resolve("db.index");
        try (DatabaseManager db = new DatabaseManager(dir.resolve("db").toString())) {
            try (FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults(), indexPath)) {
                assertFalse(index.reused(), "no index yet");
                index.addFiles(List.of(file("11111111111111111111111111111111", "linux one.iso", 2),
                        file("22222222222222222222222222222222", "linux two.iso", 5)));
                index.addFile(FileMetadata.withoutSources("33333333333333333333333333333333", "song.mp3", 10L, "Audio",
                        List.of(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_ARTIST), "Some Linux Band"))));
                index.removeFile("11111111111111111111111111111111");
            }
            try (FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults(), indexPath)) {
                assertTrue(index.reused());
                assertEquals(2, index.fileCount());
                assertEquals(List.of("22222222222222222222222222222222"), hashes(index.searchComplex(
                        new SearchQuery.TermQuery("inu", SearchQuery.ID_FILENAME), 10)), "the words are known again");
                assertEquals(List.of("33333333333333333333333333333333"), hashes(index.searchComplex(
                        new SearchQuery.TermQuery("linux band", SearchQuery.ID_MEDIA_ARTIST), 10)), "the tag values too");
                assertEquals(5, index.searchComplex(LINUX, 10).getFirst().sourceCount());
                index.addFile(file("44444444444444444444444444444444", "linux four.iso", 1));
                index.closeWithoutToken(); // a crash: the database has the fourth file, the index's commit does not
            }
            try (FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults(), indexPath)) {
                assertFalse(index.reused(), "rebuilt after a crash");
                assertEquals(3, index.fileCount());
            }
            try (FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults(), indexPath)) {
                assertTrue(index.reused());
            }
            db.setStat(FileIndex.INDEX_TOKEN_STAT, 42); // another database, or a backup restored
            try (FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults(), indexPath)) {
                assertFalse(index.reused());
                assertEquals(3, index.fileCount());
            }
            java.util.Properties keywords = new java.util.Properties();
            keywords.setProperty("banned", "linux");
            try (FileIndex index = new FileIndex(db, null, ChildAbuseFilter.fromProperties(keywords), indexPath)) {
                assertFalse(index.reused(), "files to check with other keywords");
                assertEquals(1, index.fileCount(), "the files of the new keywords deleted from the database");
            }
        }
    }

    @Test
    void anotherIndexOnTheSameDirectoryStaysInTheHeap(@TempDir Path dir) {
        try (FileIndex first = new FileIndex(null, null, ChildAbuseFilter.defaults(), dir);
             FileIndex second = new FileIndex(null, null, ChildAbuseFilter.defaults(), dir)) {
            first.addFile(file("11111111111111111111111111111111", "linux one.iso", 1));
            second.addFile(file("22222222222222222222222222222222", "linux two.iso", 1));
            assertEquals(List.of("11111111111111111111111111111111"), hashes(first.searchComplex(LINUX, 10)));
            assertEquals(List.of("22222222222222222222222222222222"), hashes(second.searchComplex(LINUX, 10)));
        }
    }

    @Test
    void theOnlineSourcesCountAndAreTheOnlyCompleteOnes() {
        try (FileIndex index = new FileIndex(null)) {
            index.addFiles(List.of(file("11111111111111111111111111111111", "linux one.iso", 1),
                    file("22222222222222222222222222222222", "linux two.iso", 2),
                    file("33333333333333333333333333333333", "linux three.iso", 0)));
            ClientState a = client(1), b = client(2), c = client(3);
            for (ClientState client : List.of(a, b, c)) {
                index.addFile(publishedBy("11111111111111111111111111111111", "linux one.iso", client));
            }
            index.addFile(publishedBy("33333333333333333333333333333333", "linux three.iso", a));
            a.partialHashes().add("33333333333333333333333333333333");

            // Three online sources rank the first file above the second one's two
            assertEquals(List.of("11111111111111111111111111111111", "22222222222222222222222222222222",
                    "33333333333333333333333333333333"), hashes(index.searchComplex(LINUX, 10)));
            SearchQuery moreThanTwo = new SearchQuery.AndQuery(LINUX,
                    new SearchQuery.NumericQuery(SearchQuery.ID_SOURCES, SearchQuery.OP_GREATER, 2));
            assertEquals(List.of("11111111111111111111111111111111"), hashes(index.searchComplex(moreThanTwo, 10)));
            SearchQuery complete = new SearchQuery.AndQuery(LINUX,
                    new SearchQuery.NumericQuery(SearchQuery.ID_COMPLETE_SOURCES, SearchQuery.OP_GREATER, 0));
            assertEquals(List.of("11111111111111111111111111111111"), hashes(index.searchComplex(complete, 10)),
                    "the third file is partial on its only source");
            assertEquals(3, index.getSources("11111111111111111111111111111111", null, 10).size());

            for (ClientState client : List.of(a, b, c)) index.removeSource(client);
            assertEquals(List.of(), index.searchComplex(complete, 10));
            assertEquals(List.of(), index.getSources("11111111111111111111111111111111", null, 10));
            assertEquals(3, index.searchComplex(moreThanTwo, 10).getFirst().sourceCount(),
                    "the count the online sources raised stays");
        }
    }

    @Test
    void aFileWithoutSourcePublishedByAClientTakesItsMetadata() {
        try (FileIndex index = new FileIndex(null)) {
            index.addFile(file("11111111111111111111111111111111", "old name.iso", 4));
            FileMetadata stored = index.addFile(publishedBy("11111111111111111111111111111111", "linux new name.iso",
                    client(1)));
            assertEquals("linux new name.iso", stored.name());
            assertEquals(4, stored.sourceCount(), "the known count is kept");
            assertEquals(List.of("11111111111111111111111111111111"), hashes(index.searchComplex(LINUX, 10)));
            assertEquals(List.of(), index.search("old", 10));
            assertEquals(1, index.fileCount());
        }
    }

    @Test
    void everyKindOfTagValueIsKept() {
        byte[] blob = {1, 2, 3};
        String longTitle = "é".repeat(40_000); // longer than a Lucene term and than writeUTF
        List<Tag> tags = List.of(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_TITLE), longTitle),
                new Tag(Tag.TYPE_INT16, Tag.idName(SearchQuery.ID_MEDIA_BITRATE), (short) 192),
                new Tag(Tag.TYPE_BLOB, Tag.idName(SearchQuery.ID_MEDIA_CODEC), blob),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 7));
        try (FileIndex index = new FileIndex(null)) {
            index.addFile(FileMetadata.withoutSources("11111111111111111111111111111111", "linux.ogg", 10L, "Audio", tags));
            FileMetadata found = index.searchComplex(new SearchQuery.AndQuery(LINUX,
                    new SearchQuery.TermQuery("éé", SearchQuery.ID_MEDIA_TITLE)), 10).getFirst();
            assertEquals(longTitle, found.tags().stream().filter(t -> t.name().equals(Tag.idName(SearchQuery.ID_MEDIA_TITLE)))
                    .findFirst().orElseThrow().value());
            assertEquals((short) 192, found.tags().stream()
                    .filter(t -> t.name().equals(Tag.idName(SearchQuery.ID_MEDIA_BITRATE))).findFirst().orElseThrow().value());
            assertArrayEquals(blob, (byte[]) found.tags().stream()
                    .filter(t -> t.name().equals(Tag.idName(SearchQuery.ID_MEDIA_CODEC))).findFirst().orElseThrow().value());
            assertEquals(7, found.sourceCount());
        }
    }

    @Test
    void theSearchesSeeTheWritesOnceTooManyWaitForTheRefresh() {
        FileIndex index = new FileIndex(null);
        index.refreshEvery(3_600_000);
        try (index) {
            for (int i = 0; i <= FileIndex.MAX_UNREFRESHED; i++) {
                index.addFile(file(String.format("%032X", i), "linux " + i + ".iso", 1));
            }
            assertEquals(FileIndex.MAX_UNREFRESHED + 1, index.fileCount());
            assertFalse(index.searchComplex(LINUX, 10).isEmpty(), "refreshed without waiting for the period");
        }
    }
}
