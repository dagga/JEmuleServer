package org.jemule.network;

import org.jemule.config.NatTraversalConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.handler.PacketProcessor;
import org.jemule.network.natt.NatCallbackService;
import org.jemule.network.natt.NatOpcodes;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.AfterEach;
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

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ed2k-server's LowID hole punch (natHolePunch) through the login and the server's TCP and UDP handling: the
 * OP_IDCHANGE flags, the 0xF9 registration, 0x60 answered by 0x61, and the extension's 0x37 wake released by a 0x9F.
 */
class NatHolePunchTest {

    /** No HighID test: every client is a LowID. */
    static final ServerConfig BASE = new ServerConfig(
            14669, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 200,
            null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 0);
    static final ServerConfig HOLE_PUNCH = BASE.withNatTraversal(new NatTraversalConfig(true, false, true));
    static final int EXT = ClientState.SRVCAP_HOLEPUNCH_EXTENSION | ClientState.SRVCAP_NATTRAVERSAL;

    Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** One login: its context, OP_IDCHANGE, and everything the server wrote on its connection. */
    record Login(FakeContext context, byte[] idChange, ByteArrayOutputStream out) {
        ClientState state() {
            return context.getState();
        }

        int flags() {
            return ByteBuffer.wrap(idChange).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
        }

        /** The packets written after the login ones, from {@code from} bytes on. */
        List<Packet> packetsAfter(int from) throws Exception {
            byte[] all = out.toByteArray();
            ByteArrayInputStream in = new ByteArrayInputStream(all, from, all.length - from);
            List<Packet> packets = new ArrayList<>();
            while (in.available() > 0) packets.add(Packet.read(in, 65536));
            return packets;
        }
    }

    /** Logs a client in from 127.0.0.1 on {@code server}, with CT_EMULE_UDPPORTS when {@code udpPort} is not 0. */
    Login login(ServerConfig config, int hashByte, int caps, int udpPort) throws Exception {
        byte[] userHash = new byte[16];
        Arrays.fill(userHash, (byte) hashByte);
        byte[] request = loginRequest(userHash, 4662, "punch", caps);
        if (udpPort != 0) {
            ByteBuffer b = ByteBuffer.allocate(request.length + 8).order(ByteOrder.LITTLE_ENDIAN).put(request);
            b.putInt(16 + 4 + 2, 5); // tag count
            b.put((byte) 0x03).putShort((short) 1).put((byte) 0xF9).putInt((udpPort << 16) | udpPort);
            request = b.array();
        }
        NatCallbackService natCallback = server.getNatCallbackService();
        FakeContext ctx = new FakeContext(new DummySocket(InetAddress.getLoopbackAddress(), 51000), config,
                server.getClientRegistry(), new FileIndex(null)) {
            @Override public NatCallbackService getNatCallbackService() { return natCallback; }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value, request), out);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        while (in.available() > 0) {
            Packet p = Packet.read(in, 65536);
            if (p.opcode() == OpCode.ID_CHANGE.value) return new Login(ctx, p.data(), out);
        }
        throw new AssertionError("no OP_IDCHANGE");
    }

    @Test
    void flagsAreAnnouncedOnlyWhileEnabled() throws Exception {
        int punchFlags = Tag.TCPFLG_LOWID_HOLEPUNCH | Tag.TCPFLG_HOLEPUNCH_EXTENSION;
        assertEquals(0x20000, Tag.TCPFLG_LOWID_HOLEPUNCH);
        assertEquals(0x100000, Tag.TCPFLG_HOLEPUNCH_EXTENSION);

        server = new Server(HOLE_PUNCH, new ClientFactory());
        Login on = login(HOLE_PUNCH, 0x10, EMULE_DEFAULT_SRVCAPS, 4672);
        assertEquals(punchFlags, on.flags() & punchFlags, "to every client while enabled");
        assertEquals(Tag.TCPFLG_NATT, on.flags() & Tag.TCPFLG_NATT);
        assertEquals(20, on.idChange().length, "no Lugdunum opt-in");
        assertEquals(0, on.state().clientId() & 1, "an even LowID: not a callback client");
        assertEquals(1, server.getNatCallbackService().sessionCount(), "a hole-punch target: its 0xF9 UDP port");
        assertFalse(server.getNatCallbackService().isOptedIn(on.state().clientId()));
        // Without 0xF9 nor 0x1000: no session
        login(HOLE_PUNCH, 0x11, EMULE_DEFAULT_SRVCAPS, 0);
        assertEquals(1, server.getNatCallbackService().sessionCount());

        ServerConfig callbackOnly = BASE.withNatTraversal(new NatTraversalConfig(true, false, false));
        Login off = login(callbackOnly, 0x12, EMULE_DEFAULT_SRVCAPS, 4672);
        assertEquals(0, off.flags() & punchFlags, "natHolePunch off");
        ServerConfig withoutCallback = BASE.withNatTraversal(new NatTraversalConfig(false, false, true));
        Login noCallback = login(withoutCallback, 0x13, EMULE_DEFAULT_SRVCAPS, 4672);
        assertEquals(0, noCallback.flags() & punchFlags, "natHolePunch needs natCallback");
        assertEquals(Tag.SERVER_TCP_FLAGS, noCallback.flags());
        assertEquals(1, server.getNatCallbackService().sessionCount(), "neither registers the client");
    }

    @Test
    void optionIsReadFromServerPropertiesAndOffByDefault() {
        assertFalse(ServerConfig.DEFAULT.natTraversal().natHolePunch());
        assertFalse(ServerConfig.DEFAULT.natTraversal().holePunch());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("natHolePunch", " true ");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertTrue(config.natTraversal().natHolePunch());
        assertFalse(config.natTraversal().holePunch(), "needs natCallback");
        props.setProperty("natCallback", "true");
        config = ServerConfig.fromProperties(props);
        assertTrue(config.natTraversal().holePunch());
        assertEquals("true", config.toProperties().get("natHolePunch"));
        assertNotNull(org.jemule.config.ConfigParameter.byKey("natHolePunch"));
        assertTrue(config.withPort(4000).natTraversal().natHolePunch(), "kept by the other with* methods");
    }

    @Test
    void holePunchThroughTheServer() throws Exception {
        server = new Server(HOLE_PUNCH, new ClientFactory());
        Login a = login(HOLE_PUNCH, 0xA, EMULE_DEFAULT_SRVCAPS, 4672);
        Login b = login(HOLE_PUNCH, 0xB, EMULE_DEFAULT_SRVCAPS, 5672);
        int aFrom = a.out().size();
        int bFrom = b.out().size();
        long bId = b.state().clientId();

        byte[] request = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt((int) bId).putShort((short) 4672)
                .array();
        new PacketProcessor().processPacket(a.context(), new Packet(Packet.PROTOCOL_ED2K, (byte) 0x60, request), a.out());

        // The resends follow 1.2 s later: only the first packet is checked
        List<Packet> toA = a.packetsAfter(aFrom);
        List<Packet> toB = b.packetsAfter(bFrom);
        assertEquals(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO, toA.getFirst().opcode());
        assertEquals(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO, toB.getFirst().opcode());
        ByteBuffer info = ByteBuffer.wrap(toA.getFirst().data()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(30, info.remaining());
        assertEquals(0x0100007F, info.getInt(0), "B's IP");
        assertEquals(5672, info.getShort(6) & 0xFFFF, "B's 0xF9 UDP port");
        assertEquals(bId, Integer.toUnsignedLong(info.getInt(25)));

        // A target that is not logged in: 0x62 reason 1
        aFrom = a.out().size();
        request = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(0x7FFF).putShort((short) 4672).array();
        new PacketProcessor().processPacket(a.context(), new Packet(Packet.PROTOCOL_ED2K, (byte) 0x60, request), a.out());
        List<Packet> fail = a.packetsAfter(aFrom).stream()
                .filter(p -> p.opcode() == NatOpcodes.OP_LOWID_HOLEPUNCH_FAIL).toList();
        assertEquals(1, fail.size());
        assertEquals(NatOpcodes.OP_LOWID_HOLEPUNCH_FAIL, fail.getFirst().opcode());
        assertArrayEquals(new byte[]{(byte) 0xFF, 0x7F, 0, 0, 1}, fail.getFirst().data());
    }

    @Test
    void extensionWakeIsReleasedByTheTargetsKeepalive() throws Exception {
        server = new Server(HOLE_PUNCH, new ClientFactory());
        Login a = login(HOLE_PUNCH, 0xA, EMULE_DEFAULT_SRVCAPS | EXT, 4672);
        Login b = login(HOLE_PUNCH, 0xB, EMULE_DEFAULT_SRVCAPS | EXT, 5672);
        assertEquals(24, a.idChange().length, "the extension rides on the Lugdunum opt-in");
        int aFrom = a.out().size();
        int bFrom = b.out().size();
        long bId = b.state().clientId();

        byte[] request = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt((int) bId).putShort((short) 4672)
                .array();
        new PacketProcessor().processPacket(a.context(), new Packet(Packet.PROTOCOL_ED2K, (byte) 0x60, request), a.out());
        assertTrue(a.packetsAfter(aFrom).isEmpty(), "the requester waits");
        List<Packet> toB = b.packetsAfter(bFrom);
        assertEquals(1, toB.size());
        assertEquals(NatOpcodes.OP_NAT_CALLBACKREQUESTED, toB.getFirst().opcode(), "woken with Lugdunum's 0x37");
        bFrom = b.out().size();

        // B's 0x9F, plain to port + 4 from its login IP
        byte[] keepalive = new byte[18];
        keepalive[0] = (byte) 0xE3;
        keepalive[1] = (byte) 0x9F;
        Arrays.fill(keepalive, 2, 18, (byte) 0xB);
        var handleUdp = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, DatagramPacket.class);
        handleUdp.setAccessible(true);
        handleUdp.invoke(server, new SourcesCompatibilityTest.CapturingDatagramSocket(),
                new DatagramPacket(keepalive, keepalive.length, InetAddress.getLoopbackAddress(), 50000));

        List<Packet> toA = a.packetsAfter(aFrom);
        assertFalse(toA.isEmpty());
        assertEquals(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO, toA.getFirst().opcode());
        assertEquals(50000, ByteBuffer.wrap(toA.getFirst().data()).order(ByteOrder.LITTLE_ENDIAN).getShort(6) & 0xFFFF,
                "the port of B's fresh 0x9F");
        Packet infoToB = b.packetsAfter(bFrom).getFirst();
        assertEquals(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO, infoToB.opcode());
        assertEquals(36, infoToB.data().length, "B's own endpoint from its 0x9F");
    }
}
