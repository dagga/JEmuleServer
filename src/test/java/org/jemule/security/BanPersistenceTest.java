package org.jemule.security;

import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.network.AdminInterface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A name ban from the admin console survives a restart, removes the files and applies with the heuristics disabled. */
class BanPersistenceTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void adminBanIsStoredAndRemovesTheFiles() throws Exception {
        String dbPath = tempDir.resolve("db").toString();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FileIndex index = new FileIndex(db);
            index.addFile(new FileMetadata(HASH, "Some Bad File.avi", 1000, "Video"));
            index.addFile(new FileMetadata("ffffffffffffffffffffffffffffffff", "some good file.avi", 1000, "Video"));
            FakeFileDetector detector = new FakeFileDetector();
            detector.setDatabaseManager(db);

            new AdminInterface(null, null, index, new IPFilter(), detector).handleCommand("ban   some BAD file.avi ");

            assertEquals(1, index.fileCount());
            assertTrue(index.search("bad", 10).isEmpty());
            assertEquals(1, db.loadFiles().size());
        }

        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FakeFileDetector detector = new FakeFileDetector();
            detector.setEnabled(false);
            detector.setDatabaseManager(db);
            db.loadBannedNames().forEach(n -> detector.addBannedName(n.name()));
            assertEquals(List.of("some bad file.avi"), detector.bannedNames());
            assertTrue(detector.isFake("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", " SOME bad FILE.avi", 1000),
                    "the ban survives a restart, any hash and case, heuristics disabled");
            assertFalse(detector.isFake(HASH, "renamed.avi", 1000), "the hash is not banned");
            assertFalse(detector.isFake("ffffffffffffffffffffffffffffffff", "movie.mp4.exe", 1000));

            new AdminInterface(null, null, new FileIndex(db), new IPFilter(), detector).handleCommand("unban Some Bad File.avi");
            assertTrue(detector.bannedNames().isEmpty());
            assertTrue(db.loadBannedNames().isEmpty());
        }
    }

    @Test
    void bannedNamesAreListedTheMostRecentFirst() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("db").toString())) {
            db.addBannedName("first.avi", "test");
            Thread.sleep(5);
            db.addBannedName("second.avi", null);
            var names = db.loadBannedNames();
            assertEquals(List.of("second.avi", "first.avi"), names.stream().map(DatabaseManager.BannedName::name).toList());
            assertEquals("test", names.get(1).reason());
        }
    }
}
