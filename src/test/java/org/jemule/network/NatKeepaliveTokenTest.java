package org.jemule.network;

import org.jemule.config.NatTraversalConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.natt.KeepaliveToken;
import org.jemule.network.natt.NatCallbackService;
import org.jemule.network.natt.UdpEndpoint;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * NAT callback session token (natCallbackToken): ST_NATT_TOKEN and ST_NATT_PROOF in OP_SERVERIDENT, and the 0x9F
 * keepalives the server's UDP port takes with it.
 */
class NatKeepaliveTokenTest {

    static final byte[] USER_HASH = HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
    /** No HighID test: every client is a LowID. */
    static final ServerConfig BASE = new ServerConfig(
            14667, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 200,
            null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 0);

    Server server;

    @BeforeEach
    void setUp() {
        server = new Server(BASE.withNatTraversal(new NatTraversalConfig(true, true)), new ClientFactory());
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    record Login(ClientState state, List<Tag> identTags) {
    }

    /** Logs a client in from 127.0.0.1 and returns the tags of its OP_SERVERIDENT. */
    Login login(ServerConfig config, int srvCaps) throws Exception {
        return login(config, loginRequest(USER_HASH, 0, "natt", srvCaps));
    }

    /** Same, the login also carrying CT_EMULE_UDPPORTS (0xF9) as aMule's ServerConnect.cpp sends it. */
    Login loginWithUdpPorts(ServerConfig config, int udpPorts) throws Exception {
        byte[] request = loginRequest(USER_HASH, 0, "natt", EMULE_DEFAULT_SRVCAPS);
        ByteBuffer b = ByteBuffer.allocate(request.length + 8).order(ByteOrder.LITTLE_ENDIAN).put(request);
        b.putInt(16 + 4 + 2, 5); // tag count
        b.put((byte) 0x03).putShort((short) 1).put((byte) 0xF9).putInt(udpPorts);
        return login(config, b.array());
    }

    Login login(ServerConfig config, byte[] request) throws Exception {
        NatCallbackService natCallback = server.getNatCallbackService();
        FakeContext ctx = new FakeContext(new DummySocket(InetAddress.getLoopbackAddress(), 51000), config,
                new ClientRegistry(), new FileIndex(null)) {
            @Override public NatCallbackService getNatCallbackService() { return natCallback; }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value, request), out);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        while (in.available() > 0) {
            Packet p = Packet.read(in, 65536);
            if (p.opcode() == OpCode.SERVER_IDENT.value) {
                ByteBuffer b = ByteBuffer.wrap(p.data()).order(ByteOrder.LITTLE_ENDIAN);
                b.position(16 + 4 + 2); // <hash 16><IP 4><port 2><tags>
                return new Login(ctx.getState(), Tag.readList(b));
            }
        }
        throw new AssertionError("no OP_SERVERIDENT");
    }

    static Tag tag(List<Tag> tags, String name) {
        return tags.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    void keepalive(byte[] payload, String fromIp, int fromPort) throws Exception {
        send(datagram(payload), fromIp, fromPort);
    }

    /** A 0x9F obfuscated the server way with the server UDP key of {@code fromIp}, as sent to the obfuscation port. */
    void obfuscatedKeepalive(byte[] payload, String fromIp, int fromPort) throws Exception {
        int serverKey = org.jemule.security.UdpObfuscation.serverKeyFor(InetAddress.getByName(fromIp));
        send(UdpObfuscationCompatibilityTest.clientEncrypt(datagram(payload), serverKey), fromIp, fromPort);
    }

    static byte[] datagram(byte[] payload) {
        byte[] datagram = new byte[2 + payload.length];
        datagram[0] = (byte) 0xE3;
        datagram[1] = (byte) 0x9F;
        System.arraycopy(payload, 0, datagram, 2, payload.length);
        return datagram;
    }

    void send(byte[] datagram, String fromIp, int fromPort) throws Exception {
        var handleUdp = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, DatagramPacket.class);
        handleUdp.setAccessible(true);
        handleUdp.invoke(server, new SourcesCompatibilityTest.CapturingDatagramSocket(),
                new DatagramPacket(datagram, datagram.length, InetAddress.getByName(fromIp), fromPort));
    }

    @Test
    void natTraversalLowIdGetsATokenAndProvesItFromAnotherIp() throws Exception {
        Login login = login(BASE.withNatTraversal(new NatTraversalConfig(true, true)), EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_NATTRAVERSAL);
        long lowId = login.state().clientId();
        assertFalse(login.state().isHighId());

        Tag token = tag(login.identTags(), Tag.NAME_NATT_TOKEN);
        assertNotNull(token, "ST_NATT_TOKEN (0xAC)");
        assertEquals(Tag.TYPE_HASH, token.type());
        byte[] tokenBytes = (byte[]) token.value();
        assertEquals(16, tokenBytes.length);
        Tag proof = tag(login.identTags(), Tag.NAME_NATT_PROOF);
        assertNotNull(proof, "ST_NATT_PROOF (0xA9)");
        assertEquals(Tag.TYPE_INTEGER, proof.type());
        assertEquals(1L, proof.unsignedValue());

        NatCallbackService natCallback = server.getNatCallbackService();
        // The user hash alone from another IP than the login's: ignored
        keepalive(USER_HASH, "198.51.100.20", 40001);
        assertEquals(Optional.empty(), natCallback.endpointOf(lowId));

        // The proof: taken from any address
        keepalive(KeepaliveToken.proof(USER_HASH, tokenBytes, 1), "198.51.100.20", 40001);
        assertEquals(Optional.of(new UdpEndpoint(InetAddress.getByName("198.51.100.20"), 40001)), natCallback.endpointOf(lowId));

        // Replayed from elsewhere: the endpoint stays
        keepalive(KeepaliveToken.proof(USER_HASH, tokenBytes, 1), "203.0.113.5", 40009);
        assertEquals(Optional.of(new UdpEndpoint(InetAddress.getByName("198.51.100.20"), 40001)), natCallback.endpointOf(lowId));

        // The user hash alone from the login IP still counts
        keepalive(USER_HASH, "127.0.0.1", 40002);
        assertEquals(Optional.of(new UdpEndpoint(InetAddress.getLoopbackAddress(), 40002)), natCallback.endpointOf(lowId));

        // A proof obfuscated the server way, from yet another address
        obfuscatedKeepalive(KeepaliveToken.proof(USER_HASH, tokenBytes, 2), "203.0.113.5", 40003);
        assertEquals(Optional.of(new UdpEndpoint(InetAddress.getByName("203.0.113.5"), 40003)), natCallback.endpointOf(lowId));

        // An obfuscated user hash alone from the login IP: from then on a plain one is ignored
        obfuscatedKeepalive(USER_HASH, "127.0.0.1", 40004);
        assertEquals(Optional.of(new UdpEndpoint(InetAddress.getLoopbackAddress(), 40004)), natCallback.endpointOf(lowId));
        keepalive(USER_HASH, "127.0.0.1", 40005);
        assertEquals(Optional.of(new UdpEndpoint(InetAddress.getLoopbackAddress(), 40004)), natCallback.endpointOf(lowId));

        server.getNatCallbackService().unregister(login.state());
        assertEquals(Optional.empty(), natCallback.endpointOf(lowId));
    }

    @Test
    void udpPortsLoginTagAlsoGetsAToken() throws Exception {
        Login login = loginWithUdpPorts(BASE.withNatTraversal(new NatTraversalConfig(true, true)), (4672 << 16) | 4672);
        assertEquals((4672 << 16) | 4672, login.state().emuleUdpPorts());
        assertNotNull(tag(login.identTags(), Tag.NAME_NATT_TOKEN));
        assertNotNull(tag(login.identTags(), Tag.NAME_NATT_PROOF));
    }

    @Test
    void noTokenWithoutTheOptInOrTheOptions() throws Exception {
        int natt = EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_NATTRAVERSAL;
        assertNull(tag(login(BASE.withNatTraversal(new NatTraversalConfig(true, true)), EMULE_DEFAULT_SRVCAPS).identTags(), Tag.NAME_NATT_TOKEN),
                "client without SRVCAP_NATTRAVERSAL");
        assertNull(tag(login(BASE.withNatTraversal(new NatTraversalConfig(true, false)), natt).identTags(), Tag.NAME_NATT_TOKEN), "natCallbackToken off");
        assertNull(tag(login(BASE.withNatTraversal(new NatTraversalConfig(false, true)), natt).identTags(), Tag.NAME_NATT_TOKEN), "natCallback off");
        assertNull(tag(login(BASE, natt).identTags(), Tag.NAME_NATT_PROOF), "off by default");
    }

    @Test
    void keepalivesAreIgnoredWhileTheCallbackIsOff() throws Exception {
        server.stop();
        server = new Server(BASE, new ClientFactory());
        Login login = login(BASE.withNatTraversal(new NatTraversalConfig(true, true)), EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_NATTRAVERSAL);
        keepalive(USER_HASH, "127.0.0.1", 40002);
        assertEquals(Optional.empty(), server.getNatCallbackService().endpointOf(login.state().clientId()));
    }

    @Test
    void optionsAreReadFromServerProperties() {
        assertFalse(ServerConfig.DEFAULT.natTraversal().natCallback());
        assertFalse(ServerConfig.DEFAULT.natTraversal().natCallbackToken());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("natCallback", "true");
        props.setProperty("natCallbackToken", " true ");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertTrue(config.natTraversal().natCallback());
        assertTrue(config.natTraversal().natCallbackToken());
        assertEquals("true", config.toProperties().get("natCallbackToken"));
        assertNotNull(org.jemule.config.ConfigParameter.byKey("natCallbackToken"));
        assertTrue(config.withPort(4000).natTraversal().natCallbackToken(), "kept by the other with* methods");
    }
}
