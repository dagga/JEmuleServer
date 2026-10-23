package org.jemule.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The writes of a running server leave the file sparse: it is compacted without closing the database. */
class DatabaseOnlineCompactionTest {

    @TempDir
    Path tempDir;

    @Test
    void aSparseFileIsCompactedWhileTheDatabaseStaysOpen() throws Exception {
        int count = 20_000;
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("db").toString())) {
            List<FileMetadata> files = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                files.add(new FileMetadata(String.format("%032x", i), "shared file number " + i + " of the test.avi",
                        700_000_000L + i, "Video", List.of()));
            }
            for (int i = 0; i < count; i += 1000) {
                db.saveFiles(files.subList(i, i + 1000));
            }
            assertFalse(db.compactOnline(10_000), "not before the startup load is over");
            db.enableOnlineCompaction();
            assertFalse(db.compactOnline(10_000), "a dense file is left alone");

            // A running server keeps rewriting the source counts: the old versions leave the file sparse
            for (int round = 1; round <= 40; round++) {
                int sources = round;
                db.saveSourceCounts(files.stream().map(f -> f.withSourceCount(sources)).toList());
            }
            long sparse = db.fileSize();

            assertTrue(db.compactOnline(10_000), "the file is sparse");
            long compacted = db.fileSize();
            assertTrue(compacted < sparse * 0.7, "file " + sparse / 1024 + " KB -> " + compacted / 1024 + " KB");

            // Still open and complete
            db.saveFile(new FileMetadata(String.format("%032x", count), "after the compaction.avi", 1000, "Video", List.of()));
            List<FileMetadata> loaded = db.loadFiles();
            assertEquals(count + 1, loaded.size());
            assertEquals(40, loaded.stream().filter(f -> f.hash().equals(String.format("%032x", 7))).findFirst()
                    .orElseThrow().sourceCount());
        }
    }
}
