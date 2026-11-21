package org.jemule.network.ipv6;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.network.EmuleTestSupport.DummySocket;
import org.jemule.network.EmuleTestSupport.FakeContext;
import org.jemule.network.FakeEmuleClient;
import org.jemule.network.Packet;
import org.jemule.network.handler.FoundSource;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.handler.PacketProcessor;
import org.jemule.network.handler.SourceHandler;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.jemule.network.EmuleTestSupport.EMULE_DEFAULT_SRVCAPS;
import static org.jemule.network.EmuleTestSupport.hash;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** IPv6 sources (doc/ipv6.md): login tag 0xAE, OP_IPV6_UPDATE, dial-back, ST_IPV6_STATUS and IPv6 source records. */
class Ipv6SourceServiceTest {

    /** HighID test disabled: every client is LowID, without any connection. */
    static final ServerConfig OFF = new ServerConfig(
            14664, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 200,
            null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 0);
    static final ServerConfig ON = OFF.withIpv6Sources(true);

    static final byte[] ADDRESS = hash("20010db8000000000000000000000001");
    static final byte[] OTHER_ADDRESS = hash("20010db8000000000000000000000002");
    static final byte[] FILE = hash("0123456789ABCDEF0123456789ABCDEF");

    /** Dials answered by a list of verdicts, recording the addresses dialled. */
    static final class ScriptedDialer implements Ipv6SourceService.Dialer {
        final List<InetAddress> dialled = new CopyOnWriteArrayList<>();
        final List<Integer> ports = new CopyOnWriteArrayList<>();
        volatile boolean answer;

        ScriptedDialer(boolean answer) {
            this.answer = answer;
        }

        @Override
        public boolean reached(org.jemule.network.handler.ClientContext context, InetAddress address, int port,
                               byte[] userHash, int timeoutMs) {
            dialled.add(address);
            ports.add(port);
            return answer;
        }
    }

    /** A context using the given service. */
    static final class Ipv6Context extends FakeContext {
        final Ipv6SourceService service;

        Ipv6Context(ServerConfig config, ClientRegistry registry, Ipv6SourceService service) {
            super(new DummySocket(InetAddress.getLoopbackAddress(), 51000), config, registry, new FileIndex(null));
            this.service = service;
        }

        @Override
        public Ipv6SourceService getIpv6SourceService() {
            return service;
        }
    }

    /** OP_LOGINREQUEST with the tags of eMuleQt (01, 11, 20, FB, AE, F9), old tag format as CTag::WriteTagToFile. */
    static byte[] loginRequest(byte[] userHash, int listenPort, byte[] ipv6) {
        return loginRequest(userHash, listenPort, ipv6, EMULE_DEFAULT_SRVCAPS);
    }

    static byte[] loginRequest(byte[] userHash, int listenPort, byte[] ipv6, int caps) {
        byte[] nick = "aMule".getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        b.put(userHash).putInt(0).putShort((short) listenPort).putInt(ipv6 == null ? 5 : 6);
        b.put((byte) 0x02).putShort((short) 1).put((byte) 0x01).putShort((short) nick.length).put(nick);
        b.put((byte) 0x03).putShort((short) 1).put((byte) 0x11).putInt(0x3C);
        b.put((byte) 0x03).putShort((short) 1).put((byte) 0x20).putInt(caps);
        b.put((byte) 0x03).putShort((short) 1).put((byte) 0xFB).putInt(3 << 17 | 2 << 10);
        if (ipv6 != null) b.put((byte) 0x01).putShort((short) 1).put((byte) 0xAE).put(ipv6); // CT_MOD_IP_V6
        b.put((byte) 0x03).putShort((short) 1).put((byte) 0xF9).putInt(0); // eMuleQt also sends 0xF9, unknown here
        return Arrays.copyOf(b.array(), b.position());
    }

    static List<Packet> packets(byte[] stream) throws IOException {
        List<Packet> packets = new ArrayList<>();
        ByteArrayInputStream in = new ByteArrayInputStream(stream);
        while (in.available() > 0) packets.add(Packet.read(in, 1 << 20));
        return packets;
    }

    static List<Packet> withOpcode(List<Packet> packets, OpCode op) {
        return packets.stream().filter(p -> p.opcode() == op.value).toList();
    }

    /** ST_IPV6_STATUS of an OP_SERVERIDENT, -1 when absent. */
    static int status(Packet serverIdent) {
        ByteBuffer b = ByteBuffer.wrap(serverIdent.data()).order(ByteOrder.LITTLE_ENDIAN);
        b.position(16 + 4 + 2);
        for (Tag tag : Tag.readList(b)) {
            if (tag.name().equals(Tag.NAME_IPV6_STATUS)) {
                assertEquals(Tag.TYPE_INT8, tag.type(), "a uint8, read by aMule with IsInt()");
                return (int) tag.unsignedValue();
            }
        }
        return -1;
    }

    static int idChangeFlags(List<Packet> packets) {
        Packet idChange = withOpcode(packets, OpCode.ID_CHANGE).get(0);
        return ByteBuffer.wrap(idChange.data()).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
    }

    /** Waits until {@code out} holds {@code count} OP_SERVERIDENT. */
    static List<Packet> awaitServerIdents(ByteArrayOutputStream out, int count) throws Exception {
        for (int i = 0; i < 500; i++) {
            List<Packet> idents = withOpcode(packets(out.toByteArray()), OpCode.SERVER_IDENT);
            if (idents.size() >= count) return idents;
            Thread.sleep(10);
        }
        throw new AssertionError("expected " + count + " OP_SERVERIDENT");
    }

    static Ipv6Context login(ServerConfig config, ClientRegistry registry, Ipv6SourceService service,
                             byte[] userHash, byte[] ipv6, ByteArrayOutputStream out) throws IOException {
        Ipv6Context ctx = new Ipv6Context(config, registry, service);
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value,
                loginRequest(userHash, 4662, ipv6)), out);
        return ctx;
    }

    static ClientState client(String ip, long id, byte[] userHash) throws IOException {
        ClientState s = new ClientState(InetAddress.getByName(ip), 50000, id, 0, new AtomicLong());
        s.setUserHash(userHash);
        s.setListenPort(4662);
        s.setServerCaps(ClientState.SRVCAP_SUPPORTCRYPT);
        return s;
    }

    /** A client whose IPv6 address the dial-back reached (or not). */
    static ClientState withAddress(ClientState s, byte[] address, boolean reached) {
        assertEquals(Ipv6Session.Action.DIAL, s.ipv6().announce(address, a -> true));
        Ipv6Session.Dial dial = s.ipv6().dialTarget();
        assertTrue(s.ipv6().conclude(dial.generation(), reached));
        return s;
    }

    static void assumeIpv6() {
        assumeTrue(Ipv6SourceService.jvmHasIpv6(), "the JVM runs with java.net.preferIPv4Stack");
    }

    // ---- per-connection state

    @Test
    void anAddressIsHeldThenReachedOrNot() {
        Ipv6Session s = new Ipv6Session();
        assertEquals(0, s.status(), "no address: no tag");
        assertEquals(Ipv6Session.Action.DIAL, s.announce(ADDRESS, Ipv6SourceService::isGlobalUnicast));
        assertTrue(s.readsRecords());
        assertEquals(0x01, s.status(), "held, the dial runs");
        assertNull(s.publishedAddress(), "not published before the dial");
        Ipv6Session.Dial dial = s.dialTarget();
        assertArrayEquals(ADDRESS, dial.address());
        assertNull(s.dialTarget(), "one dial");
        assertTrue(s.conclude(dial.generation(), true));
        assertEquals(0x07, s.status(), "held, reachable, probed");
        assertArrayEquals(ADDRESS, s.publishedAddress());

        assertEquals(Ipv6Session.Action.NONE, s.announce(ADDRESS, a -> true), "the same address keeps its verdict");
        assertEquals(Ipv6Session.Action.DIAL, s.announce(OTHER_ADDRESS, a -> true));
        assertNull(s.publishedAddress(), "a new address is held back until its own dial");
        Ipv6Session.Dial second = s.dialTarget();
        assertFalse(s.conclude(dial.generation(), true), "a stale verdict is ignored");
        assertTrue(s.conclude(second.generation(), false));
        assertEquals(0x05, s.status(), "held, probed, not reachable");
        assertNull(s.publishedAddress());
    }

    @Test
    void zerosWithdrawAndUnusableAddressesAreNotHeld() {
        Ipv6Session s = new Ipv6Session();
        assertEquals(Ipv6Session.Action.NONE, s.announce(new byte[15], a -> true), "not 16 bytes");
        assertFalse(s.readsRecords());
        byte[] linkLocal = hash("fe800000000000000000000000000001");
        assertEquals(Ipv6Session.Action.NONE, s.announce(linkLocal, Ipv6SourceService::isGlobalUnicast));
        assertTrue(s.readsRecords(), "any address tells that the client reads the records");
        assertEquals(0, s.status(), "but an unusable one is not held");

        assertEquals(Ipv6Session.Action.DIAL, s.announce(ADDRESS, a -> true));
        assertEquals(Ipv6Session.Action.REPORT, s.announce(new byte[16], a -> true));
        assertFalse(s.readsRecords(), "zeros withdraw the address and the records");
        assertEquals(0, s.status());
        assertNull(s.dialTarget(), "the pending dial is dropped");
    }

    @Test
    void publishableAddressesAreGlobalUnicast() {
        assertTrue(Ipv6SourceService.isGlobalUnicast(ADDRESS));
        assertTrue(Ipv6SourceService.isGlobalUnicast(hash("2a0104f8000000000000000000000001")));
        for (String bad : List.of("00000000000000000000000000000001", "fe800000000000000000000000000001",
                "fd000000000000000000000000000001", "ff020000000000000000000000000001",
                "00000000000000000000ffff7f000001", "00000000000000000000000000000000")) {
            assertFalse(Ipv6SourceService.isGlobalUnicast(hash(bad)), bad);
        }
    }

    @Test
    void atMostFourDialsPerConnectionAndAFailedAddressIsDialledAgain() {
        Ipv6Session s = new Ipv6Session();
        s.announce(ADDRESS, a -> true);
        s.conclude(s.dialTarget().generation(), false);
        assertEquals(Ipv6Session.Action.DIAL, s.announce(ADDRESS, a -> true),
                "the same address after a failure: dialled again (a pinhole may have opened)");
        s.conclude(s.dialTarget().generation(), false);
        s.announce(OTHER_ADDRESS, a -> true);
        s.conclude(s.dialTarget().generation(), true);
        s.announce(ADDRESS, a -> true);
        s.conclude(s.dialTarget().generation(), false);
        assertEquals(4, s.dials());
        assertEquals(Ipv6Session.Action.REPORT, s.announce(OTHER_ADDRESS, a -> true), "no fifth dial");
        assertNull(s.dialTarget());
        assertEquals(0x05, s.status(), "not published");
        assertNull(s.publishedAddress());
    }

    // ---- login, flags and verdict

    @Test
    void offByDefault() throws Exception {
        assertFalse(ServerConfig.DEFAULT.ipv6Sources());
        assertFalse(ServerConfig.fromProperties(new Properties()).ipv6Sources());
        assertEquals("false", ServerConfig.DEFAULT.toProperties().get("ipv6Sources"));
        Properties on = new Properties();
        on.setProperty("ipv6Sources", "true");
        assertTrue(ServerConfig.fromProperties(on).ipv6Sources());
        assertTrue(ServerConfig.fromProperties(on).withPort(4662).ipv6Sources(), "kept by the with* copies");

        ScriptedDialer dialer = new ScriptedDialer(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Ipv6Context ctx = login(OFF, new ClientRegistry(), new Ipv6SourceService(a -> true, dialer), new byte[16],
                ADDRESS, out);
        List<Packet> packets = packets(out.toByteArray());
        assertEquals(0, idChangeFlags(packets) & (0x4000 | 0x8000 | 0x400000));
        assertEquals(-1, status(withOpcode(packets, OpCode.SERVER_IDENT).get(0)));
        new PacketProcessor().processPacket(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.IPV6_UPDATE.value,
                OTHER_ADDRESS), out);
        Thread.sleep(50);
        assertTrue(dialer.dialled.isEmpty(), "nothing dialled");
        assertFalse(ctx.getState().ipv6().readsRecords());
    }

    @Test
    void offWithoutIpv6InTheJvm() {
        String previous = System.getProperty("java.net.preferIPv4Stack");
        System.setProperty("java.net.preferIPv4Stack", "true");
        try {
            assertFalse(Ipv6SourceService.enabled(ON));
            assertEquals(0, Ipv6SourceService.tcpFlags(ON));
        } finally {
            if (previous == null) System.clearProperty("java.net.preferIPv4Stack");
            else System.setProperty("java.net.preferIPv4Stack", previous);
        }
    }

    @Test
    @Timeout(10)
    void theLoginAddressIsDialledBackAndTheVerdictSentInANewServerIdent() throws Exception {
        assumeIpv6();
        ScriptedDialer dialer = new ScriptedDialer(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] userHash = hash("00112233445566778899AABBCCDDEEFF");
        Ipv6Context ctx = login(ON, new ClientRegistry(), new Ipv6SourceService(Ipv6SourceService::isGlobalUnicast,
                dialer), userHash, ADDRESS, out);

        awaitServerIdents(out, 2);
        List<Packet> packets = packets(out.toByteArray());
        List<Packet> idents = withOpcode(packets, OpCode.SERVER_IDENT);
        assertEquals(0x4000 | 0x8000 | 0x400000, idChangeFlags(packets) & (0x4000 | 0x8000 | 0x400000));
        assertEquals(Tag.SERVER_TCP_FLAGS, idChangeFlags(packets) & ~(0x4000 | 0x8000 | 0x400000));
        assertEquals(0x01, status(idents.get(0)), "the login's OP_SERVERIDENT: held, not yet dialled");
        assertTrue(packets.indexOf(withOpcode(packets, OpCode.ID_CHANGE).get(0))
                < packets.indexOf(idents.get(1)), "the verdict follows the login answers");
        assertEquals(0x07, status(idents.get(1)), "reached");
        assertEquals(List.of(InetAddress.getByAddress(ADDRESS)), dialer.dialled);
        assertEquals(List.of(4662), dialer.ports, "the login TCP port");
        assertArrayEquals(ADDRESS, ctx.getState().ipv6().publishedAddress());
    }

    @Test
    @Timeout(10)
    void anUpdateIsDialledAsAtLoginAndZerosWithdraw() throws Exception {
        assumeIpv6();
        ScriptedDialer dialer = new ScriptedDialer(false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // aMule sends an address nothing proved yet only in OP_IPV6_UPDATE, after OP_IDCHANGE
        Ipv6Context ctx = login(ON, new ClientRegistry(), new Ipv6SourceService(Ipv6SourceService::isGlobalUnicast,
                dialer), new byte[16], null, out);
        assertEquals(-1, status(awaitServerIdents(out, 1).get(0)));
        assertFalse(ctx.getState().ipv6().readsRecords());

        PacketProcessor processor = new PacketProcessor();
        processor.processPacket(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.IPV6_UPDATE.value, ADDRESS), out);
        assertTrue(ctx.getState().ipv6().readsRecords(), "a non-zero OP_IPV6_UPDATE makes the client a reader");
        List<Packet> idents = awaitServerIdents(out, 3);
        assertEquals(0x01, status(idents.get(1)), "held, the dial runs");
        assertEquals(0x05, status(idents.get(2)), "dialled, not reached");
        assertNull(ctx.getState().ipv6().publishedAddress());

        dialer.answer = true;
        processor.processPacket(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.IPV6_UPDATE.value, ADDRESS), out);
        idents = awaitServerIdents(out, 5);
        assertEquals(0x01, status(idents.get(3)), "dialled again: back to held");
        assertEquals(0x07, status(idents.get(4)), "the same address dialled again after a failure");
        assertEquals(2, dialer.dialled.size());

        processor.processPacket(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.IPV6_UPDATE.value, new byte[16]), out);
        assertEquals(-1, status(awaitServerIdents(out, 6).get(5)), "withdrawn: no status");
        assertFalse(ctx.getState().ipv6().readsRecords());
        assertNull(ctx.getState().ipv6().publishedAddress());
        assertEquals(2, dialer.dialled.size());
    }

    // ---- the dial-back itself, with FirewallChecker on ::1

    /** A client listening on [::1], answering OP_HELLO with the given user hash. */
    static FakeEmuleClient ipv6Client(byte[] userHash, FakeEmuleClient.Crypt crypt) {
        try {
            return new FakeEmuleClient(InetAddress.getByName("::1"), userHash, crypt);
        } catch (IOException e) {
            assumeTrue(false, "no IPv6 loopback: " + e.getMessage());
            return null;
        }
    }

    @Test
    @Timeout(10)
    void theDialBackChecksTheUserHashOverIpv6() throws Exception {
        assumeIpv6();
        byte[] userHash = hash("00112233445566778899AABBCCDDEEFF");
        Ipv6Context ctx = new Ipv6Context(ON, new ClientRegistry(), Ipv6SourceService.DEFAULT);
        InetAddress loopback = InetAddress.getByName("::1");
        try (FakeEmuleClient client = ipv6Client(userHash, FakeEmuleClient.Crypt.NONE)) {
            assertTrue(Ipv6SourceService.FIREWALL_CHECKER.reached(ctx, loopback, client.port(), userHash, 2000));
        }
        try (FakeEmuleClient client = ipv6Client(hash("FFEEDDCCBBAA99887766554433221100"), FakeEmuleClient.Crypt.NONE)) {
            assertFalse(Ipv6SourceService.FIREWALL_CHECKER.reached(ctx, loopback, client.port(), userHash, 2000),
                    "answered by another client");
        }
    }

    @Test
    @Timeout(15)
    void aLoginOnIpv6LoopbackIsReachedByTheRealDialBack() throws Exception {
        assumeIpv6();
        byte[] userHash = hash("00112233445566778899AABBCCDDEEFF");
        try (FakeEmuleClient client = ipv6Client(userHash, FakeEmuleClient.Crypt.SUPPORTED)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Ipv6Context ctx = loginOnIpv6Loopback(userHash, client.port(), EMULE_DEFAULT_SRVCAPS, out);
            assertEquals(0x07, status(awaitServerIdents(out, 2).get(1)));
            assertArrayEquals(InetAddress.getByName("::1").getAddress(), ctx.getState().ipv6().publishedAddress());
            assertEquals(List.of("obfuscated"), client.connections());
        }
    }

    /**
     * #124: a client that requires the crypt layer refuses a plain connection over IPv6 (aMule accepts one only as
     * the login's test, from the server's IPv4 address); the obfuscated dial reaches it.
     */
    @Test
    @Timeout(15)
    void aClientRequiringTheCryptLayerIsReachedOverIpv6() throws Exception {
        assumeIpv6();
        byte[] userHash = hash("00112233445566778899AABBCCDDEEFF");
        int requires = EMULE_DEFAULT_SRVCAPS | ClientState.SRVCAP_REQUESTCRYPT | ClientState.SRVCAP_REQUIRECRYPT;
        try (FakeEmuleClient client = ipv6Client(userHash, FakeEmuleClient.Crypt.REQUIRED)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            loginOnIpv6Loopback(userHash, client.port(), requires, out);
            assertEquals(0x07, status(awaitServerIdents(out, 2).get(1)));
            assertEquals(List.of("obfuscated"), client.connections());
        }
    }

    private static Ipv6Context loginOnIpv6Loopback(byte[] userHash, int port, int caps, ByteArrayOutputStream out)
            throws Exception {
        Ipv6Context ctx = new Ipv6Context(ON, new ClientRegistry(),
                new Ipv6SourceService(a -> true, Ipv6SourceService.FIREWALL_CHECKER));
        byte[] request = loginRequest(userHash, port, InetAddress.getByName("::1").getAddress(), caps);
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value, request), out);
        return ctx;
    }

    // ---- source records

    /** Records of an OP_FOUNDSOURCES(_OBFU) payload, parsed like aMule's ParseServerSourceRecord. */
    record Parsed(long id, int port, int crypt, byte[] userHash, byte[] ipv6) {}

    static List<Parsed> parse(byte[] payload, boolean obfu, boolean readsIpv6) {
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        b.position(16);
        int count = b.get() & 0xFF;
        List<Parsed> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = Integer.toUnsignedLong(b.getInt());
            int port = Short.toUnsignedInt(b.getShort());
            int crypt = 0;
            byte[] userHash = null;
            if (obfu) {
                crypt = b.get() & 0xFF;
                if ((crypt & 0x80) != 0) {
                    userHash = new byte[16];
                    b.get(userHash);
                }
            }
            byte[] ipv6 = null;
            if (readsIpv6 && id == Ipv6SourceService.SENTINEL_ID) {
                ipv6 = new byte[16];
                b.get(ipv6);
            }
            records.add(new Parsed(id, port, crypt, userHash, ipv6));
        }
        assertFalse(b.hasRemaining(), "every byte belongs to a record");
        return records;
    }

    /** OP_GETSOURCES(_OBFU) of FILE by the requester, whose answer payloads are returned. */
    static List<Packet> getSources(Ipv6Context ctx, boolean obfu) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] request = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put(FILE).putInt(1000).array();
        new SourceHandler().handleGetSources(ctx, new Packet(Packet.PROTOCOL_ED2K,
                obfu ? OpCode.GET_SOURCES_OBFU.value : OpCode.GET_SOURCES.value, request), out);
        return packets(out.toByteArray());
    }

    /** The requester and three sources of FILE: a LowID reached on IPv6, a LowID not reached, a HighID reached. */
    static Ipv6Context sourcesSetup(ServerConfig config, ClientState requester) throws IOException {
        ClientRegistry registry = new ClientRegistry();
        Ipv6Context ctx = new Ipv6Context(config, registry, Ipv6SourceService.DEFAULT);
        ctx.setState(requester);
        ClientState lowReached = withAddress(client("10.0.0.1", 5, hash("11111111111111111111111111111111")), ADDRESS, true);
        ClientState lowFailed = withAddress(client("10.0.0.2", 6, hash("22222222222222222222222222222222")), OTHER_ADDRESS, false);
        ClientState highReached = withAddress(client("91.216.110.42", 0x2A6ED85BL, hash("33333333333333333333333333333333")),
                hash("20010db8000000000000000000000003"), true);
        org.jemule.core.FileMetadata file = new org.jemule.core.FileMetadata(
                org.jemule.network.handler.HandlerUtils.bytesToHashString(FILE), "file.avi", 1000, "Video");
        for (ClientState s : List.of(requester, lowReached, lowFailed, highReached)) {
            file.sources().put(FileIndex.sourceKey(s), s);
        }
        ctx.getFileIndex().addFile(file);
        return ctx;
    }

    static ClientState lowIdRequester(boolean announced) throws IOException {
        ClientState requester = client("10.0.0.9", 9, hash("99999999999999999999999999999999"));
        if (announced) requester.ipv6().announce(hash("20010db8000000000000000000000009"), a -> true);
        return requester;
    }

    @Test
    void aLowIdRequesterReadingThemGetsTheReachedLowIdSourcesAsIpv6Records() throws Exception {
        assumeIpv6();
        Ipv6Context ctx = sourcesSetup(ON, lowIdRequester(true));
        List<Parsed> plain = parse(withOpcode(getSources(ctx, false), OpCode.FOUND_SOURCES).get(0).data(), false, true);
        assertEquals(4, plain.size());
        List<Parsed> ipv6 = plain.stream().filter(r -> r.ipv6() != null).toList();
        assertEquals(1, ipv6.size(), "only the LowID source the dial-back reached");
        assertArrayEquals(ADDRESS, ipv6.get(0).ipv6());
        assertEquals(4662, ipv6.get(0).port(), "its login TCP port, the one dialled");
        assertTrue(plain.stream().anyMatch(r -> r.id() == 6), "the unreached LowID keeps its LowID");
        assertTrue(plain.stream().anyMatch(r -> r.id() == 0x2A6ED85BL), "the HighID keeps its IPv4");
        assertTrue(plain.stream().anyMatch(r -> r.id() == 9), "the requester's own record stays classic");

        List<Parsed> obfu = parse(withOpcode(getSources(ctx, true), OpCode.SOURCES_RESULT_OBFU).get(0).data(), true, true);
        Parsed record = obfu.stream().filter(r -> r.ipv6() != null).findFirst().orElseThrow();
        assertEquals(0x81, record.crypt(), "crypt options, user hash flag");
        assertArrayEquals(hash("11111111111111111111111111111111"), record.userHash(), "user hash before the address");
        assertArrayEquals(ADDRESS, record.ipv6(), "the address last");
    }

    @Test
    void noIpv6RecordForOtherRequesters() throws Exception {
        assumeIpv6();
        // Not announced (eMule), HighID (reaches a LowID by callback), another server, or the option off
        ClientState highId = client("91.216.110.43", 0x2B6ED85BL, hash("99999999999999999999999999999998"));
        highId.ipv6().announce(hash("20010db8000000000000000000000008"), a -> true);
        Ipv6Context server = sourcesSetup(ON, lowIdRequester(true));
        server.setFromServer(true);
        for (Ipv6Context ctx : List.of(sourcesSetup(ON, lowIdRequester(false)), sourcesSetup(ON, highId), server,
                sourcesSetup(OFF, lowIdRequester(true)))) {
            for (boolean obfu : List.of(false, true)) {
                Packet answer = getSources(ctx, obfu).get(0);
                List<Parsed> records = parse(answer.data(), obfu, false);
                assertEquals(4, records.size());
                assertTrue(records.stream().noneMatch(r -> r.id() == Ipv6SourceService.SENTINEL_ID));
            }
        }
    }

    @Test
    void neverInTheUdpAnswerOfOtherServersClients() throws Exception {
        ClientState lowReached = withAddress(client("10.0.0.1", 5, hash("11111111111111111111111111111111")), ADDRESS, true);
        byte[] block = SourceHandler.encodeFoundSources(FILE, List.of(lowReached));
        assertEquals(16 + 1 + 6, block.length, "OP_GLOBFOUNDSOURCES: the LowID, never the sentinel");
        assertEquals(5, ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).getInt(17));
        assertFalse(FoundSource.of(lowReached).id() == Ipv6SourceService.SENTINEL_ID);
    }
}
