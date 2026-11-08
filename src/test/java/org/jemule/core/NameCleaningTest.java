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

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The name cleaning of the files coming in by every channel, and of the files indexed before the rules. */
class NameCleaningTest {
    private static final SearchQuery SONG = new SearchQuery.TermQuery("song", SearchQuery.ID_FILENAME);
    private static final SearchQuery KAD = new SearchQuery.TermQuery("kad", SearchQuery.ID_FILENAME);

    @TempDir
    Path tempDir;

    private static String hash(int i) {
        return String.format("%032x", i);
    }

    private static FileMetadata file(int i, String name, int sources) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_CODEC), "mp3"));
        if (sources > 0) tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources));
        return FileMetadata.withoutSources(hash(i), name, 1_000L + i, "Audio", tags);
    }

    private static List<String> names(List<FileMetadata> files) {
        return files.stream().map(FileMetadata::name).sorted().toList();
    }

    @Test
    void everyChannelIndexesAndSavesTheCleanedName() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("db").toString());
             FileIndex index = new FileIndex(db)) {
            index.setNameCleaner(new NameCleaner(NameCleaner.DEFAULT_RULE));
            ClientState client = new ClientState(InetAddress.getLoopbackAddress(), 4662, 1, System.currentTimeMillis(),
                    new AtomicLong());
            ConcurrentHashMap<String, ClientState> sources = new ConcurrentHashMap<>();
            sources.put(FileIndex.sourceKey(client), client);
            FileMetadata stored = index.addFile(new FileMetadata(hash(1), "[kad emule-qt.org] Song One.mp3", 10, "Audio",
                    List.of(), sources), FileSource.CLIENT_PUSH);
            assertEquals("Song One.mp3", stored.name());
            for (FileSource source : List.of(FileSource.KAD_SEARCH, FileSource.SERVER_SEARCH,
                    FileSource.SERVER_INDEX_UPDATE, FileSource.RELAY_FEED)) {
                int i = 2 + source.ordinal();
                index.addFiles(List.of(file(i, "[torrent emule-qt.org] Song " + i + ".mp3", 3)), source);
            }
            index.addFiles(List.of(file(100, "Song clean.mp3", 1)), FileSource.KAD_SEARCH);

            List<FileMetadata> found = index.searchComplex(SONG, 100);
            assertEquals(6, found.size());
            assertTrue(found.stream().noneMatch(f -> f.name().contains("emule-qt")), names(found).toString());
            assertEquals(List.of(), index.searchComplex(KAD, 100), "the words of the tag are not indexed");
            assertEquals(5, index.nameCleaner().cleanedAtIngestion());
            assertTrue(db.loadFiles().stream().noneMatch(f -> f.name().contains("emule-qt")), "saved cleaned");
        }
    }

    @Test
    void inRelayModeTheFeedGetsTheCleanedName() {
        FileIndex index = new FileIndex(null);
        index.setNameCleaner(new NameCleaner(NameCleaner.DEFAULT_RULE));
        List<FileMetadata> fed = new ArrayList<>();
        index.setRelayFeed(new FileIndex.RelayFeed() {
            public boolean relayMode() {
                return true;
            }

            public void offer(FileMetadata file) {
                fed.add(file);
            }
        });
        index.addFile(file(1, "[kad emule-qt.org] Song.mp3", 0), FileSource.CLIENT_PUSH);
        index.addFiles(List.of(file(2, "Other [web emule-qt.org].mp3", 0)), FileSource.KAD_SEARCH);
        assertEquals(List.of("Other.mp3", "Song.mp3"), names(fed));
    }

    /** A database and its index holding files named before the rule existed. */
    private void indexedBeforeTheRule(DatabaseManager db, int count) {
        List<FileMetadata> files = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            files.add(file(i, i % 3 == 0 ? "Song plain " + i + ".mp3"
                    : i % 3 == 1 ? "[kad emule-qt.org] Song " + i + ".mp3" : "Song " + i + " [web EMULE-QT.org].mp3", i));
        }
        db.saveFiles(files);
    }

    @Test
    void theJobRenamesTheIndexedFilesInTheIndexAndTheDatabase() throws Exception {
        String path = tempDir.resolve("job").toString();
        try (DatabaseManager db = new DatabaseManager(path)) {
            indexedBeforeTheRule(db, 30);
            try (FileIndex index = new FileIndex(db)) {
                NameCleaner cleaner = new NameCleaner(NameCleaner.DEFAULT_RULE);
                index.setNameCleaner(cleaner);
                assertEquals(20, index.searchComplex(KAD, 100).size() + index.searchComplex(
                        new SearchQuery.TermQuery("web", SearchQuery.ID_FILENAME), 100).size());

                // Windows of 7 files: several queries over the table
                NameCleaningJob job = new NameCleaningJob(db, index, cleaner, 7);
                assertEquals(NameCleaningJob.State.IDLE, job.status().state());
                assertTrue(job.start());
                assertTrue(job.awaitEnd(30_000));
                NameCleaningJob.Status status = job.status();
                assertEquals(NameCleaningJob.State.DONE, status.state(), status.lastError());
                assertEquals(30, status.scanned());
                assertEquals(20, status.renamed());
                assertTrue(status.finishedAt() >= status.startedAt() && status.startedAt() > 0);

                index.refresh();
                assertEquals(List.of(), index.searchComplex(KAD, 100), "the words of the tags are gone");
                List<FileMetadata> songs = index.searchComplex(SONG, 100);
                assertEquals(30, songs.size());
                FileMetadata one = songs.stream().filter(f -> f.hash().equals(hash(1))).findFirst().orElseThrow();
                assertEquals("Song 1.mp3", one.name());
                assertEquals(1, one.sourceCount(), "the source count stays");
                assertEquals(1_001L, one.size());
                assertEquals("mp3", one.tags().stream().filter(t -> t.name().equals(Tag.idName(SearchQuery.ID_MEDIA_CODEC)))
                        .findFirst().orElseThrow().value(), "the tags stay");
                assertEquals("Song 2.mp3", index.searchComplex(new SearchQuery.TermQuery("2", SearchQuery.ID_FILENAME), 100)
                        .stream().filter(f -> f.hash().equals(hash(2))).findFirst().orElseThrow().name());
                assertTrue(db.loadFiles().stream().noneMatch(f -> f.name().toLowerCase().contains("emule-qt")));
                assertEquals(30, db.loadFiles().size());

                // A second run has nothing left to rename
                assertTrue(job.start());
                assertTrue(job.awaitEnd(30_000));
                assertEquals(0, job.status().renamed());
                assertEquals(30, job.status().scanned());
            }
        }
    }

    @Test
    void theJobStopsByItsFlag() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("stop").toString())) {
            indexedBeforeTheRule(db, 300);
            FileIndex index = new FileIndex(db);
            NameCleaner cleaner = new NameCleaner(NameCleaner.DEFAULT_RULE);
            index.setNameCleaner(cleaner);
            // Every window waits until the stop is asked
            java.util.concurrent.CountDownLatch stopAsked = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
            DatabaseManager slow = new DatabaseManager(tempDir.resolve("stop").toString() + "-other") {
                @Override
                public NameWindow filesNamedLike(List<String> texts, String after, int window) {
                    reading.countDown();
                    try {
                        stopAsked.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return db.filesNamedLike(texts, after, window);
                }

                @Override
                public boolean renameFiles(java.util.Map<String, String> namesByHash) {
                    return db.renameFiles(namesByHash);
                }
            };
            try (slow) {
                NameCleaningJob job = new NameCleaningJob(slow, index, cleaner, 10);
                assertTrue(job.start());
                assertFalse(job.start(), "one run at a time");
                assertEquals(NameCleaningJob.State.RUNNING, job.status().state());
                assertTrue(reading.await(30, java.util.concurrent.TimeUnit.SECONDS));
                job.stop();
                stopAsked.countDown();
                assertTrue(job.awaitEnd(30_000));
                assertEquals(NameCleaningJob.State.STOPPED, job.status().state());
                assertEquals(10, job.status().scanned(), "the window under way ends, no other one starts");
            }
            index.close();
        }
    }

    @Test
    void theDatabaseReadsTheTableWindowAfterWindowAndMatchesWildcardsAsTheyAre() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("windows").toString())) {
            db.saveFiles(List.of(file(1, "[kad eMule-Qt.org] Song.mp3", 3), file(2, "plain.mp3", 0),
                    file(3, "Deal 100%_OFF.zip", 0), file(4, "Deal 100x off.zip", 0)));
            List<String> texts = List.of("emule-qt.org", "100%_off");
            DatabaseManager.NameWindow first = db.filesNamedLike(texts, "", 2);
            assertEquals(2, first.scanned());
            assertEquals(hash(2), first.last());
            assertEquals(List.of(hash(1)), first.matches().stream().map(DatabaseManager.NamedFile::hash).toList());
            DatabaseManager.NameWindow second = db.filesNamedLike(texts, first.last(), 2);
            assertEquals(List.of(hash(3)), second.matches().stream().map(DatabaseManager.NamedFile::hash).toList());
            assertNull(db.filesNamedLike(texts, second.last(), 2).last());

            assertTrue(db.renameFiles(java.util.Map.of(hash(1), "Song.mp3")));
            FileMetadata renamed = db.loadFiles().stream().filter(f -> f.hash().equals(hash(1))).findFirst().orElseThrow();
            assertEquals("Song.mp3", renamed.name());
            assertEquals(3, renamed.sourceCount());
            assertEquals(1, renamed.tags().stream().filter(t -> t.value().equals("mp3")).count(), "the tags stay");
        }
    }

    @Test
    void withoutDatabaseTheJobFails() throws Exception {
        NameCleaningJob job = new NameCleaningJob(null, new FileIndex(null), new NameCleaner("x"));
        assertTrue(job.start());
        assertTrue(job.awaitEnd(10_000));
        assertEquals(NameCleaningJob.State.FAILED, job.status().state());
        assertEquals("No database", job.status().lastError());
        assertEquals("failed", job.status().stateName());
    }
}
