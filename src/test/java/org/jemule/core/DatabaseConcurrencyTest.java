package org.jemule.core;

import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The single JDBC connection is shared by every client thread: concurrent saves must all be stored complete. */
class DatabaseConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentSavesKeepEveryFileAndItsTags() throws Exception {
        int files = 200;
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("db").toString());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < files; i++) {
                String hash = String.format("%032x", i);
                tasks.add(executor.submit(() -> db.saveFile(new FileMetadata(hash, "file " + hash + ".avi", 1000, "Video",
                        List.of(new Tag(Tag.TYPE_STRING, "a", "x"), new Tag(Tag.TYPE_INTEGER, "b", 1))))));
                tasks.add(executor.submit(() -> db.addBannedHash(hash, "test")));
            }
            for (Future<?> t : tasks) t.get();

            List<FileMetadata> loaded = db.loadFiles();
            assertEquals(files, loaded.size());
            loaded.forEach(m -> assertEquals(2, m.tags().size(), m.hash()));
            assertEquals(files, db.loadBannedHashes().size());
        }
    }

    @Test
    void batchSavePersistsFilesAndTheirTagsInOneCall() throws Exception {
        String firstHash = "00000000000000000000000000000001";
        String secondHash = "00000000000000000000000000000002";
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("batch-db").toString())) {
            FileIndex index = new FileIndex(db);
            index.addFiles(List.of(
                    new FileMetadata(firstHash, "first.avi", 1000, "Video",
                            List.of(new Tag(Tag.TYPE_STRING, "source", "kad"))),
                    new FileMetadata(secondHash, "second.avi", 2000, "Video",
                            List.of(new Tag(Tag.TYPE_INTEGER, "sources", 3)))
            ));

            List<FileMetadata> loaded = db.loadFiles();
            assertTrue(index.contains(firstHash));
            assertTrue(index.contains(secondHash));
            assertEquals(2, loaded.size());
            assertEquals(Map.of(firstHash, "first.avi", secondHash, "second.avi"),
                    loaded.stream().collect(java.util.stream.Collectors.toMap(FileMetadata::hash, FileMetadata::name)));
            assertEquals(1, loaded.stream().filter(file -> file.hash().equals(firstHash)).findFirst().orElseThrow().tags().size());
            assertEquals(1, loaded.stream().filter(file -> file.hash().equals(secondHash)).findFirst().orElseThrow().tags().size());
        }
    }
}
