package org.jemule.core;

import org.jemule.security.ChildAbuseFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
        }
    }
}
