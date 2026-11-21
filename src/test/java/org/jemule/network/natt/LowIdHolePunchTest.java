package org.jemule.network.natt;

import org.jemule.core.ClientState;
import org.jemule.network.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.jemule.network.natt.LowIdHolePunch.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ed2k-server's LowID hole punch and its extension (aMule master a6d6290be0, docs/NAT_T_Protocol.md, "ed2k-server
 * LowID hole punch" and "Hole-punch extension (ed2k-server 0.9.85)"), with the cases of ed2k-server's
 * {@code holepunch.rs} tests.
 */
class LowIdHolePunchTest {

    static final int NATT = ClientState.SRVCAP_NATTRAVERSAL;
    static final int EXT = ClientState.SRVCAP_HOLEPUNCH_EXTENSION | ClientState.SRVCAP_NATTRAVERSAL;
    static final int SUPPORTS = 0x200;
    static final int REQUESTS = 0x400;

    /** A packet sent on one client's TCP connection. */
    record Sent(ClientState to, Packet packet) {
    }

    record Timer(long at, Runnable task) {
    }

    final AtomicLong now = new AtomicLong(1_000_000);
    final AtomicBoolean callback = new AtomicBoolean(true);
    final AtomicBoolean holePunch = new AtomicBoolean(true);
    final List<Sent> sent = new ArrayList<>();
    final List<Timer> timers = new ArrayList<>();
    final Map<Long, ClientState> clients = new HashMap<>();
    NatCallbackService service;

    @BeforeEach
    void setup() {
        service = new NatCallbackService(callback::get, () -> false, holePunch::get, now::get,
                new NatCallbackService.Transport() {
                    @Override public void sendTcp(long clientId, Packet packet) { sent.add(new Sent(clients.get(clientId), packet)); }
                    @Override public void sendTcp(ClientState client, Packet packet) { sent.add(new Sent(client, packet)); }
                    @Override public void sendUdp(DatagramSocket socket, UdpEndpoint to, byte[] datagram) { }
                }, new KeepaliveToken(), clients::get, (task, delayMs) -> timers.add(new Timer(now.get() + delayMs, task)));
    }

    /** Moves the clock {@code ms} on, running the timers due on the way, in order. */
    void advance(long ms) {
        long end = now.get() + ms;
        while (true) {
            Timer next = timers.stream().filter(t -> t.at() <= end).min(Comparator.comparingLong(Timer::at)).orElse(null);
            if (next == null) break;
            timers.remove(next);
            now.set(Math.max(now.get(), next.at()));
            next.task().run();
        }
        now.set(end);
    }

    static InetAddress ip(String text) throws Exception {
        return InetAddress.getByName(text);
    }

    static byte[] hash(int b) {
        byte[] h = new byte[16];
        Arrays.fill(h, (byte) b);
        return h;
    }

    /** A logged-in client; {@code udpPort} is the client UDP port of its 0xF9, 0 for none. */
    ClientState client(int hashByte, long id, String address, int caps, int udpPort) throws Exception {
        ClientState c = new ClientState(ip(address), 50000, id, now.get(), new AtomicLong());
        c.setUserHash(hash(hashByte));
        c.setServerCaps(caps);
        c.setListenPort(4662);
        c.setEmuleUdpPorts((udpPort << 16) | udpPort);
        clients.put(id, c);
        return c;
    }

    /** A LowID registered as LoginHandler does: for the callback with 0x1000, else for the hole punch with a 0xF9 port. */
    ClientState lowId(int hashByte, long id, String address, int caps, int udpPort) throws Exception {
        ClientState c = client(hashByte, id, address, caps, udpPort);
        if ((caps & NATT) != 0) service.register(c);
        else if (udpPort != 0) service.register(c, false);
        return c;
    }

    void punch(ClientState requester, long target, int udpPort) {
        service.handleHolePunchRequest(requester,
                ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt((int) target).putShort((short) udpPort).array());
    }

    List<Packet> to(ClientState c) {
        return sent.stream().filter(s -> s.to() == c).map(Sent::packet).toList();
    }

    static List<Byte> opcodes(List<Packet> packets) {
        return packets.stream().map(Packet::opcode).toList();
    }

    static ByteBuffer le(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** The 0x62 reason sent to {@code c}, which must be its only packet. */
    int failReason(ClientState c, long target) {
        List<Packet> packets = to(c);
        assertEquals(1, packets.size(), "one packet: " + opcodes(packets));
        Packet p = packets.getFirst();
        assertEquals(NatOpcodes.OP_LOWID_HOLEPUNCH_FAIL, p.opcode());
        assertEquals(5, p.data().length, "<target ID 4><reason 1>");
        assertEquals(target, Integer.toUnsignedLong(le(p.data()).getInt(0)));
        return p.data()[4];
    }

    @Test
    void infoWireFormat() throws Exception {
        ClientState peer = client(7, 13, "9.9.9.9", SUPPORTS | REQUESTS, 0);
        byte[] info = LowIdHolePunch.info(new Endpoint(ClientState.ipToLong(ip("1.2.3.4")), 4672), peer, 0, 0x01020304,
                null);
        assertEquals(30, info.length);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Arrays.copyOfRange(info, 0, 4), "IP in network order");
        assertArrayEquals(new byte[]{0x36, 0x12}, Arrays.copyOfRange(info, 4, 6), "TCP port 4662 LE");
        assertArrayEquals(new byte[]{0x40, 0x12}, Arrays.copyOfRange(info, 6, 8), "UDP port 4672 LE");
        assertArrayEquals(hash(7), Arrays.copyOfRange(info, 8, 24));
        assertEquals(0, info[24], "role");
        assertArrayEquals(new byte[]{4, 3, 2, 1}, Arrays.copyOfRange(info, 25, 29), "peer ID LE");
        assertEquals(0x03, info[29], "peer crypt: (CT_SERVER_FLAGS >> 9) & 7");

        info = LowIdHolePunch.info(new Endpoint(ClientState.ipToLong(ip("1.2.3.4")), 4672), peer, 1, 1,
                new UdpEndpoint(ip("9.8.7.6"), 0x1234));
        assertEquals(36, info.length);
        assertEquals(1, info[24]);
        assertArrayEquals(new byte[]{9, 8, 7, 6, 0x34, 0x12}, Arrays.copyOfRange(info, 30, 36), "own IP, own UDP port LE");
    }

    @Test
    void bothSidesGetTheOthersEndpoint() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", SUPPORTS, 4672);
        ClientState b = lowId(0xB, 14, "9.9.9.9", SUPPORTS | REQUESTS, 5672);
        punch(a, 14, 4672);
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(a)));
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(b)));
        byte[] toA = to(a).getFirst().data();
        byte[] toB = to(b).getFirst().data();
        assertEquals(30, toA.length, "no 0x9F from A: no own endpoint");
        assertEquals(30, toB.length);
        // A is told B's login IP, its announced UDP port, hash, ID 14 and crypt options; A, the lower ID, starts
        assertArrayEquals(new byte[]{9, 9, 9, 9}, Arrays.copyOfRange(toA, 0, 4));
        assertEquals(4662, le(toA).getShort(4) & 0xFFFF);
        assertEquals(5672, le(toA).getShort(6) & 0xFFFF);
        assertArrayEquals(hash(0xB), Arrays.copyOfRange(toA, 8, 24));
        assertEquals(ROLE_FIRST, toA[24]);
        assertEquals(14, le(toA).getInt(25));
        assertEquals(0x03, toA[29]);
        // B is told A's
        assertArrayEquals(new byte[]{5, 6, 7, 8}, Arrays.copyOfRange(toB, 0, 4));
        assertEquals(4672, le(toB).getShort(6) & 0xFFFF);
        assertArrayEquals(hash(0xA), Arrays.copyOfRange(toB, 8, 24));
        assertEquals(ROLE_SECOND, toB[24]);
        assertEquals(12, le(toB).getInt(25));
        assertEquals(0x01, toB[29]);
        assertEquals(1, service.holePunchCounters().get("info"));
    }

    @Test
    void theLowerIdStartsWhicheverSideAsks() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        ClientState b = lowId(0xB, 14, "9.9.9.9", 0, 5672);
        punch(b, 12, 5672);
        assertEquals(ROLE_SECOND, to(b).getFirst().data()[24], "the requester has the higher ID");
        assertEquals(ROLE_FIRST, to(a).getFirst().data()[24]);
    }

    @Test
    void aFreshKeepaliveGivesThePortAndTheOwnEndpoint() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        ClientState b = lowId(0xB, 14, "9.9.9.9", 0, 5672);
        // B's 0x9F as its NAT mapped it
        assertTrue(service.handleKeepalive(hash(0xB), ip("9.9.9.9"), 40002, false));
        punch(a, 14, 24000);
        byte[] toA = to(a).getFirst().data();
        byte[] toB = to(b).getFirst().data();
        assertEquals(40002, le(toA).getShort(6) & 0xFFFF, "B's 0x9F port, not its announced one");
        assertEquals(30, toA.length, "A sent no 0x9F");
        assertEquals(24000, le(toB).getShort(6) & 0xFFFF, "A's 0x60 port replaces its 0xF9 one");
        assertEquals(36, toB.length, "B's own endpoint from its 0x9F");
        assertArrayEquals(new byte[]{9, 9, 9, 9, (byte) 0x42, (byte) 0x9C}, Arrays.copyOfRange(toB, 30, 36));

        // Ten minutes later the 0x9F is stale: the announced port again, and no own endpoint
        advance(NatCallbackService.KEEPALIVE_TTL_MS + 1);
        sent.clear();
        punch(a, 14, 24000);
        assertEquals(5672, le(to(a).getFirst().data()).getShort(6) & 0xFFFF);
        assertEquals(30, to(b).getFirst().data().length);
    }

    @Test
    void aProvenKeepaliveFromAnotherIpGivesThatAddress() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        ClientState b = lowId(0xB, 14, "9.9.9.9", 0, 5672);
        // As a 0x9F proven with the session token from another IPv4 (carrier NAT) records it
        service.onKeepalive(14, ip("198.51.100.7"), 41000);
        punch(a, 14, 4672);
        byte[] toA = to(a).getFirst().data();
        assertArrayEquals(new byte[]{(byte) 198, 51, 100, 7}, Arrays.copyOfRange(toA, 0, 4));
        assertEquals(41000, le(toA).getShort(6) & 0xFFFF);
        assertArrayEquals(new byte[]{(byte) 198, 51, 100, 7}, Arrays.copyOfRange(to(b).getFirst().data(), 30, 34));
    }

    @Test
    void failureReasons() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        punch(a, 999, 4672);
        assertEquals(FAIL_TARGET_NOT_CONNECTED, failReason(a, 999));

        sent.clear();
        long highId = ClientState.ipToLong(ip("80.1.2.3"));
        client(0xC, highId, "80.1.2.3", 0, 4672);
        punch(a, highId, 4672);
        assertEquals(FAIL_TARGET_IS_HIGHID, failReason(a, highId));

        sent.clear();
        punch(a, 12, 4672);
        assertEquals(FAIL_BAD_REQUEST, failReason(a, 12), "a punch to itself");

        // A stock LowID: no 0xF9 port, not registered
        sent.clear();
        ClientState stock = lowId(0xD, 16, "9.9.9.9", 0, 0);
        punch(a, 16, 4672);
        assertEquals(FAIL_TARGET_NO_UDP, failReason(a, 16));
        assertTrue(to(stock).isEmpty());

        // A callback client without a UDP port nor a 0x9F
        sent.clear();
        lowId(0xE, 17, "9.9.9.8", NATT, 0);
        punch(a, 17, 4672);
        assertEquals(FAIL_TARGET_NO_UDP, failReason(a, 17));

        // A session over IPv6 has no IPv4 to give
        sent.clear();
        ClientState v6 = lowId(0xF, 18, "2001:db8::5", 0, 5672);
        punch(a, 18, 4672);
        assertEquals(FAIL_NO_IPV4, failReason(a, 18), "nor is an IPv4 requester told an IPv6 target's 0.0.0.0");
        sent.clear();
        punch(v6, 12, 5672);
        assertEquals(FAIL_NO_IPV4, failReason(v6, 12), "an IPv6 requester is not handed 0.0.0.0");
        // Once its 0x9F gave an IPv4, the pair is punched to that address
        sent.clear();
        service.onKeepalive(18, ip("5.6.7.10"), 42000);
        punch(v6, 12, 5672);
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(v6)));

        // A HighID requester needs no punch
        sent.clear();
        ClientState high = client(0x10, highId, "80.1.2.3", 0, 4672);
        punch(high, 12, 4672);
        assertEquals(FAIL_BAD_REQUEST, failReason(high, 12));
        Map<String, Long> counters = service.holePunchCounters();
        assertEquals(1, counters.get("fail_not_connected"));
        assertEquals(1, counters.get("fail_highid"));
        assertEquals(2, counters.get("fail_bad_request"));
        assertEquals(2, counters.get("fail_no_udp"));
        assertEquals(2, counters.get("fail_no_ipv4"));
    }

    @Test
    void aTargetThatSentA9FWithoutA0xF9PortCanBePunched() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        ClientState b = lowId(0xB, 13, "9.9.9.9", NATT, 0);
        assertTrue(service.handleKeepalive(hash(0xB), ip("9.9.9.9"), 40002, false));
        punch(a, 13, 4672);
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(b)));
        // Its port stays known once the 0x9F is stale
        advance(NatCallbackService.KEEPALIVE_TTL_MS + 1);
        sent.clear();
        punch(a, 13, 4672);
        assertEquals(40002, le(to(a).getFirst().data()).getShort(6) & 0xFFFF);
    }

    @Test
    void aRequesterWithoutA0xF9PortIsRegisteredByIts0x60() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 0);
        ClientState b = lowId(0xB, 14, "9.9.9.9", 0, 5672);
        assertEquals(1, service.sessionCount());
        punch(a, 14, 4672);
        assertEquals(4672, le(to(b).getFirst().data()).getShort(6) & 0xFFFF);
        assertEquals(2, service.sessionCount());
        // It is a target now, but not a callback client
        sent.clear();
        punch(b, 12, 5672);
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(a)));
        assertFalse(service.isOptedIn(12));

        // With no port at all, its 0x60 cannot describe it
        ClientState c = lowId(0xC, 16, "9.9.9.7", 0, 0);
        sent.clear();
        punch(c, 14, 0);
        assertEquals(FAIL_BAD_REQUEST, failReason(c, 14));
    }

    @Test
    void theAnswerIsSentAgainAt1200And3000Ms() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        ClientState b = lowId(0xB, 14, "9.9.9.9", 0, 5672);
        punch(a, 14, 4672);
        assertEquals(1, to(a).size());
        advance(1_199);
        assertEquals(1, to(a).size());
        advance(1);
        assertEquals(2, to(a).size());
        assertEquals(2, to(b).size());
        // A repeated 0x60 is answered at once, without a second series of resends
        punch(a, 14, 4672);
        assertEquals(3, to(a).size());
        advance(1_800);
        assertEquals(4, to(a).size());
        assertEquals(4, to(b).size());
        advance(10_000);
        assertEquals(4, to(a).size());
        assertEquals(0, service.holePunch().pendingCount());
    }

    @Test
    void aTargetGoneBeforeTheResendGetsTheRequesterA0x62() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        ClientState b = lowId(0xB, 14, "9.9.9.9", 0, 5672);
        punch(a, 14, 4672);
        service.unregister(b);
        clients.remove(14L);
        advance(1_200);
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO, NatOpcodes.OP_LOWID_HOLEPUNCH_FAIL), opcodes(to(a)));
        assertEquals(FAIL_TARGET_NOT_CONNECTED, to(a).get(1).data()[4]);
        // A requester gone: nothing more
        service.unregister(a);
        advance(5_000);
        assertEquals(2, to(a).size());
    }

    @Test
    void extensionPairWaitsForTheTargetsKeepalive() throws Exception {
        ClientState a = lowId(0xA, 11, "5.6.7.8", EXT | SUPPORTS, 4672);
        ClientState b = lowId(0xB, 13, "9.9.9.9", EXT | SUPPORTS | REQUESTS, 5672);
        punch(a, 13, 4672);
        assertTrue(to(a).isEmpty(), "the requester waits");
        List<Packet> woke = to(b);
        assertEquals(List.of(NatOpcodes.OP_NAT_CALLBACKREQUESTED), opcodes(woke));
        // Lugdunum's 0x37, byte-identical: A's endpoint, ID, crypt with 0x80, A's hash (27 bytes)
        byte[] wake = woke.getFirst().data();
        assertEquals(27, wake.length);
        assertArrayEquals(new byte[]{5, 6, 7, 8, 0x40, 0x12, 11, 0, 0, 0, (byte) 0x81}, Arrays.copyOf(wake, 11));
        assertArrayEquals(hash(0xA), Arrays.copyOfRange(wake, 11, 27));

        // A repeated 0x60 while waiting adds nothing
        punch(a, 13, 4672);
        assertEquals(1, sent.size());

        // B's 0x9F, as its NAT mapped it, releases the 0x61 with that fresh port
        advance(500);
        assertTrue(service.handleKeepalive(hash(0xB), ip("9.9.9.9"), 50000, true));
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(a)));
        byte[] toA = to(a).getFirst().data();
        assertEquals(30, toA.length);
        assertEquals(50000, le(toA).getShort(6) & 0xFFFF);
        assertEquals(0x03, toA[29], "B supports and requests");
        byte[] toB = to(b).get(1).data();
        assertEquals(36, toB.length, "B's own endpoint from the 0x9F");
        assertEquals(0x01, toB[29], "A supports");
        assertArrayEquals(new byte[]{9, 9, 9, 9, 0x50, (byte) 0xC3}, Arrays.copyOfRange(toB, 30, 36));
        // Another 0x9F releases nothing more; the timeout neither
        assertTrue(service.handleKeepalive(hash(0xB), ip("9.9.9.9"), 50000, true));
        assertEquals(1, to(a).size());
        // The resends, at +1.2 s and +3 s after the 0x61
        advance(1_200);
        assertEquals(2, to(a).size());
        advance(1_800);
        assertEquals(3, to(a).size());
        advance(10_000);
        assertEquals(3, to(a).size());
        Map<String, Long> counters = service.holePunchCounters();
        assertEquals(1, counters.get("ext_started"));
        assertEquals(1, counters.get("ext_fresh"));
        assertEquals(0, counters.get("ext_timeout"));
        assertEquals(0, service.holePunch().pendingCount());
    }

    @Test
    void withoutTheKeepaliveThePunchGoesAheadAfterTheWait() throws Exception {
        ClientState a = lowId(0xA, 11, "5.6.7.8", EXT, 4672);
        ClientState b = lowId(0xB, 13, "9.9.9.9", EXT, 5672);
        punch(a, 13, 4672);
        assertEquals(List.of(NatOpcodes.OP_NAT_CALLBACKREQUESTED), opcodes(to(b)));
        assertEquals(11, to(b).getFirst().data().length, "no hash without the crypt layer");
        advance(2_999);
        assertTrue(to(a).isEmpty());
        advance(1);
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(a)));
        assertEquals(5672, le(to(a).getFirst().data()).getShort(6) & 0xFFFF, "the announced port");
        assertEquals(2, to(b).size());
        assertEquals(1, service.holePunchCounters().get("ext_timeout"));
    }

    @Test
    void anExtensionTargetWithoutAPortGetsOneFromItsKeepalive() throws Exception {
        ClientState a = lowId(0xA, 11, "5.6.7.8", EXT, 4672);
        ClientState b = lowId(0xB, 13, "9.9.9.9", EXT, 0);
        punch(a, 13, 4672);
        assertEquals(List.of(NatOpcodes.OP_NAT_CALLBACKREQUESTED), opcodes(to(b)), "woken although no port is known");
        assertTrue(service.handleKeepalive(hash(0xB), ip("9.9.9.9"), 50000, false));
        assertEquals(50000, le(to(a).getFirst().data()).getShort(6) & 0xFFFF);

        // Silent and without a port: 0x62 reason 4 after the wait, where it always would
        ClientState c = lowId(0xC, 15, "9.9.9.8", EXT, 0);
        sent.clear();
        advance(10_000);
        sent.clear();
        punch(a, 15, 4672);
        assertEquals(List.of(NatOpcodes.OP_NAT_CALLBACKREQUESTED), opcodes(to(c)));
        advance(3_000);
        assertEquals(FAIL_TARGET_NO_UDP, failReason(a, 15));
        advance(10_000);
        assertEquals(1, to(a).size(), "no resend after a 0x62");
    }

    @Test
    void aPairWithoutTheExtensionOnBothSidesIsAnsweredAtOnce() throws Exception {
        int[][] pairs = {{EXT, NATT}, {NATT, EXT}, {0, 0}, {EXT, 0}, {0, EXT}};
        for (int[] pair : pairs) {
            setup();
            ClientState a = lowId(0xA, 11, "5.6.7.8", pair[0], 4672);
            ClientState b = lowId(0xB, 13, "9.9.9.9", pair[1], 5672);
            punch(a, 13, 4672);
            String flags = Integer.toHexString(pair[0]) + " " + Integer.toHexString(pair[1]);
            assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(a)), flags);
            assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(b)), flags);
        }
    }

    @Test
    void cryptOptionsThatRuleOutTheWakeLeaveThePunch() throws Exception {
        ClientState a = lowId(0xA, 11, "5.6.7.8", EXT | 0x200 | 0x400 | 0x800, 4672);
        ClientState b = lowId(0xB, 13, "9.9.9.9", EXT, 5672);
        punch(a, 13, 4672);
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(a)));
        assertEquals(List.of(NatOpcodes.OP_LOWID_HOLEPUNCH_INFO), opcodes(to(b)));
    }

    @Test
    void disabledOrWithoutTheCallbackNothingIsAnswered() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        lowId(0xB, 14, "9.9.9.9", 0, 5672);
        holePunch.set(false);
        punch(a, 14, 4672);
        assertTrue(sent.isEmpty());
        assertEquals(0, service.holePunchCounters().get("requests"));
        // Short payloads are ignored
        holePunch.set(true);
        service.handleHolePunchRequest(a, new byte[5]);
        assertTrue(sent.isEmpty());
    }

    @Test
    void requestsAreLimitedPerRequesterAndPerTarget() throws Exception {
        ClientState a = lowId(0xA, 12, "5.6.7.8", 0, 4672);
        lowId(0xB, 14, "9.9.9.9", 0, 5672);
        for (int i = 0; i < NatCallbackService.REQUESTER_BURST; i++) punch(a, 999, 4672);
        assertEquals(NatCallbackService.REQUESTER_BURST, to(a).size());
        punch(a, 999, 4672);
        assertEquals(NatCallbackService.REQUESTER_BURST, to(a).size(), "over the burst: dropped");
        advance(NatCallbackService.REQUESTER_INTERVAL_MS);
        punch(a, 999, 4672);
        assertEquals(NatCallbackService.REQUESTER_BURST + 1, to(a).size(), "one a second");

        // Many requesters, one target
        sent.clear();
        ClientState target = lowId(0xC, 16, "9.9.9.8", 0, 5672);
        for (int i = 0; i <= NatCallbackService.TARGET_BURST; i++) {
            ClientState r = lowId(0x20 + i, 100 + 2L * i, "10.0.0." + (i + 1), 0, 4672);
            punch(r, 16, 4672);
        }
        assertEquals(NatCallbackService.TARGET_BURST, to(target).size());
        assertEquals(2, service.holePunchCounters().get("rate_limited"));
    }

    @Test
    void holePunchSessionsDoNotTakeTheCallback() throws Exception {
        lowId(0xA, 12, "5.6.7.8", 0, 4672);
        lowId(0xB, 13, "9.9.9.9", NATT, 5672);
        assertFalse(service.isOptedIn(12));
        assertTrue(service.isOptedIn(13));
        assertEquals(2, service.sessionCount());
        assertTrue(service.handleKeepalive(hash(0xA), ip("5.6.7.8"), 40000, false), "it may send 0x9F");
        byte[] request = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(13).putInt(12).array();
        assertEquals(NatCallbackService.Outcome.UNKNOWN_REQUESTER,
                service.handleCallbackRequest(request, ip("5.6.7.8"), 40000, true, null));
        request = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(12).putInt(13).array();
        assertEquals(NatCallbackService.Outcome.TARGET_NOT_CAPABLE,
                service.handleCallbackRequest(request, ip("9.9.9.9"), 40000, true, null));
        assertTrue(sent.isEmpty());
    }
}
