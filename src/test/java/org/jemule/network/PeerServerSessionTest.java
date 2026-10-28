package org.jemule.network;

import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.SearchQuery;
import org.jemule.network.handler.SearchHandler;
import org.jemule.network.handler.SourceHandler;
import org.jemule.protocol.OpCode;
import org.jemule.network.Packet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * The sessions another eD2K server opens here (its relay and index update sessions) get the local answers only: a
 * search sent on to Kad, to the other servers or to the relay server would be made twice, and its keywords, offered
 * to the index update, would go back and forth between the two servers.
 */
class PeerServerSessionTest {

    private FakeContext context(boolean fromServer, FileIndex index, org.jemule.kad.KadSearchBridge kad,
                                ServerSearchBridge servers, ServerRelay relay) throws Exception {
        FakeContext ctx = loggedInContext("10.9.8.7", 50000, SourcesCompatibilityTest.CONFIG, new ClientRegistry(), index);
        ctx.setKadSearchBridge(kad);
        ctx.setServerSearchBridge(servers);
        ctx.setServerRelay(relay);
        ctx.setFromServer(fromServer);
        return ctx;
    }

    private static int localResults(ByteArrayOutputStream out) throws Exception {
        Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
        assertEquals(OpCode.SEARCH_RESULT.value, p.opcode());
        return readSearchAnswer(p.data()).results().size();
    }

    @Test
    void anotherServersSearchGetsTheLocalResultsOnly() throws Exception {
        FileIndex index = new FileIndex(null);
        index.addFile(new FileMetadata("0123456789ABCDEF0123456789ABCDEF", "linux distribution.iso", 1_000L, "Pro"));
        for (boolean fromServer : new boolean[]{true, false}) {
            org.jemule.kad.KadSearchBridge kad = mock(org.jemule.kad.KadSearchBridge.class);
            ServerSearchBridge servers = mock(ServerSearchBridge.class);
            ServerRelay relay = mock(ServerRelay.class);
            when(kad.forkSearch(any(SearchQuery.class))).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(servers.forkSearch(any(), any())).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(relay.forkSearch(any(), anyInt())).thenReturn(CompletableFuture.completedFuture(List.of()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new SearchHandler().handleSearch(context(fromServer, index, kad, servers, relay),
                    new SearchTree().keyword("linux").toBytes(), out);

            assertEquals(1, localResults(out));
            if (fromServer) {
                verify(kad, never()).forkSearch(any(SearchQuery.class));
                verify(servers, never()).forkSearch(any(), any());
                verify(relay, never()).forkSearch(any(), anyInt());
            } else {
                verify(kad).forkSearch(any(SearchQuery.class));
                verify(servers).forkSearch(any(), any());
                verify(relay).forkSearch(any(), anyInt());
            }
        }
    }

    @Test
    void anotherServersSourceRequestIsNotSentToTheRelayServer() throws Exception {
        FileIndex index = new FileIndex(null);
        for (boolean fromServer : new boolean[]{true, false}) {
            ServerRelay relay = mock(ServerRelay.class);
            when(relay.forkSources(any())).thenReturn(null);
            byte[] request = new byte[20]; // <hash 16><size 4>
            new SourceHandler().handleGetSources(context(fromServer, index, null, null, relay),
                    new Packet(Packet.PROTOCOL_ED2K, OpCode.GET_SOURCES.value, request), new ByteArrayOutputStream());
            verify(relay, fromServer ? never() : times(1)).forkSources(any());
        }
    }
}
