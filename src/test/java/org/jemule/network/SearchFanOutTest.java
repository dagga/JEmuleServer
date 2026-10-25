package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.SearchQuery;
import org.jemule.kad.KadSearchBridge;
import org.jemule.network.handler.SearchHandler;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.jemule.network.EmuleTestSupport.FakeContext;
import static org.jemule.network.EmuleTestSupport.SearchAnswer;
import static org.jemule.network.EmuleTestSupport.SearchTree;
import static org.jemule.network.EmuleTestSupport.loggedInContext;
import static org.jemule.network.EmuleTestSupport.readSearchAnswer;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A search of a connected client is answered from the local index and sent both to Kad and to the eD2K servers. */
class SearchFanOutTest {
    private static final String LOCAL = "11111111111111111111111111111111";
    private static final String FROM_KAD = "22222222222222222222222222222222";
    private static final String FROM_SERVERS = "33333333333333333333333333333333";

    private final FileIndex index = new FileIndex(null);
    private final KadSearchBridge kad = mock(KadSearchBridge.class);
    private final ServerSearchBridge servers = mock(ServerSearchBridge.class);

    private FakeContext client() throws Exception {
        FakeContext ctx = loggedInContext("10.1.1.1", 50000, ServerConfig.DEFAULT, new ClientRegistry(), index);
        ctx.setKadSearchBridge(kad);
        ctx.setServerSearchBridge(servers);
        return ctx;
    }

    private static SearchAnswer search(FakeContext ctx, byte[] tree) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SearchHandler().handleSearch(ctx, tree, out);
        Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
        assertEquals(OpCode.SEARCH_RESULT.value, p.opcode());
        return readSearchAnswer(p.data());
    }

    private static List<String> hashes(SearchAnswer answer) {
        return answer.results().stream().map(r -> r.hash().toUpperCase()).sorted().toList();
    }

    @Test
    void aClientSearchQueriesTheIndexKadAndTheServers() throws Exception {
        index.addFile(new FileMetadata(LOCAL, "ubuntu local.iso", 100, "Iso"));
        when(kad.forkSearch(any(SearchQuery.class))).thenReturn(CompletableFuture.completedFuture(
                List.of(FileMetadata.withoutSources(FROM_KAD, "ubuntu kad.iso", 200, "Iso", List.of()))));
        when(servers.forkSearch(any(SearchQuery.class), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(
                List.of(FileMetadata.withoutSources(FROM_SERVERS, "ubuntu servers.iso", 300, "Iso", List.of()))));
        byte[] tree = new SearchTree().keyword("ubuntu").toBytes();

        SearchAnswer answer = search(client(), tree);

        SearchQuery query = new SearchQuery.TermQuery("ubuntu", SearchQuery.ID_FILENAME);
        verify(kad, times(1)).forkSearch(eq(query));
        verify(servers, times(1)).forkSearch(eq(query), any(byte[].class));
        org.mockito.ArgumentCaptor<byte[]> sent = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(servers).forkSearch(any(SearchQuery.class), sent.capture());
        assertArrayEquals(tree, sent.getValue(), "the servers get the client's search tree as is");
        assertEquals(List.of(LOCAL, FROM_KAD, FROM_SERVERS), hashes(answer), "local, Kad and server results");
    }

    @Test
    void theServersAreQueriedEvenWhenKadFails() throws Exception {
        when(kad.forkSearch(any(SearchQuery.class))).thenThrow(new IllegalStateException("Kad down"));
        when(servers.forkSearch(any(SearchQuery.class), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(
                List.of(FileMetadata.withoutSources(FROM_SERVERS, "ubuntu servers.iso", 300, "Iso", List.of()))));

        SearchAnswer answer = search(client(), new SearchTree().keyword("ubuntu").toBytes());

        verify(servers, times(1)).forkSearch(any(SearchQuery.class), any(byte[].class));
        assertEquals(List.of(FROM_SERVERS), hashes(answer));
    }

    @Test
    void aChildAbuseSearchGetsNoResultAndIsSentNeitherToKadNorToTheServers() throws Exception {
        index.addFile(new FileMetadata(LOCAL, "stamp collection.avi", 100, "Video"));

        SearchAnswer answer = search(client(), new SearchTree().and().keyword("pthc").keyword("collection").toBytes());

        assertEquals(List.of(), hashes(answer));
        org.mockito.Mockito.verifyNoInteractions(kad, servers);
        assertEquals(1, index.childAbuseQueryCount());
    }

    @Test
    void aFileMatchingTheFilterNeverReachesTheClientWhateverItsChannel() throws Exception {
        // Indexed before the keywords were reloaded, or returned by a channel that did not screen it
        index.addFile(new FileMetadata(LOCAL, "collection 1 pthc.avi", 100, "Video"));
        index.addFile(new FileMetadata("44444444444444444444444444444444", "collection 2.avi", 100, "Video"));
        when(kad.forkSearch(any(SearchQuery.class))).thenReturn(CompletableFuture.completedFuture(
                List.of(FileMetadata.withoutSources(FROM_KAD, "collection 3 pthc.avi", 200, "Video", List.of()))));
        when(servers.forkSearch(any(SearchQuery.class), any(byte[].class))).thenReturn(CompletableFuture.completedFuture(
                List.of(FileMetadata.withoutSources(FROM_SERVERS, "collection 4.avi", 300, "Video", List.of()))));

        SearchAnswer answer = search(client(), new SearchTree().keyword("collection").toBytes());

        assertEquals(List.of(FROM_SERVERS, "44444444444444444444444444444444"), hashes(answer),
                "neither the local file nor the Kad file matching the filter");
        assertEquals(0, index.childAbuseQueryCount(), "the search itself is clean");
    }
}
