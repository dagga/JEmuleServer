package org.jemule.core;

import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void testFilePersistence() throws Exception {
        String dbPath = tempDir.resolve("testdb").toString();
        
        FileMetadata file = new FileMetadata(
            "1234567890ABCDEF1234567890ABCDEF",
            "test_file.txt",
            1024,
            "Text",
            List.of(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_CODEC), "xvid"))
        );

        // Save to DB
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FileIndex index = new FileIndex(db);
            index.addFile(file);
            assertEquals(1, index.fileCount());
        }

        // Load from DB in a new instance
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FileIndex index = new FileIndex(db);
            assertEquals(1, index.fileCount(), "File count should be 1 after loading from DB");
            List<FileMetadata> results = index.search("test", 10);
            assertEquals(1, results.size(), "Search should return 1 result");
            FileMetadata loaded = results.getFirst();
            assertEquals(file.hash(), loaded.hash());
            assertEquals(file.name(), loaded.name());
            assertEquals(file.size(), loaded.size());
            assertEquals(1, loaded.tags().size());
            assertEquals("xvid", loaded.tags().getFirst().value());
        }
    }

    @Test
    void testStatsPersistence() throws Exception {
        String dbPath = tempDir.resolve("testdb_stats").toString();
        
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            db.setStat("total_files", 100);
            db.setStat("total_users", 50);
        }

        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            assertEquals(100, db.getStat("total_files", 0));
            assertEquals(50, db.getStat("total_users", 0));
            assertEquals(0, db.getStat("unknown", 0));
        }
    }

    @Test
    void filesPublishedOneByOneShareATransactionUntilADeletion() throws Exception {
        String dbPath = tempDir.resolve("coalesced").toString();
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FileIndex index = new FileIndex(db);
            index.setPersistenceExecutor(queued::add);
            for (int i = 0; i < 3; i++) {
                index.addFile(new FileMetadata(String.format("%032X", i), "coalesced " + i + ".avi", 2048, "Video"));
            }
            assertEquals(1, queued.size(), "one write for the three files");

            index.removeFile(String.format("%032X", 1));
            index.addFile(new FileMetadata(String.format("%032X", 1), "coalesced again.avi", 4096, "Video"));
            index.addFile(new FileMetadata(String.format("%032X", 7), "coalesced 7.avi", 2048, "Video"));
            assertEquals(3, queued.size(), "the deletion closes the first write; the files after it share another");

            queued.forEach(Runnable::run);
            java.util.Map<String, FileMetadata> saved = new java.util.HashMap<>();
            db.loadFiles().forEach(f -> saved.put(f.hash(), f));
            assertEquals(4, saved.size());
            assertEquals("coalesced again.avi", saved.get(String.format("%032X", 1)).name(), "written after its deletion");
        }
    }

    @Test
    void databaseWritesRunOnThePersistenceExecutorInOrder() throws Exception {
        String dbPath = tempDir.resolve("asyncdb").toString();
        FileMetadata file = new FileMetadata("ABCDEF1234567890ABCDEF1234567890", "queued file.avi", 2048, "Video");
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FileIndex index = new FileIndex(db);
            index.setPersistenceExecutor(queued::add);

            index.addFile(file);
            assertEquals(1, index.search("queued", 10).size(), "indexed in memory at once");
            assertTrue(db.loadFiles().isEmpty(), "the caller did not write to the database");
            index.removeFile(file.hash());
            assertEquals(2, queued.size(), "the deletion is queued after the write");

            queued.forEach(Runnable::run);
            assertTrue(db.loadFiles().isEmpty(), "write then delete, in order");
            index.addFile(file);
            queued.get(2).run();
            assertEquals(1, db.loadFiles().size());
        }
    }

    @Test
    void aHashAlreadyStoredOnlyHasItsHigherSourceCountWritten() throws Exception {
        String hash = "0123456789ABCDEF0123456789ABCDEF";
        List<Tag> tags = List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 5),
                new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_CODEC), "xvid"));
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("cachedb").toString())) {
            new FileIndex(db).addFile(new FileMetadata(hash, "known file.avi", 4096, "Video", tags), FileSource.KAD_SEARCH);

            // Reloaded from the database, as after a restart
            FileIndex index = new FileIndex(db);
            assertEquals(5, index.search("known", 10).getFirst().sourceCount());
            java.util.List<Runnable> queued = new java.util.ArrayList<>();
            index.setPersistenceExecutor(queued::add);

            index.addFile(new FileMetadata(hash, "known file.avi", 4096, "Video", tags), FileSource.KAD_SEARCH);
            index.addFile(new FileMetadata(hash, "other name.avi", 4096, "Video",
                    List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 3))), FileSource.SERVER_SEARCH);
            assertTrue(queued.isEmpty(), "a known hash found again by searches, without more sources, is not written");

            ClientState client = new ClientState(java.net.InetAddress.getByName("10.0.0.1"), 4662, 1,
                    System.currentTimeMillis(), new java.util.concurrent.atomic.AtomicLong());
            FileMetadata published = new FileMetadata(hash, "known file.avi", 4096, "Video", tags);
            published.sources().put(FileIndex.sourceKey(client), client);
            index.addFile(published, FileSource.CLIENT_PUSH);
            assertTrue(queued.isEmpty(), "a client publishing a known hash is not written either");
            assertEquals(1, index.search("known", 10).getFirst().sources().size(), "but becomes its source");

            index.addFiles(List.of(new FileMetadata(hash, "other name.avi", 4096, "Video",
                    List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 9)))), FileSource.SERVER_SEARCH);
            assertEquals(1, queued.size(), "a higher source count is written");
            queued.forEach(Runnable::run);

            FileMetadata stored = db.loadFiles().getFirst();
            assertEquals("known file.avi", stored.name(), "only the count was written");
            assertEquals(9, stored.sourceCount());
            assertEquals(2, stored.tags().size(), "the codec and one source count");

            queued.clear();
            index.addFile(new FileMetadata("FEDCBA9876543210FEDCBA9876543210", "new file.avi", 10, "Video"),
                    FileSource.KAD_SEARCH);
            assertEquals(1, queued.size(), "an unknown hash is written");
        }
    }

    @Test
    void theSourceCountOfAnUnsavedFileIsNotWritten() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("unsaveddb").toString())) {
            db.saveSourceCounts(List.of(new FileMetadata("0123456789ABCDEF0123456789ABCDEF", "x", 1, "Video",
                    List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 9)))));
            assertTrue(db.loadFiles().isEmpty());
        }
    }

    @Test
    void theIndexKeepsOnlyTheTagsSearchesUse() throws Exception {
        String codec = Tag.idName(SearchQuery.ID_MEDIA_CODEC);
        FileMetadata received = new FileMetadata("0123456789ABCDEF0123456789ABCDEF", "movie.avi", 4096, "Video", List.of(
                new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, "movie.avi"),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, 4096),
                new Tag(Tag.TYPE_STRING, Tag.NAME_FILETYPE, "Video"),
                new Tag(Tag.TYPE_STRING, Tag.NAME_FILEFORMAT, "avi"),
                new Tag(Tag.TYPE_INTEGER, new String(Tag.NAME_SOURCES.toCharArray()), 7),
                new Tag(Tag.TYPE_STRING, new String(codec.toCharArray()), "xvid")));
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("slimdb").toString())) {
            FileIndex index = new FileIndex(db);
            FileMetadata stored = index.addFile(received);
            assertEquals(List.of(Tag.NAME_SOURCES, codec), stored.tags().stream().map(Tag::name).toList());
            stored.tags().forEach(tag -> assertSame(Tag.idName(tag.name().charAt(0)), tag.name(), "shared name"));
            assertEquals(7, stored.sourceCount());
            assertEquals(1, index.searchComplex(new SearchQuery.TermQuery("xvid", SearchQuery.ID_MEDIA_CODEC), 10).size());
            assertEquals(2, db.loadFiles().getFirst().tags().size(), "only the codec and the source count are stored");
        }
    }
}
