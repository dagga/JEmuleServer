package org.jemule.network.natt;

import org.jemule.core.ClientState;
import org.jemule.network.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0x9F (OP_NATT_KEEPALIVE) with the session token (natCallbackToken): the user hash alone from the login IP, the
 * token's proof from any address (ed2k-server 0.9.88; aMule master a6d6290be0, docs/NAT_T_Protocol.md).
 */
class KeepaliveProofTest {

    static final byte[] USER_HASH = HexFormat.of().parseHex("101112131415161718191a1b1c1d1e1f");
    static final long LOW_ID = 4243;
    static final boolean PLAIN = false;
    static final boolean OBFUSCATED = true;

    final AtomicLong now = new AtomicLong(1_000_000);
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final AtomicBoolean tokenEnabled = new AtomicBoolean(true);
    NatCallbackService service;
    InetAddress loginIp;
    InetAddress otherIp;
    ClientState client;

    @BeforeEach
    void setUp() throws Exception {
        service = new NatCallbackService(enabled::get, tokenEnabled::get, now::get, new NatCallbackService.Transport() {
            @Override public void sendTcp(long clientId, Packet packet) { }
            @Override public void sendUdp(DatagramSocket socket, UdpEndpoint to, byte[] datagram) { }
        }, new KeepaliveToken());
        loginIp = InetAddress.getByName("192.0.2.10");
        otherIp = InetAddress.getByName("198.51.100.20");
        client = client(USER_HASH, LOW_ID, ClientState.SRVCAP_NATTRAVERSAL);
    }

    /** A client logged in from {@link #loginIp}, registered as LoginHandler does for an opted-in client. */
    ClientState client(byte[] userHash, long id, int caps) {
        ClientState state = new ClientState(loginIp, 51000, id, now.get(), new AtomicLong(now.get()));
        state.setUserHash(userHash);
        state.setServerCaps(caps);
        if (NatCallbackService.optsIn(caps)) service.register(state);
        return state;
    }

    boolean keepalive(InetAddress from, int port, byte[] payload) {
        return service.handleKeepalive(payload, from, port, PLAIN);
    }

    long counter(String name) {
        return service.keepaliveCounters().get(name);
    }

    @Test
    void goodProofIsTakenFromAnyAddress() {
        byte[] token = service.issueToken(client);
        assertNotNull(token);
        assertTrue(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, token, 1)));
        assertEquals(Optional.of(new UdpEndpoint(otherIp, 40001)), service.endpointOf(LOW_ID));
        // The next one moves the endpoint, from the login IP too, plain or obfuscated
        assertTrue(service.handleKeepalive(KeepaliveToken.proof(USER_HASH, token, 2), loginIp, 40002, OBFUSCATED));
        assertEquals(Optional.of(new UdpEndpoint(loginIp, 40002)), service.endpointOf(LOW_ID));
        assertEquals(2, counter("proof"));
        assertEquals(1, counter("proof_other_ip"));
    }

    @Test
    void badHmacIsRejected() {
        byte[] token = service.issueToken(client);
        byte[] forged = KeepaliveToken.proof(USER_HASH, token, 1);
        forged[20] ^= 0x40;
        assertFalse(keepalive(otherIp, 40001, forged));
        byte[] otherToken = token.clone();
        otherToken[0] ^= 1;
        assertFalse(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, otherToken, 1)));
        assertFalse(keepalive(loginIp, 40001, forged), "a bad proof moves nothing, even from the login IP");
        assertEquals(Optional.empty(), service.endpointOf(LOW_ID));
        assertEquals(3, counter("proof_bad"));
    }

    @Test
    void replayedCounterIsRejected() throws Exception {
        byte[] token = service.issueToken(client);
        byte[] first = KeepaliveToken.proof(USER_HASH, token, 7);
        assertTrue(keepalive(otherIp, 40001, first));
        // Someone on the path plays it again from elsewhere: the endpoint does not move
        InetAddress attacker = InetAddress.getByName("203.0.113.66");
        assertFalse(keepalive(attacker, 50000, first));
        assertFalse(keepalive(attacker, 50000, KeepaliveToken.proof(USER_HASH, token, 6)), "older counter");
        assertEquals(Optional.of(new UdpEndpoint(otherIp, 40001)), service.endpointOf(LOW_ID));
        assertEquals(2, counter("proof_replay"));
        assertTrue(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, token, 8)));
    }

    @Test
    void unknownUserHashIsRejected() {
        byte[] token = service.issueToken(client);
        byte[] stranger = USER_HASH.clone();
        stranger[15] ^= 1;
        assertFalse(keepalive(loginIp, 40001, stranger));
        assertFalse(keepalive(otherIp, 40001, KeepaliveToken.proof(stranger, token, 1)));
        assertEquals(Optional.empty(), service.endpointOf(LOW_ID));
    }

    @Test
    void userHashAloneCountsOnlyFromTheLoginIp() {
        service.issueToken(client);
        assertFalse(keepalive(otherIp, 40001, USER_HASH));
        assertEquals(Optional.empty(), service.endpointOf(LOW_ID));
        assertTrue(keepalive(loginIp, 40001, USER_HASH));
        assertEquals(Optional.of(new UdpEndpoint(loginIp, 40001)), service.endpointOf(LOW_ID));
        assertFalse(keepalive(loginIp, 40002, Arrays.copyOf(USER_HASH, 15)), "too short");
    }

    @Test
    void retiredTokenFormIsTheUserHashAlone() {
        // ed2k-server 0.9.87's <user hash 16><token 16>: the token itself is never taken over UDP
        byte[] token = service.issueToken(client);
        byte[] tokenForm = Arrays.copyOf(USER_HASH, 32);
        System.arraycopy(token, 0, tokenForm, 16, 16);
        assertFalse(keepalive(otherIp, 40002, tokenForm));
        assertEquals(Optional.empty(), service.endpointOf(LOW_ID));
        assertTrue(keepalive(loginIp, 40002, tokenForm));
        assertEquals(2, counter("token_form_ignored"));
    }

    @Test
    void plainUserHashIsIgnoredAfterAnObfuscatedOne() {
        byte[] token = service.issueToken(client);
        assertTrue(keepalive(loginIp, 40001, USER_HASH));
        assertTrue(service.handleKeepalive(USER_HASH, loginIp, 40002, OBFUSCATED));
        assertFalse(keepalive(loginIp, 40003, USER_HASH), "only the server UDP key makes one from now on");
        assertEquals(Optional.of(new UdpEndpoint(loginIp, 40002)), service.endpointOf(LOW_ID));
        assertTrue(service.handleKeepalive(USER_HASH, loginIp, 40004, OBFUSCATED));
        // A valid proof is not subject to that rule
        assertTrue(keepalive(otherIp, 40005, KeepaliveToken.proof(USER_HASH, token, 1)));
        assertEquals(Optional.of(new UdpEndpoint(otherIp, 40005)), service.endpointOf(LOW_ID));
    }

    @Test
    void withoutTheTokenOptionAProofIsTheUserHashAlone() {
        byte[] token = service.issueToken(client);
        byte[] proof = KeepaliveToken.proof(USER_HASH, token, 1);
        tokenEnabled.set(false);
        assertFalse(keepalive(otherIp, 40001, proof));
        assertTrue(keepalive(loginIp, 40001, proof));
        assertNull(service.issueToken(client), "no token while natCallbackToken is off");
        tokenEnabled.set(true);
        enabled.set(false);
        assertNull(service.issueToken(client), "nor while natCallback is off");
    }

    @Test
    void sessionWithoutTokenReadsAProofAsTheUserHashAlone() {
        byte[] proof = KeepaliveToken.proof(USER_HASH, new byte[16], 1);
        assertFalse(keepalive(otherIp, 40001, proof));
        assertTrue(keepalive(loginIp, 40001, proof));
    }

    @Test
    void onlyASessionThatMaySendKeepalivesGetsAToken() {
        ClientState legacy = client(HexFormat.of().parseHex("202122232425262728292a2b2c2d2e2f"), 77, 0x0200);
        assertNull(service.issueToken(legacy), "no 0x1000, 0x100000 nor 0xF9: no unsolicited tag");

        ClientState extension = client(HexFormat.of().parseHex("303132333435363738393a3b3c3d3e3f"), 79,
                ClientState.SRVCAP_HOLEPUNCH_EXTENSION);
        assertNotNull(service.issueToken(extension), "SRVCAP_HOLEPUNCH_EXTENSION");

        ClientState udpPorts = client(HexFormat.of().parseHex("404142434445464748494a4b4c4d4e4f"), 81, 0);
        udpPorts.setEmuleUdpPorts((4672 << 16) | 4672);
        assertNotNull(service.issueToken(udpPorts), "UDP port in CT_EMULE_UDPPORTS");
        assertNotNull(service.issueToken(client), "SRVCAP_NATTRAVERSAL");
        // Neither opted in to the callback: their keepalives have no use here (unchanged callback behaviour)
        assertFalse(keepalive(loginIp, 40001, udpPorts.userHash()));
        assertFalse(service.isOptedIn(81));
    }

    @Test
    void highIdGetsATokenButIsNotRecorded() {
        ClientState highId = client(HexFormat.of().parseHex("505152535455565758595a5b5c5d5e5f"),
                ClientState.ipToLong(loginIp), ClientState.SRVCAP_NATTRAVERSAL);
        byte[] token = service.issueToken(highId);
        assertNotNull(token, "it may send 0x9F");
        assertFalse(keepalive(otherIp, 40001, KeepaliveToken.proof(highId.userHash(), token, 1)));
        assertEquals(Optional.empty(), service.endpointOf(highId.clientId()));
    }

    @Test
    void logoutForgetsTheSessionTokenAndEndpoint() {
        byte[] token = service.issueToken(client);
        assertTrue(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, token, 1)));
        service.unregister(client);
        assertEquals(Optional.empty(), service.endpointOf(LOW_ID));
        assertFalse(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, token, 2)));
    }

    @Test
    void aNewLoginUnderTheSameHashGetsANewTokenAndCounter() {
        byte[] oldToken = service.issueToken(client);
        assertTrue(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, oldToken, 50)));
        ClientState again = client(USER_HASH, LOW_ID + 2, ClientState.SRVCAP_NATTRAVERSAL);
        byte[] newToken = service.issueToken(again);
        assertFalse(Arrays.equals(oldToken, newToken));
        assertFalse(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, oldToken, 51)));
        assertTrue(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, newToken, 1)), "the counter starts again");
        assertEquals(Optional.of(new UdpEndpoint(otherIp, 40001)), service.endpointOf(LOW_ID + 2));
        service.unregister(client); // the older connection closes late: the new session stays
        assertTrue(keepalive(otherIp, 40001, KeepaliveToken.proof(USER_HASH, newToken, 2)));
    }

    @Test
    void ipv6SourceIsNotRecorded() throws Exception {
        byte[] token = service.issueToken(client);
        assertFalse(keepalive(InetAddress.getByName("2001:db8::7"), 40001, KeepaliveToken.proof(USER_HASH, token, 1)));
        assertEquals(Optional.empty(), service.endpointOf(LOW_ID));
    }
}
