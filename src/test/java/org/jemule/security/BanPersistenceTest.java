package org.jemule.security;

import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.network.AdminInterface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A ban from the admin console survives a restart, removes the file and applies with the heuristics disabled. */
class BanPersistenceTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void adminBanIsStoredAndRemovesTheFile() throws Exception {
        String dbPath = tempDir.resolve("db").toString();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FileIndex index = new FileIndex(db);
            index.addFile(new FileMetadata(HASH, "some file.avi", 1000, "Video"));
            FakeFileDetector detector = new FakeFileDetector();
            detector.setDatabaseManager(db);

            new AdminInterface(null, null, index, new IPFilter(), detector).handleCommand("ban " + HASH + " bad file");

            assertEquals(0, index.fileCount());
            assertTrue(index.search("some", 10).isEmpty());
            assertTrue(db.loadFiles().isEmpty());
        }

        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FakeFileDetector detector = new FakeFileDetector();
            detector.setEnabled(false);
            db.loadBannedHashes().forEach(detector::addBannedHash);
            assertTrue(detector.isFake(HASH, "renamed.avi", 1000), "the ban survives a restart, heuristics disabled");
            assertFalse(detector.isFake("ffffffffffffffffffffffffffffffff", "movie.mp4.exe", 1000));
        }
    }

    @Test
    void recentBansReturnsOnlyTheNewestRows() throws Exception {
        String dbPath = tempDir.resolve("db").toString();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            for (int i = 1; i <= 5; i++) {
                db.addBannedHash(String.format("%032d", i), "test");
            }
            var recent = db.loadRecentBannedHashes(3);
            assertEquals(3, recent.size());
            assertTrue(recent.contains(String.format("%032d", 3)));
            assertTrue(recent.contains(String.format("%032d", 4)));
            assertTrue(recent.contains(String.format("%032d", 5)));
        }
    }
}
