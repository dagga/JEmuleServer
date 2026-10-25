package org.jemule.core;

import org.jemule.security.ChildAbuseFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChildAbuseIndexTest {

    private static final String ABUSE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String LEGIT_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    Path tempDir;

    @Test
    void filesOfTheDatabaseArePurgedAtStartup() throws Exception {
        String dbPath = tempDir.resolve("db").toString();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            // stored by an older version, without the filter
            db.saveFile(new FileMetadata(ABUSE_HASH, "BOYFUCK - 6Yo boy stripped.avi", 1000, "Video"));
            db.saveFile(new FileMetadata(LEGIT_HASH, "holiday video.avi", 1000, "Video"));
        }

        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults());
            assertEquals(1, index.fileCount());
            assertTrue(index.search("boy", 10).isEmpty());
            assertEquals(1, db.loadFiles().size(), "the file must be deleted from the database");
            assertTrue(index.isChildAbuse(ABUSE_HASH, "BOYFUCK - 6Yo boy stripped.avi"));
            assertFalse(index.isChildAbuse(LEGIT_HASH, "holiday video.avi"));
            assertEquals(1, index.childAbuseRejectedCount());
            // Its name is recorded among the banned names, once, for the export; not the clean one
            assertTrue(index.isChildAbuse("cccccccccccccccccccccccccccccccc", "  BOYFUCK - 6Yo boy stripped.AVI"));
            List<DatabaseManager.BannedName> names = db.loadBannedNames();
            assertEquals(List.of("boyfuck - 6yo boy stripped.avi"), names.stream().map(DatabaseManager.BannedName::name).toList());
            assertEquals(ChildAbuseFilter.BAN_REASON, names.get(0).reason());
            assertTrue(db.loadBannedNames(ChildAbuseFilter.BAN_REASON).isEmpty(), "not loaded into memory at startup");
        }
    }

    @Test
    void anAdminBanKeepsItsReasonWhenTheFilterMeetsTheName() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("db2").toString())) {
            db.addBannedName("pthc collection.avi", "Admin console");
            FileIndex index = new FileIndex(db, null, ChildAbuseFilter.defaults());
            assertTrue(index.isChildAbuse(ABUSE_HASH, "pthc collection.avi"));
            assertEquals("Admin console", db.loadBannedNames().get(0).reason());
            assertEquals(1, db.loadBannedNames(ChildAbuseFilter.BAN_REASON).size());
            assertEquals(List.of("pthc collection.avi"), db.bannedNamesAfter(null, 10));
            assertEquals(1, db.countBannedNames());
        }
    }
}
