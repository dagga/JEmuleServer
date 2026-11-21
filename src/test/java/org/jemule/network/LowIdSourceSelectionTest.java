package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.network.handler.HandlerUtils;
import org.jemule.network.handler.SourceHandler;
import org.jemule.network.natt.KeepaliveToken;
import org.jemule.network.natt.NatCallbackService;
import org.jemule.network.natt.UdpEndpoint;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The LowID sources a requester gets (#126): all for a HighID, only those it can reach by NAT traversal for a LowID,
 * chosen before the limit; to another server's client only those of the cross-server callback.
 */
class LowIdSourceSelectionTest {
    static final int NATT = ClientState.SRVCAP_NATTRAVERSAL;
    static final int EXTENSION = ClientState.SRVCAP_HOLEPUNCH_EXTENSION;
    static final byte[] FILE = hash("0123456789abcdef0123456789abcdef");
    static final long HIGH = 0x2A6ED85BL;

    final AtomicBoolean callback = new AtomicBoolean(true);
    final AtomicBoolean holePunch = new AtomicBoolean(true);
    final AtomicBoolean crossServer = new AtomicBoolean(false);
    final Map<Long, ClientState> clients = new HashMap<>();
    final List<ClientState> sources = new ArrayList<>();
    NatCallbackService service;
    int nextHash;

    @BeforeEach
    void setup() {
        service = new NatCallbackService(callback::get, () -> false, () -> callback.get() && holePunch.get(),
                crossServer::get, ip -> false, System::currentTimeMillis, new NatCallbackService.Transport() {
                    @Override public void sendTcp(long clientId, org.jemule.network.Packet packet) { }
                    @Override public void sendUdp(DatagramSocket socket, UdpEndpoint to, byte[] datagram) { }
                }, new KeepaliveToken(), clients::get, (task, delayMs) -> { });
    }

    /** A logged-in client with ID {@code id}, CT_SERVER_FLAGS {@code caps} and 0xF9 client UDP port {@code udpPort}. */
    ClientState client(long id, int caps, int udpPort) throws Exception {
        String address = id >= 0x1000000 ? "91.216.110.42" : "10.0.0." + (id % 250 + 1);
        ClientState c = new ClientState(InetAddress.getByName(address), 50000, id, System.currentTimeMillis(),
                new AtomicLong());
        byte[] userHash = new byte[16];
        Arrays.fill(userHash, (byte) ++nextHash);
        c.setUserHash(userHash);
        c.setServerCaps(caps);
        c.setListenPort(4662);
        c.setEmuleUdpPorts((udpPort << 16) | udpPort);
        clients.put(id, c);
        return c;
    }

    /** A LowID registered as LoginHandler does: for the callback with 0x1000, else for the hole punch with 0xF9. */
    ClientState lowId(long id, int caps, int udpPort) throws Exception {
        ClientState c = client(id, caps, udpPort);
        if ((caps & NATT) != 0) service.register(c);
        else if (udpPort != 0) service.register(c, false);
        return c;
    }

    /** The sources of FILE: a HighID, a Lugdunum LowID (0x1000), a hole-punch LowID (0xF9), a stock LowID. */
    void standardSources() throws Exception {
        sources.add(client(HIGH, 0, 0));
        sources.add(lowId(11, NATT, 0));
        sources.add(lowId(12, 0, 4672));
        sources.add(lowId(14, 0, 0));
    }

    /** The IDs of the OP_FOUNDSOURCES answer to {@code requester}. */
    Set<Long> answer(ClientState requester, ServerConfig config, boolean fromServer) throws Exception {
        FileIndex index = new FileIndex(null);
        FileMetadata file = new FileMetadata(HandlerUtils.bytesToHashString(FILE), "file.avi", 1000, "Video");
        for (ClientState s : sources) file.sources().put(FileIndex.sourceKey(s), s);
        index.addFile(file);
        FakeContext ctx = new FakeContext(new DummySocket(requester.address(), 50000), config, new ClientRegistry(),
                index);
        ctx.setState(requester);
        ctx.setFromServer(fromServer);
        ctx.setNatCallbackService(service);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] request = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put(FILE).putInt(1000).array();
        new SourceHandler().handleGetSources(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.GET_SOURCES.value, request),
                out);
        Packet packet = Packet.read(new ByteArrayInputStream(out.toByteArray()), 65536);
        assertEquals(OpCode.FOUND_SOURCES.value, packet.opcode());
        ByteBuffer b = ByteBuffer.wrap(packet.data()).order(ByteOrder.LITTLE_ENDIAN);
        int count = b.get(16) & 0xFF;
        Set<Long> ids = new TreeSet<>();
        for (int i = 0; i < count; i++) ids.add(Integer.toUnsignedLong(b.getInt(17 + 6 * i)));
        return ids;
    }

    Set<Long> answer(ClientState requester) throws Exception {
        return answer(requester, ServerConfig.DEFAULT, false);
    }

    static Set<Long> ids(Long... ids) {
        return new TreeSet<>(List.of(ids));
    }

    @Test
    void aHighIdRequesterAndAnotherServerGetEverySource() throws Exception {
        standardSources();
        assertEquals(ids(11L, 12L, 14L, HIGH), answer(client(0x3A6ED85BL, 0, 0)), "it calls any LowID back");
        assertEquals(ids(11L, 12L, 14L, HIGH), answer(lowId(20, 0, 0), ServerConfig.DEFAULT, true));
    }

    @Test
    void aStockLowIdGetsTheHighIdSourcesOnly() throws Exception {
        standardSources();
        // eMule while LowID drops LowID sources (CPartFile::CanAddSource) and cannot have them called back
        assertEquals(ids(HIGH), answer(lowId(20, 0, 0)));
    }

    @Test
    void aLugdunumRequesterGetsTheLugdunumSources() throws Exception {
        standardSources();
        // 0x1000 only (NeoMule, NeoLoader): 0xA6 reaches the other Lugdunum clients, it sends no 0x60
        assertEquals(ids(11L, HIGH), answer(lowId(21, NATT, 0)));
        holePunch.set(false);
        assertEquals(ids(11L, HIGH), answer(lowId(23, NATT, 0)));
    }

    @Test
    void aHolePunchRequesterGetsEveryLowIdWithASession() throws Exception {
        standardSources();
        // 0xF9: 0x60 to a 0xF9 target (0x61) and to a Lugdunum-only one (0x37 bridge); never to a stock LowID
        assertEquals(ids(11L, 12L, HIGH), answer(lowId(20, 0, 4672)));
        // The extension alone makes it ask with 0x60 too; aMule master sets everything
        assertEquals(ids(11L, 12L, HIGH), answer(lowId(22, EXTENSION, 0)));
        assertEquals(ids(11L, 12L, HIGH), answer(lowId(24, NATT | EXTENSION, 4672)));
    }

    @Test
    void theNatOptionsDecideWhatIsReachable() throws Exception {
        standardSources();
        holePunch.set(false);
        assertEquals(ids(HIGH), answer(lowId(20, 0, 4672)), "no hole punch: a 0xF9 requester reaches no LowID");
        assertEquals(ids(11L, HIGH), answer(lowId(24, NATT | EXTENSION, 4672)), "the callback only: the Lugdunum source");
        callback.set(false);
        assertEquals(ids(HIGH), answer(lowId(26, NATT | EXTENSION, 4672)), "NAT traversal off: HighIDs only");
    }

    @Test
    void theUselessLowIdsAreLeftOutBeforeTheLimit() throws Exception {
        for (int i = 0; i < 10; i++) sources.add(lowId(100 + 2L * i, 0, 0));
        sources.add(client(HIGH, 0, 0));
        sources.add(lowId(11, NATT, 0));
        // 3 records at most: before #126, the stock LowIDs could take all three
        ServerConfig config = new ServerConfig(14667, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 3,
                null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 0);
        for (int i = 0; i < 20; i++) {
            assertEquals(ids(11L, HIGH), answer(lowId(21, NATT, 0), config, false));
        }
    }

    @Test
    void anotherServersClientGetsOnlyTheLowIdsOfTheCrossServerCallback() throws Exception {
        standardSources();
        assertFalse(service.foreignSourceUsable(sources.get(1)), "cross-server callback off");
        crossServer.set(true);
        assertTrue(service.foreignSourceUsable(sources.get(1)), "a Lugdunum LowID can be woken by 0xA6 with ID 0");
        assertFalse(service.foreignSourceUsable(sources.get(2)), "a hole-punch session did not opt in with 0x1000");
        assertFalse(service.foreignSourceUsable(sources.get(3)), "a stock LowID");
        callback.set(false);
        assertFalse(service.foreignSourceUsable(sources.get(1)), "the callback off");
    }
}
