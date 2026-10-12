package org.jemule.network;

import org.jemule.core.ClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C10: server UDP obfuscation, checked against an independent re-implementation of eMule's
 * CEncryptedDatagramSocket::EncryptSendServer / DecryptReceivedServer (JDK ARCFOUR + MD5).
 */
class UdpObfuscationCompatibilityTest {

    static final int MAGICVALUE_UDP_SYNC_SERVER = 0x13EF24D5;
    static final int SERVER_PORT = SourcesCompatibilityTest.CONFIG.port();

    Server server;

    @BeforeEach
    void setup() {
        server = new Server(SourcesCompatibilityTest.CONFIG, new ClientFactory());
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    static Cipher rc4(int baseKey, int magic, byte r0, byte r1) throws Exception {
        byte[] keyData = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).putInt(baseKey).put((byte) magic).put(r0).put(r1).array();
        Cipher c = Cipher.getInstance("ARCFOUR");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(MessageDigest.getInstance("MD5").digest(keyData), "ARCFOUR"));
        return c;
    }

    /** EncryptSendServer: <marker><rand 2> RC4(<magic 4><padLen 1><pad><packet>), key MD5(key, 0x6B, rand). */
    static byte[] clientEncrypt(byte[] packet, int serverKey) throws Exception {
        byte[] pad = {1, 2, 3};
        Cipher c = rc4(serverKey, 0x6B, (byte) 0x34, (byte) 0x12);
        byte[] body = ByteBuffer.allocate(5 + pad.length + packet.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGICVALUE_UDP_SYNC_SERVER).put((byte) pad.length).put(pad).put(packet).array();
        byte[] enc = c.update(body);
        return ByteBuffer.allocate(3 + enc.length).put((byte) 0x55).put((byte) 0x34).put((byte) 0x12).put(enc).array();
    }

    /** DecryptReceivedServer: returns the ed2k packet, or null if the magic value does not match. */
    static byte[] clientDecrypt(byte[] in, int baseKey) throws Exception {
        if (in[0] == (byte) 0xE3) return null;
        Cipher c = rc4(baseKey, 0xA5, in[1], in[2]);
        byte[] dec = c.update(Arrays.copyOfRange(in, 3, in.length));
        ByteBuffer b = ByteBuffer.wrap(dec).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != MAGICVALUE_UDP_SYNC_SERVER) return null;
        int padLen = b.get() & 15;
        return Arrays.copyOfRange(dec, 5 + padLen, dec.length);
    }

    byte[] exchange(byte[] request, int localPort) throws Exception {
        SourcesCompatibilityTest.CapturingDatagramSocket cds = new SourcesCompatibilityTest.CapturingDatagramSocket() {
            @Override public int getLocalPort() { return localPort; }
        };
        var m = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, cds, new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), 40000));
        assertEquals(1, cds.sent.size(), "one answer expected");
        return cds.sent.getFirst().getData();
    }

    static byte[] statReq(int challenge) {
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0xE3).put((byte) 0x96).putInt(challenge).array();
    }

    @Test
    void encryptedPingIsAnsweredEncrypted() throws Exception {
        // 1. plain ping: the client learns its server UDP key (offset 36 of OP_GLOBSERVSTATRES)
        byte[] plainAnswer = exchange(statReq(0x55AA1234), SERVER_PORT + 4);
        int serverKey = ByteBuffer.wrap(plainAnswer, 2 + 36, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertNotEquals(0, serverKey);

        // 2. from then on every packet is obfuscated with that key (CUDPSocket::SendPacket)
        byte[] answer = exchange(clientEncrypt(statReq(0x55AA4321), serverKey), SERVER_PORT + 12);
        byte[] decrypted = clientDecrypt(answer, serverKey);
        assertNotNull(decrypted, "answer must be obfuscated with the server key");
        assertEquals((byte) 0xE3, decrypted[0]);
        assertEquals((byte) 0x97, decrypted[1]);
        assertEquals(0x55AA4321, ByteBuffer.wrap(decrypted, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(), "challenge echoed");
    }

    @Test
    void cryptPingIsAnsweredWithTheChallengeAsKey() throws Exception {
        // CServerList::ServerStats: raw <challenge 4><random padding> sent unencrypted to port + 12
        int challenge = 0x1234567;
        byte[] raw = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(challenge).put(new byte[]{9, 8, 7, 6, 5}).array();
        byte[] answer = exchange(raw, SERVER_PORT + 12);
        byte[] decrypted = clientDecrypt(answer, challenge);
        assertNotNull(decrypted);
        assertEquals((byte) 0x97, decrypted[1]);
        assertEquals(challenge, ByteBuffer.wrap(decrypted, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    @Test
    void serverKeyDependsOnTheClientIp() throws Exception {
        int k1 = org.jemule.security.UdpObfuscation.serverKeyFor(InetAddress.getByName("10.0.0.1"));
        int k2 = org.jemule.security.UdpObfuscation.serverKeyFor(InetAddress.getByName("10.0.0.2"));
        assertEquals(k1, org.jemule.security.UdpObfuscation.serverKeyFor(InetAddress.getByName("10.0.0.1")));
        assertNotEquals(k1, k2);
    }

    @Test
    void globServStatResAnnouncesTheObfuscationPorts() throws Exception {
        byte[] answer = exchange(statReq(0x55AA0001), SERVER_PORT + 4);
        // CUDPSocket::ProcessPacket (OP_GLOBSERVSTATRES), offsets relative to the payload
        ByteBuffer b = ByteBuffer.wrap(answer, 2, 40).slice().order(ByteOrder.LITTLE_ENDIAN);
        int udpFlags = b.getInt(24);
        assertTrue((udpFlags & 0x200) != 0 && (udpFlags & 0x400) != 0);
        assertEquals(SERVER_PORT + 12, Short.toUnsignedInt(b.getShort(32)), "UDP obfuscation port");
        assertEquals(SERVER_PORT, Short.toUnsignedInt(b.getShort(34)), "TCP obfuscation port");
        assertNotEquals(0, b.getInt(36), "server UDP key");
    }
}
