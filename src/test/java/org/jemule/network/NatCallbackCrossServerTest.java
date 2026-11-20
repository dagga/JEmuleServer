package org.jemule.network;

import org.jemule.config.NatTraversalConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientState;
import org.jemule.network.natt.CrossServerCallback;
import org.jemule.network.natt.NatOpcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-server NAT callback through the server's UDP ports: a server-key obfuscated 0xA6 wakes the target over TCP,
 * the target's 0x9F releases the 0xA7, obfuscated with the requester's key, from the port the request came to.
 */
class NatCallbackCrossServerTest {

    static final int PORT = SourcesCompatibilityTest.CONFIG.port();
    static final ServerConfig ENABLED = SourcesCompatibilityTest.CONFIG.withNatTraversal(new NatTraversalConfig(true, true));

    Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    SourcesCompatibilityTest.CapturingDatagramSocket udp(byte[] datagram, int localPort, int sourcePort) throws Exception {
        SourcesCompatibilityTest.CapturingDatagramSocket cds = new SourcesCompatibilityTest.CapturingDatagramSocket() {
            @Override public int getLocalPort() { return localPort; }
        };
        var m = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, cds, new DatagramPacket(datagram, datagram.length, InetAddress.getLoopbackAddress(), sourcePort));
        return cds;
    }

    int statusUdpFlags() throws Exception {
        byte[] statReq = UdpObfuscationCompatibilityTest.statReq(0x55AA0001);
        byte[] answer = udp(statReq, PORT + 4, 40000).sent.getFirst().getData();
        return ByteBuffer.wrap(answer, 2 + 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    @Test
    void theOptionsAreReadAndWritten() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("natCallback", "true");
        props.setProperty("natCallbackCrossServer", "true");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals(new NatTraversalConfig(true, true), config.natTraversal());
        assertEquals("true", config.toProperties().get("natCallbackCrossServer"));
        assertEquals(NatTraversalConfig.DEFAULT, ServerConfig.DEFAULT.natTraversal());
        assertEquals("false", ServerConfig.DEFAULT.toProperties().get("natCallbackCrossServer"));
        assertEquals(config.natTraversal(), config.withPort(4242).natTraversal(), "kept by the other with* methods");
        assertNotNull(org.jemule.config.ConfigParameter.byKey("natCallbackCrossServer"));
    }

    @Test
    void theForeignCallbackFlagIsAnnouncedOnlyWhenEnabled() throws Exception {
        server = new Server(SourcesCompatibilityTest.CONFIG, new ClientFactory());
        assertEquals(0, statusUdpFlags() & 0x100000, "off by default");
        server.stop();

        server = new Server(SourcesCompatibilityTest.CONFIG.withNatTraversal(new NatTraversalConfig(false, true)),
                new ClientFactory());
        assertEquals(0, statusUdpFlags() & 0x100000, "needs natCallback");
        server.stop();

        server = new Server(ENABLED, new ClientFactory());
        assertEquals(0x100000, statusUdpFlags() & 0x100000, "SRV_UDPFLG_FOREIGN_NAT_CALLBACK");
        assertEquals(0x200, statusUdpFlags() & 0x200, "the other flags stay");
    }

    @Test
    void anObfuscatedRequestWakesTheTargetAndItsKeepaliveAnswersTheRequester() throws Exception {
        server = new Server(ENABLED, new ClientFactory());
        InetAddress loopback = InetAddress.getLoopbackAddress();
        List<Packet> toTarget = new CopyOnWriteArrayList<>();
        byte[] targetHash = EmuleTestSupport.hash("00112233445566778899aabbccddeeff");
        ClientState target = new ClientState(loopback, 4662, 5, System.currentTimeMillis(),
                new AtomicLong(System.currentTimeMillis()));
        target.setUserHash(targetHash);
        target.setServerCaps(ClientState.SRVCAP_NATTRAVERSAL | ClientState.SRVCAP_HOLEPUNCH_EXTENSION
                | ClientState.SRVCAP_SUPPORTCRYPT);
        server.getClientRegistry().add(target, toTarget::add);
        server.getNatCallbackService().register(target); // as LoginHandler does for a client setting 0x1000

        byte[] request = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).put(Packet.PROTOCOL_ED2K)
                .put(NatOpcodes.OP_NAT_CALLBACKREQUEST).putInt(5).putInt(0).put((byte) 0x01).array();

        // Plain: dropped, nothing anywhere
        assertTrue(udp(request, PORT + 4, 40000).sent.isEmpty());
        assertTrue(toTarget.isEmpty());

        // Obfuscated with the key the status reply gave this address, to the obfuscation port
        int key = org.jemule.security.UdpObfuscation.serverKeyFor(loopback);
        SourcesCompatibilityTest.CapturingDatagramSocket obfuscationPort =
                udp(UdpObfuscationCompatibilityTest.clientEncrypt(request, key), PORT + 12, 40000);
        assertEquals(1, toTarget.size());
        assertEquals(NatOpcodes.OP_NAT_CALLBACKREQUESTED, toTarget.getFirst().opcode());
        assertTrue(obfuscationPort.sent.isEmpty(), "the answer waits for the target's 0x9F");

        // The target's 0x9F, plain to TCP port + 4
        byte[] keepalive = ByteBuffer.allocate(18).put(Packet.PROTOCOL_ED2K).put(NatOpcodes.OP_NATT_KEEPALIVE)
                .put(targetHash).array();
        assertTrue(udp(keepalive, PORT + 4, 51234).sent.isEmpty());

        assertEquals(1, obfuscationPort.sent.size(), "answered from the port the request came to");
        DatagramPacket sent = obfuscationPort.sent.getFirst();
        assertEquals(40000, sent.getPort(), "to the requester's endpoint");
        byte[] answer = UdpObfuscationCompatibilityTest.clientDecrypt(sent.getData(), key);
        assertNotNull(answer, "obfuscated with the requester's key");
        assertEquals(2 + 27, answer.length);
        assertEquals(NatOpcodes.OP_NAT_CALLBACKREQUESTED_UDP, answer[1]);
        ByteBuffer a = ByteBuffer.wrap(answer, 2, 27).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((int) ClientState.ipToLong(loopback), a.getInt());
        assertEquals(51234, Short.toUnsignedInt(a.getShort()));
        assertEquals(5, a.getInt());
        assertEquals(1, server.getNatCallbackService().crossServer().count(CrossServerCallback.Outcome.WOKEN));

        // Gone: the session is forgotten
        server.getClientRegistry().remove(target);
        server.getNatCallbackService().unregister(target);
        assertTrue(server.getNatCallbackService().endpointOf(5).isEmpty());
    }
}
