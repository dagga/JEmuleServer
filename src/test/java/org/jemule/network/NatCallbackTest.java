package org.jemule.network;

import org.jemule.config.NatTraversalConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.natt.NatCallbackService;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lugdunum NAT callback (natCallback): opt-in at login, then the 0x9F / 0xA6 datagrams through the server's UDP
 * handling, server-key obfuscated as eMule's CEncryptedDatagramSocket::EncryptSendServer does.
 */
class NatCallbackTest {

    /** Every client gets a LowID (no HighID test). */
    static final ServerConfig CONFIG = new ServerConfig(
            14665, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 200,
            null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 0);
    static final ServerConfig NATT_CONFIG = CONFIG.withNatTraversal(new NatTraversalConfig(true));
    static final int CONNECTION_PORT = 51000;

    /** OP_IDCHANGE of one login, and the context it ran in. */
    record Login(FakeContext context, byte[] idChange) {
        long id() {
            return Integer.toUnsignedLong(ByteBuffer.wrap(idChange).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        }

        int flags() {
            return ByteBuffer.wrap(idChange).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
        }
    }

    static Login login(ServerConfig config, ClientRegistry registry, NatCallbackService service, int hashByte, int caps)
            throws Exception {
        FakeContext ctx = new FakeContext(new DummySocket(InetAddress.getLoopbackAddress(), CONNECTION_PORT), config,
                registry, new FileIndex(null)) {
            @Override public NatCallbackService getNatCallbackService() { return service; }
        };
        byte[] userHash = new byte[16];
        Arrays.fill(userHash, (byte) hashByte);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value,
                loginRequest(userHash, 4662, "tester", caps)), out);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        while (in.available() > 0) {
            Packet p = Packet.read(in, 65536);
            if (p.opcode() == OpCode.ID_CHANGE.value) return new Login(ctx, p.data());
        }
        throw new AssertionError("no OP_IDCHANGE");
    }

    static NatCallbackService service(boolean enabled) {
        return new NatCallbackService(() -> enabled, System::currentTimeMillis, new NatCallbackService.Transport() {
            @Override public void sendTcp(long clientId, Packet packet) { }
            @Override public void sendUdp(java.net.DatagramSocket s, org.jemule.network.natt.UdpEndpoint to, byte[] d) { }
        });
    }

    @Test
    void optedInClientGetsAnOddLowIdAndTheLongIdChange() throws Exception {
        ClientRegistry registry = new ClientRegistry();
        NatCallbackService service = service(true);
        int caps = EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_NATTRAVERSAL;
        for (int i = 0; i < 3; i++) {
            Login login = login(NATT_CONFIG, registry, service, 0x10 + i, caps);
            assertEquals(1, login.id() & 1, "odd LowID: " + login.id());
            assertTrue(login.id() < 0x01000000);
            assertEquals(24, login.idChange().length);
            assertEquals(Tag.TCPFLG_NATT, login.flags() & Tag.TCPFLG_NATT);
            ByteBuffer b = ByteBuffer.wrap(login.idChange()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(NATT_CONFIG.port(), b.getInt(8), "server TCP port");
            assertEquals(0x0100007F, b.getInt(12), "reported IP");
            assertEquals(NATT_CONFIG.port(), b.getInt(16), "obfuscation TCP port");
            assertEquals(CONNECTION_PORT, b.getInt(20), "a LowID's TCP port as the server sees it");
            assertTrue(service.isOptedIn(login.id()));
        }
        assertEquals(3, service.sessionCount());
    }

    @Test
    void otherClientsGetEvenLowIdsAndTheShortIdChange() throws Exception {
        ClientRegistry registry = new ClientRegistry();
        NatCallbackService service = service(true);
        for (int i = 0; i < 3; i++) {
            Login login = login(NATT_CONFIG, registry, service, 0x20 + i, EMULE_DEFAULT_SRVCAPS);
            assertEquals(0, login.id() & 1, "even LowID: " + login.id());
            assertEquals(20, login.idChange().length);
            assertEquals(Tag.TCPFLG_NATT, login.flags() & Tag.TCPFLG_NATT, "the server flag goes to every client");
        }
        assertEquals(0, service.sessionCount());
    }

    @Test
    void disabledByDefault() throws Exception {
        assertFalse(ServerConfig.DEFAULT.natTraversal().natCallback());
        assertFalse(ServerConfig.fromProperties(new java.util.Properties()).natTraversal().natCallback());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("natCallback", "true");
        assertTrue(ServerConfig.fromProperties(props).natTraversal().natCallback());
        assertEquals("true", NATT_CONFIG.toProperties().get("natCallback"));

        ClientRegistry registry = new ClientRegistry();
        NatCallbackService service = service(false);
        Login first = login(CONFIG, registry, service, 0x30, EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_NATTRAVERSAL);
        Login second = login(CONFIG, registry, service, 0x31, EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_NATTRAVERSAL);
        assertEquals(1, first.id());
        assertEquals(2, second.id(), "LowIDs of any parity");
        assertEquals(20, first.idChange().length);
        assertEquals(0, first.flags() & Tag.TCPFLG_NATT);
        assertEquals(Tag.SERVER_TCP_FLAGS, first.flags());
        assertEquals(0, service.sessionCount());
    }

    // ---- Through the server's UDP handling

    static byte[] request(long target, long requester) {
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0xE3).put((byte) 0xA6)
                .putInt((int) target).putInt((int) requester).array();
    }

    static void receive(Server server, java.net.DatagramSocket socket, byte[] datagram, int fromPort) throws Exception {
        var m = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, socket, new DatagramPacket(datagram, datagram.length, InetAddress.getLoopbackAddress(), fromPort));
    }

    static ClientState optedIn(Server server, long id, int hashByte, List<Packet> received) {
        ClientState c = new ClientState(InetAddress.getLoopbackAddress(), CONNECTION_PORT, id, 0, new AtomicLong());
        byte[] userHash = new byte[16];
        Arrays.fill(userHash, (byte) hashByte);
        c.setUserHash(userHash);
        c.setServerCaps(EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_NATTRAVERSAL);
        server.getClientRegistry().add(c, received::add);
        server.getNatCallbackService().register(c);
        return c;
    }

    @Test
    void callbackThroughTheServerUdpPorts() throws Exception {
        Server server = new Server(NATT_CONFIG, new ClientFactory());
        try {
            List<Packet> toA = new ArrayList<>();
            List<Packet> toB = new ArrayList<>();
            optedIn(server, 11, 0xA, toA);
            optedIn(server, 13, 0xB, toB);
            int key = org.jemule.security.UdpObfuscation.serverKeyFor(InetAddress.getLoopbackAddress());
            SourcesCompatibilityTest.CapturingDatagramSocket obfuscatedPort = new SourcesCompatibilityTest.CapturingDatagramSocket();

            // 0x9F, plain to port + 4: A's public UDP endpoint
            byte[] keepalive = new byte[18];
            keepalive[0] = (byte) 0xE3;
            keepalive[1] = (byte) 0x9F;
            Arrays.fill(keepalive, 2, 18, (byte) 0xA);
            receive(server, obfuscatedPort, keepalive, 40000);
            assertEquals(40000, server.getNatCallbackService().endpointOf(11).orElseThrow().port());

            // A plain 0xA6 is ignored
            receive(server, obfuscatedPort, request(13, 11), 40000);
            assertTrue(toB.isEmpty());

            // A asks for B: B gets TCP 0x37 with A's endpoint
            receive(server, obfuscatedPort, UdpObfuscationCompatibilityTest.clientEncrypt(request(13, 11), key), 40000);
            assertEquals(1, toB.size());
            assertEquals((byte) 0x37, toB.getFirst().opcode());
            assertArrayEquals(new byte[]{127, 0, 0, 1, 0x40, (byte) 0x9C, 11, 0, 0, 0}, Arrays.copyOf(toB.getFirst().data(), 10));
            assertTrue(obfuscatedPort.sent.isEmpty(), "the requester gets no answer");

            // B's reverse request: A, which asked just now, gets UDP 0xA7 where its request came from
            receive(server, obfuscatedPort, UdpObfuscationCompatibilityTest.clientEncrypt(request(11, 13), key), 50000);
            assertTrue(toA.isEmpty(), "no TCP notice");
            assertEquals(1, obfuscatedPort.sent.size());
            assertEquals(40000, obfuscatedPort.sent.getFirst().getPort());
            // A asks again: so does B now
            receive(server, obfuscatedPort, UdpObfuscationCompatibilityTest.clientEncrypt(request(13, 11), key), 40000);
            assertEquals(1, toB.size(), "no second TCP notice");
            assertEquals(2, obfuscatedPort.sent.size());
            DatagramPacket sent = obfuscatedPort.sent.get(1);
            assertEquals(50000, sent.getPort());
            byte[] plain = UdpObfuscationCompatibilityTest.clientDecrypt(Arrays.copyOf(sent.getData(), sent.getLength()), key);
            assertNotNull(plain, "obfuscated with B's server key");
            assertEquals(2 + 27, plain.length);
            assertArrayEquals(new byte[]{(byte) 0xE3, (byte) 0xA7, 127, 0, 0, 1, 0x40, (byte) 0x9C, 11, 0, 0, 0},
                    Arrays.copyOf(plain, 12));
        } finally {
            server.stop();
        }
    }
}
