package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileIngestionStatsTest {

    @Test
    void countsOnlyNewHashesPerIngestionChannel() {
        FileIndex index = new FileIndex(null);

        index.addFile(new FileMetadata("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "client.avi", 100, "Video"),
                FileSource.CLIENT_PUSH);
        // Same hash from another channel is not a new file
        index.addFile(new FileMetadata("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "client.avi", 100, "Video"),
                FileSource.KAD_SEARCH);
        index.addFile(new FileMetadata("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "kad.avi", 100, "Video"),
                FileSource.KAD_SEARCH);
        index.addFiles(List.of(
                new FileMetadata("cccccccccccccccccccccccccccccccc", "server1.avi", 100, "Video"),
                new FileMetadata("dddddddddddddddddddddddddddddddd", "server2.avi", 100, "Video")),
                FileSource.SERVER_SEARCH);
        // A file loaded from the database (no source) re-published is not a new hash
        index.addFile(FileMetadata.withoutSources("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "db.avi", 100, "Video", List.of()),
                FileSource.UNKNOWN);
        index.addFile(new FileMetadata("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "db.avi", 100, "Video"),
                FileSource.CLIENT_PUSH);

        assertEquals(1, index.newFileCount(FileSource.CLIENT_PUSH));
        assertEquals(1, index.newFileCount(FileSource.KAD_SEARCH));
        assertEquals(2, index.newFileCount(FileSource.SERVER_SEARCH));
        assertEquals(1, index.newFileCount(FileSource.UNKNOWN));
        assertEquals(5, index.totalNewFiles());
        assertEquals(5, index.fileCount());
    }
}
