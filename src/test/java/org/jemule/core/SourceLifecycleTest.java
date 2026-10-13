package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** C6: sources disappear when their client disconnects; the files stay searchable without source. */
class SourceLifecycleTest {

    private static ClientState client(String ip) throws Exception {
        ClientState st = new ClientState(InetAddress.getByName(ip), 50000, 1, System.currentTimeMillis(), new AtomicLong());
        st.setListenPort(4662);
        return st;
    }

    private static void publish(FileIndex index, ClientState client, String hash, String name) {
        FileMetadata meta = new FileMetadata(hash, name, 1000, "Video");
        meta.sources().put(FileIndex.sourceKey(client), client);
        index.addFile(meta);
        client.publishedHashes().add(hash);
    }

    @Test
    void disconnectRemovesSourcesButKeepsFiles() throws Exception {
        FileIndex index = new FileIndex(null);
        ClientState a = client("10.0.0.1");
        ClientState b = client("10.0.0.2");
        publish(index, a, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "shared by both.avi");
        publish(index, b, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "shared by both.avi");
        publish(index, a, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "only on a.avi");

        index.removeSource(a);

        assertEquals(2, index.fileCount());
        assertEquals(1, index.getSources("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null, 10).size());
        assertSame(b, index.getSources("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null, 10).getFirst());
        assertTrue(index.getSources("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", null, 10).isEmpty());
        assertEquals(1, index.search("only", 10).size(), "a file without source must still be found");
        assertEquals(1, index.searchComplex(new SearchQueryBuilder().term("only", SearchQuery.ID_FILENAME), 10).size());
        assertEquals(1, index.search("both", 10).size());
    }

    @Test
    void protocolSearchReturnsFilesWithoutSource() {
        FileIndex index = new FileIndex(null);
        index.addFile(new FileMetadata("cccccccccccccccccccccccccccccccc", "offline file.avi", 1000, "Video")); // e.g. loaded from DB
        assertEquals(1, index.searchComplex(m -> true, 10).size());
    }
}
