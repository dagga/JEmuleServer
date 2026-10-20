package org.jemule.core;

import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            List.of(new Tag(Tag.TYPE_STRING, "Description", "A test file"))
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
            assertEquals("A test file", loaded.tags().getFirst().value());
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
}
