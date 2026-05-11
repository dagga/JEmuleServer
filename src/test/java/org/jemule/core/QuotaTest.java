package org.jemule.core;

import org.jemule.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuotaTest {

    @TempDir
    Path tempDir;

    @Test
    void testUserQuotaLogic() {
        // Config with small quota
        ServerConfig config = new ServerConfig(
                4661, 2*1024*1024, 300, 50, 100, 1000, 2, 200, tempDir.resolve("db").toString()
        );

        FileIndex index = new FileIndex(null); 
        
        ClientState state = new ClientState(InetAddress.getLoopbackAddress(), 1234, 1, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        
        // Simulating handlePublish logic
        int max = config.maxFilesPerUser();
        
        // Publish 1
        if (state.publishedFilesCount().get() < max) {
            index.addFile(new FileMetadata("H1", "N1", 10, "T"));
            state.publishedFilesCount().incrementAndGet();
        }
        assertEquals(1, state.publishedFilesCount().get());
        assertEquals(1, index.fileCount());

        // Publish 2
        if (state.publishedFilesCount().get() < max) {
            index.addFile(new FileMetadata("H2", "N2", 10, "T"));
            state.publishedFilesCount().incrementAndGet();
        }
        assertEquals(2, state.publishedFilesCount().get());
        assertEquals(2, index.fileCount());

        // Publish 3 - should fail quota
        if (state.publishedFilesCount().get() < max) {
            index.addFile(new FileMetadata("H3", "N3", 10, "T"));
            state.publishedFilesCount().incrementAndGet();
        }
        assertEquals(2, state.publishedFilesCount().get(), "Quota should prevent incrementing");
        assertEquals(2, index.fileCount(), "Quota should prevent adding to index");
    }
}
