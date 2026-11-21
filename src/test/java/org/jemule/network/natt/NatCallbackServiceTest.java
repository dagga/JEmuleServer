package org.jemule.network.natt;

import org.jemule.core.ClientState;
import org.jemule.network.Packet;
import org.jemule.network.natt.NatCallbackService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lugdunum NAT callback on one server (aMule master a6d6290be0, docs/NAT_T_Protocol.md, "Lugdunum NAT callback"),
 * with the wire examples of ed2k-server's {@code lugdunum_natt.rs} tests.
 */
class NatCallbackServiceTest {

    static final int NATT = ClientState.SRVCAP_NATTRAVERSAL;
    static final int SUPPORTS = 0x200;
    static final int REQUESTS = 0x400;
    static final int REQUIRES = 0x800;

    record Tcp(long clientId, Packet packet) {
    }

    record Udp(DatagramSocket socket, UdpEndpoint to, byte[] datagram) {
    }

    final AtomicLong now = new AtomicLong(1_000_000);
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final List<Tcp> tcp = new ArrayList<>();
    final List<Udp> udp = new ArrayList<>();
    NatCallbackService service;
    DatagramSocket socketA;
    DatagramSocket socketB;

    @BeforeEach
    void setup() throws Exception {
        service = new NatCallbackService(enabled::get, now::get, new NatCallbackService.Transport() {
            @Override public void sendTcp(long clientId, Packet packet) { tcp.add(new Tcp(clientId, packet)); }
            @Override public void sendUdp(DatagramSocket socket, UdpEndpoint to, byte[] datagram) {
                udp.add(new Udp(socket, to, datagram));
            }
        });
        // Only identities: nothing is sent through them
        socketA = new DatagramSocket(null);
        socketB = new DatagramSocket(null);
    }

    static InetAddress ip(String text) throws Exception {
        return InetAddress.getByName(text);
    }

    static byte[] hash(int b) {
        byte[] h = new byte[16];
        Arrays.fill(h, (byte) b);
        return h;
    }

    ClientState client(int hashByte, long id, String address, int caps) throws Exception {
        ClientState c = new ClientState(ip(address), 50000, id, now.get(), new AtomicLong());
        c.setUserHash(hash(hashByte));
        c.setServerCaps(caps);
        service.register(c);
        return c;
    }

    static byte[] request(long target, long requester) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt((int) target).putInt((int) requester).array();
    }

    Outcome ask(long target, long requester, String from, int port, DatagramSocket socket) throws Exception {
        return service.handleCallbackRequest(request(target, requester), ip(from), port, true, socket);
    }

    @Test
    void requestIsForwardedToTheTargetByTcp() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT);
        client(0xB, 13, "9.9.9.9", NATT);
        assertEquals(Outcome.FORWARDED_TCP, ask(13, 11, "5.6.7.8", 40000, socketA));
        assertEquals(1, tcp.size());
        assertEquals(13, tcp.getFirst().clientId());
        Packet p = tcp.getFirst().packet();
        assertEquals(Packet.PROTOCOL_ED2K, p.protocol());
        assertEquals((byte) 0x37, p.opcode());
        // <IP 4><UDP port 2 LE><requester ID 4 LE><crypt 1>: Lugdunum's 11 bytes
        assertArrayEquals(new byte[]{5, 6, 7, 8, 0x40, (byte) 0x9C, 11, 0, 0, 0, 0}, p.data());
        assertTrue(udp.isEmpty());
    }

    @Test
    void userHashFollowsWhenBothUseTheCryptLayer() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT | SUPPORTS | REQUESTS);
        client(0xB, 13, "9.9.9.9", NATT | SUPPORTS);
        assertEquals(Outcome.FORWARDED_TCP, ask(13, 11, "5.6.7.8", 40000, socketA));
        byte[] body = tcp.getFirst().packet().data();
        assertEquals(27, body.length);
        assertEquals((byte) (0x03 | 0x80), body[10]);
        assertArrayEquals(hash(0xA), Arrays.copyOfRange(body, 11, 27));
    }

    @Test
    void requiredCryptLayerTheOtherSideLacksStopsIt() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT | SUPPORTS | REQUESTS | REQUIRES);
        client(0xB, 13, "9.9.9.9", NATT);
        assertEquals(Outcome.CRYPT_MISMATCH, ask(13, 11, "5.6.7.8", 40000, socketA));
        assertTrue(tcp.isEmpty());
    }

    @Test
    void noticeLayout() {
        // Supported on both sides, requested by neither: no hash, the requester's bits alone
        assertArrayEquals(new byte[]{1, 2, 3, 4, 0x34, 0x12, 7, 0, 0, 0, 1},
                NatCallbackService.notice(0x04030201L, 0x1234, 7, 0x01, hash(1), 0x01));
        assertNull(NatCallbackService.notice(0x04030201L, 0x1234, 7, 0x00, hash(1), 0x07));
        byte[] withHash = NatCallbackService.notice(0x04030201L, 0x1234, 7, 0x07, hash(1), 0x01);
        assertEquals((byte) 0x87, withHash[10]);
        assertEquals(27, withHash.length);
    }

    @Test
    void targetThatAskedJustNowIsAnsweredByUdp() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT);
        client(0xB, 13, "9.9.9.9", NATT);
        // B asks for A first: its NAT mapping towards the server is fresh
        assertEquals(Outcome.FORWARDED_TCP, ask(11, 13, "9.9.9.9", 50000, socketB));
        now.addAndGet(5_000);
        // A's reverse request reaches B over UDP, from the socket B's request reached, to the port B used
        assertEquals(Outcome.FORWARDED_UDP, ask(13, 11, "5.6.7.8", 40000, socketA));
        Udp u = udp.getFirst();
        assertSame(socketB, u.socket());
        assertEquals(new UdpEndpoint(ip("9.9.9.9"), 50000), u.to());
        // Always 27 bytes by UDP: the requester's hash follows, without 0x80 since neither uses the crypt layer
        assertEquals(2 + 27, u.datagram().length);
        assertArrayEquals(new byte[]{(byte) 0xE3, (byte) 0xA7, 5, 6, 7, 8, 0x40, (byte) 0x9C, 11, 0, 0, 0, 0},
                Arrays.copyOf(u.datagram(), 13));
        assertArrayEquals(hash(0xA), Arrays.copyOfRange(u.datagram(), 13, 29));

        // 20 s after B's request, TCP again
        now.addAndGet(NatCallbackService.UDP_FRESH_MS);
        assertEquals(Outcome.FORWARDED_TCP, ask(13, 11, "5.6.7.8", 40000, socketA));
    }

    @Test
    void refusals() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT);
        client(0xB, 13, "9.9.9.9", NATT);
        ClientState stock = new ClientState(ip("8.8.8.8"), 1, 14, 0, new AtomicLong());
        stock.setUserHash(hash(0xC)); // logged in here without opting in: never registered
        assertEquals(Outcome.NOT_OBFUSCATED,
                service.handleCallbackRequest(request(13, 11), ip("5.6.7.8"), 40000, false, socketA));
        assertEquals(Outcome.SHORT, service.handleCallbackRequest(new byte[]{1, 2, 3}, ip("5.6.7.8"), 40000, true, socketA));
        assertEquals(Outcome.WRONG_ADDRESS, ask(13, 11, "1.1.1.1", 40000, socketA));
        assertEquals(Outcome.UNKNOWN_REQUESTER, ask(13, 99, "5.6.7.8", 40000, socketA));
        assertEquals(Outcome.TARGET_NOT_CAPABLE, ask(14, 11, "5.6.7.8", 40000, socketA));
        assertEquals(Outcome.TARGET_NOT_CAPABLE, ask(99, 11, "5.6.7.8", 40000, socketA));
        assertEquals(Outcome.SAME_CLIENT, ask(11, 11, "5.6.7.8", 40000, socketA));
        // Requester ID 0, the cross-server form, is not served here
        assertEquals(Outcome.FOREIGN_UNSUPPORTED, ask(13, 0, "1.1.1.1", 40000, socketA));
        enabled.set(false);
        assertEquals(Outcome.DISABLED, ask(13, 11, "5.6.7.8", 40000, socketA));
        assertTrue(tcp.isEmpty() && udp.isEmpty());
    }

    @Test
    void requestsAreRateLimitedPerRequester() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT);
        client(0xB, 13, "9.9.9.9", NATT);
        int forwarded = 0;
        for (int i = 0; i < 30; i++) {
            if (ask(13, 11, "5.6.7.8", 40000, socketA) == Outcome.FORWARDED_TCP) forwarded++;
        }
        assertEquals(NatCallbackService.REQUESTER_BURST, forwarded);
        assertEquals(Outcome.REQUESTER_RATE_LIMITED, ask(13, 11, "5.6.7.8", 40000, socketA));
        now.addAndGet(NatCallbackService.REQUESTER_INTERVAL_MS);
        assertEquals(Outcome.FORWARDED_TCP, ask(13, 11, "5.6.7.8", 40000, socketA));
    }

    @Test
    void noticesAreRateLimitedPerTarget() throws Exception {
        client(0xB, 13, "9.9.9.9", NATT);
        int forwarded = 0;
        int limited = 0;
        for (int r = 0; r < 4; r++) {
            long id = 21 + 2L * r;
            client(0x20 + r, id, "5.6.7." + (r + 1), NATT);
            for (int i = 0; i < NatCallbackService.REQUESTER_BURST; i++) {
                Outcome o = ask(13, id, "5.6.7." + (r + 1), 40000, socketA);
                if (o == Outcome.FORWARDED_TCP) forwarded++;
                if (o == Outcome.TARGET_RATE_LIMITED) limited++;
            }
        }
        assertEquals(NatCallbackService.TARGET_BURST, forwarded);
        assertEquals(4 * NatCallbackService.REQUESTER_BURST - NatCallbackService.TARGET_BURST, limited);
    }

    @Test
    void keepaliveIsTakenOnlyFromTheLoginIp() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT);
        assertFalse(service.handleKeepalive(hash(0xA), ip("1.1.1.1"), 40000, false), "another IP");
        assertEquals(Optional.empty(), service.endpointOf(11));
        assertFalse(service.handleKeepalive(hash(0xD), ip("5.6.7.8"), 40000, false), "unknown hash");
        assertFalse(service.handleKeepalive(Arrays.copyOf(hash(0xA), 15), ip("5.6.7.8"), 40000, false), "short");
        assertTrue(service.handleKeepalive(hash(0xA), ip("5.6.7.8"), 40001, false));
        assertEquals(Optional.of(new UdpEndpoint(ip("5.6.7.8"), 40001)), service.endpointOf(11));
        // The last one wins, and it expires
        assertTrue(service.handleKeepalive(hash(0xA), ip("5.6.7.8"), 40002, true));
        assertEquals(40002, service.endpointOf(11).orElseThrow().port());
        now.addAndGet(NatCallbackService.KEEPALIVE_TTL_MS + 1);
        assertEquals(Optional.empty(), service.endpointOf(11));
        enabled.set(false);
        assertFalse(service.handleKeepalive(hash(0xA), ip("5.6.7.8"), 40000, false), "disabled");
    }

    @Test
    void requestFromTheKeepaliveIpIsAccepted() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT);
        client(0xB, 13, "9.9.9.9", NATT);
        // A keepalive believed from another IP (as a token proof will allow) vouches for that IP
        service.onKeepalive(11, ip("10.20.30.40"), 41000);
        assertEquals(Outcome.FORWARDED_TCP, ask(13, 11, "10.20.30.40", 41000, socketA));
        assertArrayEquals(new byte[]{10, 20, 30, 40}, Arrays.copyOf(tcp.getFirst().packet().data(), 4));
        now.addAndGet(NatCallbackService.KEEPALIVE_TTL_MS + 1);
        assertEquals(Outcome.WRONG_ADDRESS, ask(13, 11, "10.20.30.40", 41000, socketA));
    }

    @Test
    void unregisterForgetsTheClientButNotItsSuccessor() throws Exception {
        ClientState first = client(0xA, 11, "5.6.7.8", NATT);
        assertTrue(service.isOptedIn(11));
        ClientState again = client(0xA, 15, "5.6.7.8", NATT); // same user hash, new login
        service.unregister(first);
        assertFalse(service.isOptedIn(11));
        assertTrue(service.isOptedIn(15));
        assertTrue(service.handleKeepalive(hash(0xA), ip("5.6.7.8"), 40000, false), "found by hash");
        assertTrue(service.endpointOf(15).isPresent());
        service.unregister(again);
        assertEquals(0, service.sessionCount());
    }

    @Test
    void udpNoticeWithTheCryptLayerKeepsItsHash() throws Exception {
        client(0xA, 11, "5.6.7.8", NATT | SUPPORTS | REQUESTS);
        client(0xB, 13, "9.9.9.9", NATT | SUPPORTS);
        ask(11, 13, "9.9.9.9", 50000, socketB);
        assertEquals(Outcome.FORWARDED_UDP, ask(13, 11, "5.6.7.8", 40000, socketA));
        byte[] datagram = udp.getFirst().datagram();
        assertEquals(2 + 27, datagram.length);
        assertEquals((byte) (0x03 | 0x80), datagram[12]);
        assertArrayEquals(hash(0xA), Arrays.copyOfRange(datagram, 13, 29));
    }

    @Test
    void clientLoggedInOverIpv6HasNoIpv4Endpoint() throws Exception {
        client(0xA, 11, "2001:db8::1", NATT);
        client(0xB, 13, "9.9.9.9", NATT);
        assertEquals(Outcome.WRONG_ADDRESS, ask(13, 11, "2001:db8::1", 40000, socketA));
        assertEquals(Outcome.WRONG_ADDRESS, ask(13, 11, "5.6.7.8", 40000, socketA));
        assertFalse(service.handleKeepalive(hash(0xA), ip("2001:db8::1"), 40000, false));
        assertEquals(Optional.empty(), service.endpointOf(11));
        // As a target it is told by TCP, never by UDP: its own requests are refused, so it has no fresh endpoint
        ask(11, 13, "9.9.9.9", 50000, socketB);
        assertEquals(1, tcp.size());
        assertArrayEquals(new byte[]{9, 9, 9, 9}, Arrays.copyOf(tcp.getFirst().packet().data(), 4));
        assertTrue(udp.isEmpty());
    }

    @Test
    void highIdIsNotRegistered() throws Exception {
        client(0xA, 0x08070605L, "5.6.7.8", NATT);
        assertEquals(0, service.sessionCount());
    }
}
