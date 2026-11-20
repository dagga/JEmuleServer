package org.jemule.network.natt;

import org.jemule.config.NatTraversalConfig;
import org.jemule.core.ClientState;
import org.jemule.network.Packet;
import org.jemule.network.natt.CrossServerCallback.Outcome;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-server NAT callback as agreed in andrey23127/ed2k-server#40 (ed2k-server 0.9.86) and read by aMule master
 * a6d6290be0 (NatServerHolePunch::ParseForeignCallbackAnswer, LugdunumNatTraversal::ParseForwardedCallback).
 */
class CrossServerCallbackTest {

    static final long TARGET_ID = 7;
    static final byte[] TARGET_HASH = hash(0x11);
    static final int EXTENSION = ClientState.SRVCAP_NATTRAVERSAL | ClientState.SRVCAP_HOLEPUNCH_EXTENSION;

    record Tcp(long clientId, Packet packet) {
    }

    record Udp(DatagramSocket socket, UdpEndpoint to, byte[] datagram) {
    }

    final AtomicLong now = new AtomicLong(1_000_000);
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final AtomicBoolean crossServer = new AtomicBoolean(true);
    final Set<InetAddress> blocked = new HashSet<>();
    final List<Tcp> tcp = new CopyOnWriteArrayList<>();
    final List<Udp> udp = new CopyOnWriteArrayList<>();
    NatCallbackService service;
    ClientState target;
    InetAddress requesterIp;
    InetAddress targetIp;
    DatagramSocket obfuscationPort;

    @BeforeEach
    void setup() throws Exception {
        service = new NatCallbackService(enabled::get, crossServer::get, blocked::contains, now::get,
                new NatCallbackService.Transport() {
                    @Override public void sendTcp(long clientId, Packet packet) { tcp.add(new Tcp(clientId, packet)); }
                    @Override public void sendUdp(DatagramSocket socket, UdpEndpoint to, byte[] datagram) {
                        udp.add(new Udp(socket, to, datagram));
                    }
                });
        requesterIp = InetAddress.getByName("192.0.2.10");
        targetIp = InetAddress.getByName("198.51.100.20");
        obfuscationPort = new DatagramSocket(null); // an identity only: nothing is sent through it
        target = login(targetIp, TARGET_ID, TARGET_HASH, EXTENSION | ClientState.SRVCAP_SUPPORTCRYPT);
    }

    @AfterEach
    void tearDown() {
        obfuscationPort.close();
    }

    /** A client that opted in at login (LoginHandler registers only those). */
    ClientState login(InetAddress ip, long id, byte[] userHash, int caps) {
        ClientState client = new ClientState(ip, 4662, id, now.get(), new AtomicLong(now.get()));
        client.setUserHash(userHash);
        client.setServerCaps(caps);
        service.register(client);
        return client;
    }

    static byte[] hash(int b) {
        byte[] h = new byte[16];
        Arrays.fill(h, (byte) b);
        return h;
    }

    /** OP_NAT_CALLBACKREQUEST payload as NatServerHolePunch::BuildForeignCallbackRequest builds it (crypt -1: none). */
    static byte[] request(long targetId, int crypt) {
        ByteBuffer b = ByteBuffer.allocate(crypt < 0 ? 8 : 9).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) targetId).putInt(0);
        if (crypt >= 0) b.put((byte) crypt);
        return b.array();
    }

    NatCallbackService.Outcome ask(InetAddress from, int port, long targetId, int crypt) {
        return service.handleCallbackRequest(request(targetId, crypt), from, port, true, obfuscationPort);
    }

    Outcome askOutcome(InetAddress from, int port, long targetId, int crypt) {
        return service.crossServer().onRequest(targetId, crypt, from, port, obfuscationPort);
    }

    void sendKeepalive(InetAddress from, int port) {
        service.handleKeepalive(TARGET_HASH.clone(), from, port, false);
    }

    void advance(long ms) {
        now.addAndGet(ms);
        service.tick();
    }

    List<Packet> wakes(long id) {
        return tcp.stream().filter(t -> t.clientId() == id).map(Tcp::packet).toList();
    }

    @Test
    void theTargetIsWokenAndItsNextKeepaliveGoesToTheRequester() {
        assertEquals(NatCallbackService.Outcome.FOREIGN_WOKEN, ask(requesterIp, 4672, TARGET_ID, 0x01));

        // 0x37 <requester IP 4><requester UDP port 2><0 4><crypt 1>: ParseForwardedCallback, no hash
        List<Packet> wakes = wakes(TARGET_ID);
        assertEquals(1, wakes.size());
        Packet wake = wakes.getFirst();
        assertEquals(Packet.PROTOCOL_ED2K, wake.protocol());
        assertEquals(NatOpcodes.OP_NAT_CALLBACKREQUESTED, wake.opcode());
        assertEquals(11, wake.data().length);
        ByteBuffer w = ByteBuffer.wrap(wake.data()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((int) ClientState.ipToLong(requesterIp), w.getInt());
        assertEquals(4672, Short.toUnsignedInt(w.getShort()));
        assertEquals(0, w.getInt(), "requester ID 0: a cross-server wake");
        assertEquals(0x01, w.get(), "the requester's crypt byte as it sent it");
        assertTrue(udp.isEmpty(), "the answer waits for the target's 0x9F");

        // The target's 0x9F burst: its source is the endpoint the requester punches and dials
        advance(300);
        sendKeepalive(targetIp, 51234);
        assertEquals(1, udp.size());
        Udp sent = udp.getFirst();
        assertSame(obfuscationPort, sent.socket(), "from the port the request came to");
        assertEquals(new UdpEndpoint(requesterIp, 4672), sent.to(), "to the requester's endpoint");
        byte[] answer = sent.datagram();
        assertEquals(2 + 27, answer.length, "always 27 bytes (ParseForeignCallbackAnswer)");
        assertEquals(Packet.PROTOCOL_ED2K, answer[0]);
        assertEquals(NatOpcodes.OP_NAT_CALLBACKREQUESTED_UDP, answer[1]);
        ByteBuffer a = ByteBuffer.wrap(answer, 2, 27).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((int) ClientState.ipToLong(targetIp), a.getInt());
        assertEquals(51234, Short.toUnsignedInt(a.getShort()));
        assertEquals(TARGET_ID, a.getInt());
        assertEquals(0x01, a.get(), "target supports the crypt layer, nobody asks for it: no 0x80");
        byte[] h = new byte[16];
        a.get(h);
        assertArrayEquals(TARGET_HASH, h);
        assertEquals(1, service.crossServer().count(Outcome.WOKEN));

        // A second 0x9F of the burst does not answer again; the copies follow at +1.2 s and +3 s
        sendKeepalive(targetIp, 51234);
        assertEquals(1, udp.size());
        advance(1_199);
        assertEquals(1, udp.size());
        advance(1);
        assertEquals(2, udp.size());
        assertArrayEquals(answer, udp.get(1).datagram());
        advance(1_800);
        assertEquals(3, udp.size());
        advance(10_000);
        assertEquals(3, udp.size(), "two copies only");
        assertEquals(0, service.crossServer().inFlight());
        assertEquals(1, service.crossServer().answers());
    }

    @Test
    void withoutAKeepaliveWithinThreeSecondsTheLastKnownEndpointIsSent() {
        sendKeepalive(targetIp, 40000); // the periodic 0x9F, before the request
        assertEquals(Optional.of(new UdpEndpoint(targetIp, 40000)), service.endpointOf(TARGET_ID));

        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4672, TARGET_ID, 0));
        advance(2_999);
        assertTrue(udp.isEmpty());
        advance(1);
        assertEquals(1, udp.size());
        ByteBuffer a = ByteBuffer.wrap(udp.getFirst().datagram(), 2, 27).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((int) ClientState.ipToLong(targetIp), a.getInt());
        assertEquals(40000, Short.toUnsignedInt(a.getShort()));
    }

    @Test
    void noEndpointKnownMeansNoAnswer() {
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4672, TARGET_ID, 0));
        advance(10_000);
        assertTrue(udp.isEmpty());
        assertEquals(0, service.crossServer().inFlight());
    }

    @Test
    void aLugdunumOnlyTargetIsWokenButItsRequesterGetsNothing() throws Exception {
        ClientState lugdunum = login(InetAddress.getByName("198.51.100.30"), 9, hash(0x22),
                ClientState.SRVCAP_NATTRAVERSAL);
        assertEquals(Outcome.WOKEN_WITHOUT_ANSWER, askOutcome(requesterIp, 4672, 9, 0));
        List<Packet> wakes = wakes(9);
        assertEquals(1, wakes.size());
        assertArrayEquals(NatCallbackService.notice(ClientState.ipToLong(requesterIp), 4672, 0, 0, null, 0),
                wakes.getFirst().data(), "Lugdunum's notice, requester ID 0, no hash");
        service.onKeepalive(9, lugdunum.address(), 41000);
        advance(10_000);
        assertTrue(udp.isEmpty(), "Lugdunum-only: the target dials the requester itself");
    }

    @Test
    void unknownHighIdOrStockTargetsGetNothingAndTheRequesterNoAnswer() throws Exception {
        // A stock client is never registered (no 0x1000); a HighID is not registered either
        login(InetAddress.getByName("198.51.100.41"), 0x29643326L, hash(0x44), EXTENSION);
        assertEquals(Outcome.UNKNOWN_TARGET, askOutcome(requesterIp, 4672, 12345, 0));
        assertEquals(Outcome.UNKNOWN_TARGET, askOutcome(requesterIp, 4672, 0x29643326L, 0));
        assertEquals(Outcome.UNKNOWN_TARGET, askOutcome(requesterIp, 4672, 0, 0));
        advance(10_000);
        assertTrue(tcp.isEmpty());
        assertTrue(udp.isEmpty());
    }

    @Test
    void aPlainOrIpv6OrFilteredRequestIsDropped() throws Exception {
        assertEquals(NatCallbackService.Outcome.NOT_OBFUSCATED,
                service.handleCallbackRequest(request(TARGET_ID, 0), requesterIp, 4672, false, obfuscationPort));
        assertEquals(Outcome.NOT_IPV4, askOutcome(InetAddress.getByName("2001:db8::1"), 4672, TARGET_ID, 0));
        blocked.add(requesterIp);
        assertEquals(Outcome.BLOCKED, askOutcome(requesterIp, 4672, TARGET_ID, 0));
        assertEquals(NatCallbackService.Outcome.FOREIGN_REFUSED, ask(requesterIp, 4672, TARGET_ID, 0));
        assertTrue(tcp.isEmpty());
    }

    @Test
    void aRepeatedRequestWakesOnce() {
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4672, TARGET_ID, 0));
        assertEquals(Outcome.IN_FLIGHT, askOutcome(requesterIp, 4672, TARGET_ID, 0));
        assertEquals(1, tcp.size());
        // Another requester endpoint is another request
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4673, TARGET_ID, 0));
        assertEquals(2, tcp.size());
    }

    @Test
    void aSourceIpGetsABurstOfTenThenOneASecond() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertNotEquals(Outcome.RATE_LIMITED, askOutcome(requesterIp, 4672, 1000 + i, 0));
        }
        assertEquals(Outcome.RATE_LIMITED, askOutcome(requesterIp, 4672, 2000, 0));
        assertNotEquals(Outcome.RATE_LIMITED, askOutcome(InetAddress.getByName("192.0.2.11"), 4672, 2000, 0),
                "per source IP");
        now.addAndGet(1_000);
        assertNotEquals(Outcome.RATE_LIMITED, askOutcome(requesterIp, 4672, 2001, 0));
        assertEquals(Outcome.RATE_LIMITED, askOutcome(requesterIp, 4672, 2002, 0));
    }

    @Test
    void aSourceIpNamesAtMostSixtyTargetsInTenMinutes() {
        for (int i = 0; i < 60; i++) {
            now.addAndGet(1_000);
            assertEquals(Outcome.UNKNOWN_TARGET, askOutcome(requesterIp, 4672, 5000 + i, 0), "counted, known or not");
        }
        now.addAndGet(1_000);
        assertEquals(Outcome.TOO_MANY_TARGETS, askOutcome(requesterIp, 4672, TARGET_ID, 0));
        now.addAndGet(1_000);
        assertEquals(Outcome.UNKNOWN_TARGET, askOutcome(requesterIp, 4672, 5000, 0), "a target already named");
        assertTrue(tcp.isEmpty(), "a sweep over LowIDs stops before it reaches the real one");
        now.addAndGet(CrossServerCallback.TARGET_WINDOW_MS);
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4672, TARGET_ID, 0));
    }

    @Test
    void aTargetIsWokenAtMostSixTimesAMinute() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertEquals(Outcome.WOKEN, askOutcome(InetAddress.getByName("192.0.2." + (20 + i)), 4672, TARGET_ID, 0));
        }
        assertEquals(Outcome.TARGET_RATE_LIMITED, askOutcome(InetAddress.getByName("192.0.2.30"), 4672, TARGET_ID, 0));
        assertEquals(6, tcp.size());
        advance(CrossServerCallback.WAKE_WINDOW_MS);
        assertEquals(Outcome.WOKEN, askOutcome(InetAddress.getByName("192.0.2.31"), 4672, TARGET_ID, 0));
    }

    @Test
    void atMostFourRequestersWaitForOneTarget() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(Outcome.WOKEN, askOutcome(InetAddress.getByName("192.0.2." + (40 + i)), 4672, TARGET_ID, 0));
        }
        assertEquals(4, service.crossServer().inFlight(), "the oldest was dropped");
        sendKeepalive(targetIp, 51234);
        assertEquals(4, udp.size());
    }

    @Test
    void cryptRules() {
        int support = CrossServerCallback.CRYPT_SUPPORT;
        int request = CrossServerCallback.CRYPT_REQUEST;
        int require = CrossServerCallback.CRYPT_REQUIRE;
        int all = support | request | require;
        // With an answer the requester dials with the target's hash: "require" needs the other side's support only
        assertTrue(CrossServerCallback.cryptCompatible(true, all, support));
        assertTrue(CrossServerCallback.cryptCompatible(true, support, all));
        assertFalse(CrossServerCallback.cryptCompatible(true, all, 0));
        assertFalse(CrossServerCallback.cryptCompatible(true, 0, all));
        assertTrue(CrossServerCallback.cryptCompatible(true, 0, 0));
        // A Lugdunum-only target dials without the requester's hash: nobody may require the layer
        assertFalse(CrossServerCallback.cryptCompatible(false, all, all));
        assertTrue(CrossServerCallback.cryptCompatible(false, support | request, support));

        assertEquals(0x01 | 0x80, CrossServerCallback.answerCrypt(support, support | request));
        assertEquals(0x03 | 0x80, CrossServerCallback.answerCrypt(support | request, support));
        assertEquals(0x01, CrossServerCallback.answerCrypt(support, support));
        assertEquals(0x03, CrossServerCallback.answerCrypt(support | request, 0));
    }

    @Test
    void cryptMismatchesAreRefused() throws Exception {
        login(InetAddress.getByName("198.51.100.50"), 13, hash(0x55), EXTENSION); // no crypt layer
        assertEquals(Outcome.CRYPT_MISMATCH, askOutcome(requesterIp, 4672, 13, 0x07));
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4672, 13, 0x01));
        // A Lugdunum-only target: a required layer on either side stops it
        login(InetAddress.getByName("198.51.100.51"), 15, hash(0x56),
                ClientState.SRVCAP_NATTRAVERSAL | ClientState.SRVCAP_SUPPORTCRYPT | ClientState.SRVCAP_REQUESTCRYPT
                        | ClientState.SRVCAP_REQUIRECRYPT);
        assertEquals(Outcome.CRYPT_MISMATCH, askOutcome(requesterIp, 4672, 15, 0x07));
        assertEquals(1, tcp.size());
    }

    @Test
    void aKeepaliveFromAnotherAddressAnswersNobody() throws Exception {
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4672, TARGET_ID, 0));
        // A user hash is public: from another address it moves nothing
        sendKeepalive(InetAddress.getByName("203.0.113.66"), 50000);
        assertTrue(service.endpointOf(TARGET_ID).isEmpty());
        assertTrue(udp.isEmpty());

        sendKeepalive(targetIp, 50000);
        assertEquals(1, udp.size());
    }

    @Test
    void aTargetThatLeftWhileWaitedForIsNotAnswered() throws Exception {
        sendKeepalive(targetIp, 40000);
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4672, TARGET_ID, 0));
        service.unregister(target);
        assertEquals(Outcome.UNKNOWN_TARGET, askOutcome(requesterIp, 4673, TARGET_ID, 0));
        // Another client takes the LowID: the old request does not answer with its endpoint
        ClientState next = login(targetIp, TARGET_ID, hash(0x66), EXTENSION);
        service.onKeepalive(TARGET_ID, next.address(), 40001);
        advance(5_000);
        assertTrue(udp.isEmpty());
    }

    @Test
    void offByDefaultAndAnnouncedOnlyWhenTaken() {
        assertEquals(NatTraversalConfig.DEFAULT, NatTraversalConfig.fromProperties(new java.util.Properties()));
        assertFalse(NatTraversalConfig.DEFAULT.natCallbackCrossServer());
        assertFalse(new NatTraversalConfig(false, true).crossServerEnabled(), "needs natCallback");
        assertTrue(new NatTraversalConfig(true, true).crossServerEnabled());

        assertEquals(Tag.SERVER_UDP_FLAGS | Tag.UDPFLG_FOREIGN_CALLBACK, service.udpFlags());
        assertEquals(0x100000, Tag.UDPFLG_FOREIGN_CALLBACK);

        // natCallbackCrossServer alone does nothing
        enabled.set(false);
        assertEquals(Tag.SERVER_UDP_FLAGS, service.udpFlags());
        assertEquals(NatCallbackService.Outcome.DISABLED, ask(requesterIp, 4672, TARGET_ID, 0));

        enabled.set(true);
        crossServer.set(false);
        assertEquals(Tag.SERVER_UDP_FLAGS, service.udpFlags());
        assertEquals(NatCallbackService.Outcome.FOREIGN_UNSUPPORTED, ask(requesterIp, 4672, TARGET_ID, 0));
        assertTrue(tcp.isEmpty(), "a foreign request is dropped while the cross-server callback is off");
    }

    @Test
    void aTargetThatAskedItselfWithinTwentySecondsIsWokenByUdp() throws Exception {
        login(InetAddress.getByName("198.51.100.60"), 21, hash(0x77), ClientState.SRVCAP_NATTRAVERSAL);
        // The target's own same-server 0xA6, from its login IP: its NAT mapping towards the server is open
        byte[] own = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(21).putInt((int) TARGET_ID).array();
        service.handleCallbackRequest(own, targetIp, 52000, true, obfuscationPort);
        udp.clear();
        tcp.clear();

        now.addAndGet(5_000);
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4672, TARGET_ID, 0x01));
        assertTrue(tcp.isEmpty());
        assertEquals(1, udp.size());
        Udp wake = udp.getFirst();
        assertEquals(new UdpEndpoint(targetIp, 52000), wake.to());
        assertEquals(NatOpcodes.OP_NAT_CALLBACKREQUESTED_UDP, wake.datagram()[1]);
        assertEquals(2 + 11, wake.datagram().length, "requester ID 0, no hash");
        assertEquals(0, ByteBuffer.wrap(wake.datagram(), 2 + 6, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());

        now.addAndGet(NatCallbackService.UDP_FRESH_MS);
        assertEquals(Outcome.WOKEN, askOutcome(requesterIp, 4673, TARGET_ID, 0x01));
        assertEquals(1, tcp.size(), "by TCP once its own request is older");
    }

    @Test
    void theCryptByteIsOptional() {
        assertEquals(NatCallbackService.Outcome.FOREIGN_WOKEN, ask(requesterIp, 4672, TARGET_ID, -1));
        assertEquals(1, tcp.size());
        assertEquals(0, tcp.getFirst().packet().data()[10], "absent crypt byte: none");
    }
}
