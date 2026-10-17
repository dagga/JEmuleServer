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

    @Test
    void filesKnownWithoutSourceShareNoMapAndGetOneWhenPublished() throws Exception {
        FileIndex index = new FileIndex(null);
        String hash = "dddddddddddddddddddddddddddddddd";
        FileMetadata offline = FileMetadata.withoutSources(hash, "loaded from db.avi", 1000, "Video", java.util.List.of());
        index.addFile(offline);
        index.addFile(FileMetadata.withoutSources("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "found on kad.avi", 1000, "Video", java.util.List.of()));
        assertThrows(UnsupportedOperationException.class, () -> offline.sources().put("1", client("10.0.0.9")),
                "the shared map of the files without source cannot be filled");

        // A client publishes it: the entry is replaced by the published file and its own map
        ClientState a = client("10.0.0.1");
        publish(index, a, hash, "loaded from db.avi");
        assertSame(a, index.getSources(hash, null, 10).getFirst());
        assertTrue(offline.sources().isEmpty());
        assertTrue(index.getSources("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", null, 10).isEmpty());

        // Once the client is gone, the file can again be replaced by a file without source (e.g. from Kad)
        index.removeSource(a);
        index.addFile(FileMetadata.withoutSources(hash, "loaded from db.avi", 1000, "Video", java.util.List.of()));
        ClientState b = client("10.0.0.2");
        publish(index, b, hash, "loaded from db.avi");
        assertSame(b, index.getSources(hash, null, 10).getFirst());
        assertEquals(1, index.searchComplex(new SearchQueryBuilder().term("loaded", SearchQuery.ID_FILENAME), 10).size());
    }
}
