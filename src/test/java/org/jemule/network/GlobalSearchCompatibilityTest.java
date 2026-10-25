package org.jemule.network;

import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.network.handler.PublishHandler;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Global UDP searches as sent by CSearchResultsWnd (eMule 0.50a) and decoded like CUDPSocket::ProcessPacket. */
class GlobalSearchCompatibilityTest {

    Server server;

    @BeforeEach
    void setup() throws Exception {
        server = new Server(SourcesCompatibilityTest.CONFIG, new ClientFactory());
        FakeContext pub = loggedInContext("10.1.2.3", 50000, SourcesCompatibilityTest.CONFIG, new ClientRegistry(), server.getFileIndex());
        new PublishHandler().handlePublish(pub, OpCode.OFFER_FILES, offerFiles(
                completeFile(hash("11111111111111111111111111111111"), "linux distribution.iso", 4_000_000_000L),
                completeFile(hash("22222222222222222222222222222222"), "linux distribution dvd.iso", 8_000_000_000L),
                completeFile(hash("33333333333333333333333333333333"), "other thing.avi", 1_000_000L)), new ByteArrayOutputStream());
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    List<SearchResult> udpSearch(byte[] request) throws Exception {
        SourcesCompatibilityTest.CapturingDatagramSocket cds = new SourcesCompatibilityTest.CapturingDatagramSocket();
        var m = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, cds, new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), 40000));

        List<SearchResult> results = new ArrayList<>();
        for (DatagramPacket dp : cds.sent) {
            ByteBuffer buf = ByteBuffer.wrap(dp.getData(), 0, dp.getLength()).order(ByteOrder.LITTLE_ENDIAN);
            while (buf.remaining() >= 2) {
                assertEquals(Packet.PROTOCOL_ED2K, buf.get());
                assertEquals((byte) 0x99, buf.get(), "OP_GLOBSEARCHRES");
                results.add(readSearchResult(buf));
            }
            assertFalse(buf.hasRemaining());
        }
        return results;
    }

    static byte[] withHeader(int opcode, byte[] body) {
        return ByteBuffer.allocate(2 + body.length).put(Packet.PROTOCOL_ED2K).put((byte) opcode).put(body).array();
    }

    @Test
    void globSearchReq3WithLargeFileSupport() throws Exception {
        // <tagcount 4><CT_SERVER_UDPSEARCH_FLAGS (0x0E) = SRVCAP_UDP_NEWTAGS_LARGEFILES, WriteNewEd2kTag><tree>
        byte[] tags = new NewTags().num(0x0E, 0x01).toBytes();
        byte[] tree = new SearchTree().keyword("linux").toBytes();
        byte[] body = ByteBuffer.allocate(tags.length + tree.length).put(tags).put(tree).array();
        List<SearchResult> res = udpSearch(withHeader(0x90, body));
        assertEquals(2, res.size());
        assertTrue(res.stream().anyMatch(r -> r.tag(Tag.NAME_FILESIZE_HI) != null), "large file returned with FT_FILESIZE_HI");
    }

    @Test
    void oldGlobSearchReqExcludesLargeFiles() throws Exception {
        List<SearchResult> res = udpSearch(withHeader(0x98, new SearchTree().keyword("linux").toBytes()));
        assertEquals(1, res.size());
        assertEquals("linux distribution.iso", res.getFirst().tag(Tag.NAME_FILENAME));
        assertEquals(1, ((Number) res.getFirst().tag(Tag.NAME_SOURCES)).intValue());
    }

    @Test
    void globalSearchIsAlsoSearchedOnKadButNeverForwardedToOtherServers() throws Exception {
        org.jemule.kad.KadSearchBridge kad = mock(org.jemule.kad.KadSearchBridge.class);
        server.setKadSearchBridge(kad);

        byte[] request = withHeader(0x98, new SearchTree().keyword("linux").toBytes());
        assertEquals(1, udpSearch(request).size(), "the requester still gets the local answer");
        verify(kad).forkSearch(new org.jemule.core.SearchQuery.TermQuery("linux", org.jemule.core.SearchQuery.ID_FILENAME));
        assertEquals(0, server.getServerSearchBridge().getSearchesForked(),
                "another server forwarding global searches could send it back: no loop");

        udpSearch(request);
        verify(kad, times(1)).forkSearch(any(org.jemule.core.SearchQuery.class));
        assertEquals(2, server.getGlobalSearchForwarder().getReceived());
        assertEquals(1, server.getGlobalSearchForwarder().getForwarded());
    }

    @Test
    void aChildAbuseGlobalSearchGetsNoAnswerAndIsNotSearchedOnKad() throws Exception {
        org.jemule.kad.KadSearchBridge kad = mock(org.jemule.kad.KadSearchBridge.class);
        server.setKadSearchBridge(kad);

        assertTrue(udpSearch(withHeader(0x98, new SearchTree().keyword("pthc").toBytes())).isEmpty());
        assertTrue(udpSearch(withHeader(0x98, new SearchTree().and().keyword("linux").keyword("pthc").toBytes())).isEmpty(),
                "one term is enough, whatever the others");
        org.mockito.Mockito.verifyNoInteractions(kad);
        assertEquals(2, server.getFileIndex().childAbuseQueryCount());
    }

    @Test
    void globSearchReq2() throws Exception {
        assertEquals(1, udpSearch(withHeader(0x92, new SearchTree().keyword("other").toBytes())).size());
    }
}
