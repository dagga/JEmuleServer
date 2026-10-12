package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.network.handler.PublishHandler;
import org.jemule.network.handler.SourceHandler;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source requests and answers, decoded the way eMule 0.50a does it
 * (CDownloadQueue::ProcessLocalRequests, CServerSocket::ProcessPacket, CPartFile::AddSources, CUDPSocket::ProcessPacket).
 */
class SourcesCompatibilityTest {

    static final String HASH_A = "0123456789abcdef0123456789abcdef";
    static final String HASH_B = "fedcba9876543210fedcba9876543210";

    static final ServerConfig CONFIG = new ServerConfig(
            14663, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 200,
            null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1");

    static class CapturingDatagramSocket extends java.net.DatagramSocket {
        final List<DatagramPacket> sent = new ArrayList<>();
        CapturingDatagramSocket() throws Exception { super(); }
        @Override public void send(DatagramPacket p) {
            sent.add(new DatagramPacket(java.util.Arrays.copyOf(p.getData(), p.getLength()), p.getLength(), p.getAddress(), p.getPort()));
        }
    }

    ClientRegistry registry;
    FileIndex index;
    FakeContext publisher;

    @BeforeEach
    void setup() throws Exception {
        registry = new ClientRegistry();
        index = new FileIndex(null);
        publisher = loggedInContext("10.1.2.3", 50000, CONFIG, registry, index);
        new PublishHandler().handlePublish(publisher, OpCode.OFFER_FILES,
                offerFiles(completeFile(hash(HASH_A), "file a.avi", 1234567)), new ByteArrayOutputStream());
    }

    /** Mirrors CPartFile::AddSources for OP_FOUNDSOURCES: returns "ip:port" strings. */
    static List<String> readSourceBlock(ByteBuffer buf, String expectedHash) {
        byte[] h = new byte[16];
        buf.get(h);
        assertEquals(expectedHash, java.util.HexFormat.of().formatHex(h));
        int count = Byte.toUnsignedInt(buf.get());
        List<String> res = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int id = buf.getInt();
            int port = Short.toUnsignedInt(buf.getShort());
            res.add((id & 0xFF) + "." + ((id >> 8) & 0xFF) + "." + ((id >> 16) & 0xFF) + "." + ((id >>> 24) & 0xFF) + ":" + port);
        }
        return res;
    }

    String expectedSource() {
        return "10.1.2.3:" + LISTEN_PORT; // the listen port from the login, not the socket port (50000)
    }

    @Test
    void tcpFoundSourcesIsLittleEndian() throws Exception {
        FakeContext requester = loggedInContext("10.9.9.9", 50001, CONFIG, registry, index);
        ByteBuffer req = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put(hash(HASH_A)).putInt(1234567);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SourceHandler().handleGetSources(requester, new Packet(Packet.PROTOCOL_ED2K, OpCode.GET_SOURCES.value, req.array()), out);

        Packet resp = Packet.read(new ByteArrayInputStream(out.toByteArray()), 65536);
        assertEquals(Packet.PROTOCOL_ED2K, resp.protocol());
        assertEquals(OpCode.FOUND_SOURCES.value, resp.opcode());
        ByteBuffer buf = ByteBuffer.wrap(resp.data()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(List.of(expectedSource()), readSourceBlock(buf, HASH_A));
        assertFalse(buf.hasRemaining());
    }

    @Test
    void udpGlobGetSourcesAnswersEveryRequestedHash() throws Exception {
        Server server = new Server(CONFIG, new ClientFactory());
        FakeContext pub = loggedInContext("10.1.2.3", 50000, CONFIG, new ClientRegistry(), server.getFileIndex());
        new PublishHandler().handlePublish(pub, OpCode.OFFER_FILES, offerFiles(
                completeFile(hash(HASH_A), "file a.avi", 1234567),
                completeFile(hash(HASH_B), "file b.avi", 7654321)), new ByteArrayOutputStream());

        // OP_GLOBGETSOURCES with two hashes (SRV_UDPFLG_EXT_GETSOURCES)
        ByteBuffer req = ByteBuffer.allocate(2 + 32);
        req.put(Packet.PROTOCOL_ED2K).put((byte) 0x9A).put(hash(HASH_A)).put(hash(HASH_B));
        CapturingDatagramSocket cds = new CapturingDatagramSocket();
        var m = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, cds, new DatagramPacket(req.array(), req.capacity(), InetAddress.getLoopbackAddress(), 40000));

        assertEquals(1, cds.sent.size());
        ByteBuffer buf = ByteBuffer.wrap(cds.sent.getFirst().getData()).order(ByteOrder.LITTLE_ENDIAN);
        String src = "10.1.2.3:" + LISTEN_PORT;
        assertEquals(Packet.PROTOCOL_ED2K, buf.get());
        assertEquals((byte) 0x9B, buf.get());
        assertEquals(List.of(src), readSourceBlock(buf, HASH_A));
        assertEquals(Packet.PROTOCOL_ED2K, buf.get());
        assertEquals((byte) 0x9B, buf.get());
        assertEquals(List.of(src), readSourceBlock(buf, HASH_B));
        assertFalse(buf.hasRemaining());
        server.stop();
    }

    @Test
    void sourcesWithoutKnownListenPortAreNotHandedOut() throws Exception {
        publisher.getState().setListenPort(0);
        FakeContext requester = loggedInContext("10.9.9.9", 50001, CONFIG, registry, index);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SourceHandler().handleGetSources(requester, new Packet(Packet.PROTOCOL_ED2K, OpCode.GET_SOURCES.value, hash(HASH_A)), out);
        Packet resp = Packet.read(new ByteArrayInputStream(out.toByteArray()), 65536);
        assertEquals(0, resp.data()[16], "no usable source");
    }

    @Test
    void everyPublisherOfAFileIsASource() throws Exception {
        FakeContext second = loggedInContext("10.4.5.6", 50002, CONFIG, registry, index);
        FakeContext sameNat = loggedInContext("10.1.2.3", 50003, CONFIG, registry, index); // same IP as publisher
        sameNat.getState().setListenPort(4663);
        for (FakeContext c : List.of(second, sameNat)) {
            new PublishHandler().handlePublish(c, OpCode.OFFER_FILES,
                    offerFiles(completeFile(hash(HASH_A), "other name.avi", 1234567)), new ByteArrayOutputStream());
        }

        FakeContext requester = loggedInContext("10.9.9.9", 50001, CONFIG, registry, index);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SourceHandler().handleGetSources(requester, new Packet(Packet.PROTOCOL_ED2K, OpCode.GET_SOURCES.value, hash(HASH_A)), out);
        Packet resp = Packet.read(new ByteArrayInputStream(out.toByteArray()), 65536);
        List<String> sources = readSourceBlock(ByteBuffer.wrap(resp.data()).order(ByteOrder.LITTLE_ENDIAN), HASH_A);
        assertEquals(java.util.Set.of("10.1.2.3:4662", "10.4.5.6:4662", "10.1.2.3:4663"), new java.util.HashSet<>(sources));
        assertEquals(1, index.fileCount());
    }

    @Test
    void getSourcesObfuIsRoutedToTheSourceHandler() throws Exception {
        // Default eMule (crypt layer supported) + server announcing SRV_TCPFLG_TCPOBFUSCATION:
        // the client sends OP_GETSOURCES_OBFU = 0xE3/0x23 <hash 16><size 4>
        FakeContext requester = loggedInContext("10.9.9.9", 50001, CONFIG, registry, index);
        ByteBuffer req = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put(hash(HASH_A)).putInt(1234567);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new org.jemule.network.handler.PacketProcessor().processPacket(requester, new Packet(Packet.PROTOCOL_ED2K, (byte) 0x23, req.array()), out);

        assertTrue(out.size() > 0, "OP_GETSOURCES_OBFU must be answered");
        Packet resp = Packet.read(new ByteArrayInputStream(out.toByteArray()), 65536);
        assertEquals(Packet.PROTOCOL_ED2K, resp.protocol());
        assertTrue(resp.opcode() == 0x42 || resp.opcode() == 0x44, "OP_FOUNDSOURCES(_OBFU) expected");
    }
}
