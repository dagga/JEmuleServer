package org.jemule.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The writes of a running server leave the file sparse: it is compacted without restarting the server. */
class DatabaseOnlineCompactionTest {
    private static final int FILES = 20_000;

    @TempDir
    Path tempDir;

    @Test
    void aSparseFileIsCompactedAndTheDatabaseStaysUsable() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("db").toString())) {
            List<FileMetadata> files = new ArrayList<>();
            for (int i = 0; i < FILES; i++) {
                files.add(new FileMetadata(String.format("%032x", i), "shared file number " + i + " of the test.avi",
                        700_000_000L + i, "Video", List.of()));
            }
            for (int i = 0; i < FILES; i += 1000) {
                db.saveFiles(files.subList(i, i + 1000));
            }
            assertFalse(db.compactOnline(), "a dense file is left alone");

            // A running server keeps rewriting the source counts: the old versions leave the file sparse
            for (int round = 1; round <= 40; round++) {
                int sources = round;
                db.saveSourceCounts(files.stream().map(f -> f.withSourceCount(sources)).toList());
            }
            long sparse = db.fileSize();

            assertTrue(db.compactOnline(), "the file is sparse");
            long compacted = db.fileSize();
            assertTrue(compacted < sparse * 0.3, "file " + sparse / 1024 + " KB -> " + compacted / 1024 + " KB");
            assertFalse(db.compactOnline(), "nothing left to compact");

            // Reopened, complete and writable
            db.saveFile(new FileMetadata(String.format("%032x", FILES), "after the compaction.avi", 1000, "Video", List.of()));
            List<FileMetadata> loaded = db.loadFiles();
            assertEquals(FILES + 1, loaded.size());
            assertEquals(40, loaded.stream().filter(f -> f.hash().equals(String.format("%032x", 7))).findFirst()
                    .orElseThrow().sourceCount());
        }
    }
}
